(ns collet.actions.llm
  (:require
   [charred.api :as charred]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [wkok.openai-clojure.api :as openai]
   [malli.json-schema :as json-schema]
   [collet.utils :as utils]
   [collet.action :as action])
  (:import
   [java.io ByteArrayOutputStream InputStream]
   [java.util Base64]
   [org.apache.tika Tika]))


(defn question->msgs
  "Convert the question to a list of messages."
  [question]
  (cond
    (string? question) [{:user question}]
    (vector? question) (map #(if (string? %) {:user %} %) question)
    :else (throw (ex-info (str question "is unknown question type") {:q question}))))


(defn ->open-ai-message
  "Convert the message to the OpenAI format."
  [{:keys [system user ai tool-calls tool tool-call-id]
    :as   msg}]
  (cond system {:role "system" :content system}
        user {:role "user" :content user}
        ai {:role "assistant" :content ai}
        tool-calls {:role "assistant" :tool_calls tool-calls}
        tool {:role "tool" :content tool :tool_call_id tool-call-id}
        :else (throw (ex-info (str msg "is unknown message type") {:msg msg}))))


(defn tool->function
  "Convert the tool to a function object for the OpenAI API."
  [tool-spec]
  (let [args          (into {}
                            (map
                             (fn [{:keys [name type :required desc]}]
                               [(keyword name) {:type type :description desc}]))
                            (:args tool-spec))
        required-args (vec (for [arg (:args tool-spec)
                                 :when (:required arg)]
                             (:name arg)))]
    {:type     "function"
     :function {:name        (:name tool-spec)
                :description (:desc tool-spec)
                :parameters  {:type       "object"
                              :properties args
                              :required   required-args}}}))


(defn ->json-schema
  [{:keys [name schema]}]
  (let [json-schema (-> schema
                        (json-schema/transform)
                        (assoc :additionalProperties false))]
    {:type "json_schema"
     :json_schema
     {:name   name
      :strict true
      :schema json-schema}}))


(defn chat-completion
  "Send a request to OpenAI chat completions."
  [{:keys [model msgs tools max-tokens top-p temperature response-format]}
   {:keys [api-key api-endpoint organization]}]
  (let [params      (cond-> (utils/assoc-some
                              {:model    (or model "gpt-4o")
                               :messages (map ->open-ai-message msgs)}
                              :temperature temperature
                              :top_p top-p
                              :max_completion_tokens max-tokens)
                      (seq tools)
                      (assoc :tools (map tool->function tools))
                      (some? response-format)
                      (assoc :response_format (->json-schema response-format)))
        completions (openai/create-chat-completion
                     params
                     (utils/assoc-some
                       {:throw-exceptions? false}
                       :api-key api-key
                       :api-endpoint api-endpoint
                       :organization organization))]
    (-> completions
        :choices
        first
        :message)))


(defn parse-arguments
  "Parse the arguments of the tool calls."
  [result]
  (update result :tool_calls
          #(map (fn [tool-call]
                  (update-in tool-call [:function :arguments]
                             (fn [arguments]
                               (charred/read-json arguments :key-fn keyword))))
                %)))


(defn select-tool-by-name
  "Select the tool by name."
  [tools function]
  (->> tools
       (filter #(= (:name function) (:name %)))
       first))


(defn apply-fn
  "Apply the tool function to the arguments."
  [{:keys [args func] :as tool} function]
  (->> args
       (map #(get (:arguments function) (keyword (:name %))))
       (apply func)))


(defn ask-open-ai
  "Send the request to OpenAI and if tools are provided, apply them to the response."
  [question
   {:keys [model tools as max-tokens temperature top-p response-format]}
   {:keys [api-key api-endpoint organization]}]
  (let [msgs   (question->msgs question)
        result (chat-completion
                {:model           model
                 :msgs            msgs
                 :tools           tools
                 :max-tokens      max-tokens
                 :top-p           top-p
                 :temperature     temperature
                 :response-format response-format}
                {:api-key      api-key
                 :api-endpoint api-endpoint
                 :organization organization})]
    (if (seq tools)
      (let [parsed-result (parse-arguments result)
            fn-results    (map (fn [{:keys [id function]}]
                                 (let [tool (select-tool-by-name tools function)]
                                   {:id       id
                                    :function function
                                    :result   (when tool
                                                (apply-fn tool function))}))
                               (:tool_calls parsed-result))]
        (conj (vec msgs)
              {:ai
               (if (= :values as)
                 (map (fn [{:keys [function result]}]
                        {(keyword (:name function)) result}) fn-results)
                 (:content
                  (chat-completion
                   {:model           model
                    :msgs            (concat msgs
                                             [{:tool-calls (-> result :tool_calls)}]
                                             (map
                                              (fn [{:keys [id result]}]
                                                {:tool         (charred/write-json-str result)
                                                 :tool-call-id id})
                                              fn-results)
                                             [(last msgs)])
                    :tools           tools
                    :max-tokens      max-tokens
                    :top-p           top-p
                    :temperature     temperature
                    :response-format response-format}
                   {:api-key      api-key
                    :api-endpoint api-endpoint
                    :organization organization})))}))
      (conj (vec msgs) {:ai (:content result)}))))


(defn prompt
  "Python-like f-string
   Example:
   ```clojure
   (f-string \"Hello, {name}!\" {:name \"world\"})
   ;; => \"Hello, world!\"
   ```
   "
  [s ctx]
  (let [get-value (fn [k]
                    (if-let [v (get ctx (keyword k))]
                      v
                      (throw (ex-info (str "key not found: " k) {:key k}))))]
    (loop [s      s
           result ""
           k      nil]
      (if-let [c (first s)]
        (cond
          (and (= c \{) k) (recur (rest s) (str result c) "")
          (= c \{) (recur (rest s) (str result) "")
          (and (= c \}) k) (recur (rest s) (str result (get-value (string/trim k))) nil)
          k (recur (rest s) result (str k c))
          :else (recur (rest s) (str result c) nil))
        result))))


(defn file->bytes
  "Read file into bytes."
  ^bytes [^InputStream input-stream]
  (with-open [xin  input-stream
              xout (ByteArrayOutputStream.)]
    (io/copy xin xout)
    (.toByteArray xout)))


(defn with-images
  "Return a message with an image.
   Args:
    - text: The prompt to ask the user.
    - images: A list of image URLs or input streams.
   Example:
   ```clojure
   (with-images \"What are in these images? Is there any difference between them?\"
       \"https://upload.wikimedia.org/wikipedia/commons/thumb/d/dd/Gfp-wisconsin-madison-the-nature-boardwalk.jpg/2560px-Gfp-wisconsin-madison-the-nature-boardwalk.jpg\"
       (io/input-stream \"/tmp/sample.png\"))
   ```"
  [text images]
  (let [image-urls (map (fn [image]
                          (if-some [image-stream (cond
                                                   (instance? InputStream image)
                                                   image
                                                   (.exists (io/file image))
                                                   (io/input-stream image))]
                            (let [bytes     (file->bytes image-stream)
                                  mime-type (.detect (Tika.) bytes)
                                  base64    (.encodeToString (Base64/getEncoder) bytes)]
                              (str "data:" mime-type ";base64," base64))
                            ;; assume it's an URL
                            image))
                        images)]
    [{:user
      (cons {:type "text" :text text}
            (map (fn [image-url]
                   {:type "image_url" :image_url {:url image-url}})
                 image-urls))}]))


(def tool-spec
  [:map
   [:name :string]
   [:desc :string]
   [:func fn?]
   [:args {:optional true}
    [:+
     [:map
      [:name :string]
      [:type :string]
      [:required {:optional true} :boolean]
      [:desc :string]]]]])


(def openai-params-spec
  [:map
   [:question :string]
   [:vars {:optional true} :map]
   [:images {:optional true} [:+ :string]]
   [:model :string]
   [:api-key :string]
   [:api-endpoint {:optional true} :string]
   [:organization {:optional true} :string]
   [:response-format {:optional true}
    [:map
     [:name :string]
     [:schema :any]]]
   [:max-tokens {:optional true} :int]
   [:temperature {:optional true} :number]
   [:top-p {:optional true} :number]
   [:tools {:optional true} [:+ tool-spec]]
   [:as {:optional true} [:enum :values]]])


(defn ask-openai
  "Send request to OpenAI chat completions.
   Args:
    - question: The question to ask.
    - vars: A map of variables to use in the question.
    - images: A list of image URLs or input streams.
    - model: The model to use.
    - api-key: The API key.
    - api-endpoint: The API endpoint.
    - organization: The organization.
    - response-format: The response format.
    - max-tokens: The maximum number of tokens.
    - temperature: The temperature.
    - top-p: The top-p.
    - tools: A list of tools.
    - as: The output format.
   Example:
   ```clojure
   (ask-openai {:question \"What programming language was created by Rich Hickey?\"
                :api-key  api-token})
   ```"
  {:malli/schema [:=> [:cat openai-params-spec]
                  :any]}
  [{:keys [question vars images model
           api-key api-endpoint organization
           response-format max-tokens temperature top-p tools as]}]
  (let [llm-params  {:model           model
                     :max-tokens      max-tokens
                     :temperature     temperature
                     :top-p           top-p
                     :tools           tools
                     :as              as
                     :response-format response-format}
        llm-options {:api-key      api-key
                     :api-endpoint api-endpoint
                     :organization organization}
        prompt      (cond-> (prompt question vars)
                      (seq images)
                      (with-images images))
        response    (-> (ask-open-ai prompt llm-params llm-options)
                        last :ai)]
    (if (some? response-format)
      (charred/read-json response :key-fn keyword)
      response)))


(defmethod action/action-fn ::openai [_]
  ask-openai)
(ns collet.actions.llm.client
  "Collet's synchronous, provider-neutral LLM boundary."
  (:require [charred.api :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [llm.sdk :as sdk]
            [llm.sdk.provider.auth :as auth]
            [malli.core :as m]
            [malli.json-schema :as json-schema])
  (:import [com.networknt.schema InputFormat Schema SchemaRegistry SpecificationVersion]
           [java.io ByteArrayOutputStream InputStream]
           [java.net URI]
           [java.util Base64]
           [org.apache.tika Tika]))


(def ^:private operations
  {:chat           {:call       sdk/complete
                    :prefix     "request"
                    :capability :chat
                    :keys       #{:messages :tools :tool-choice :temperature :top-p :max-tokens :stop :response-format :reasoning :cache :metadata}}
   :decide         {:call       sdk/decide
                    :prefix     "decision"
                    :capability :decision
                    :keys       #{:input :questions :images}}
   :embed          {:call       sdk/embed
                    :prefix     "embed"
                    :capability :embedding
                    :keys       #{:input :dimensions :encoding-format :user}}
   :moderate       {:call sdk/moderate :prefix "moderation" :capability :moderation :keys #{:input}}
   :rerank         {:call       sdk/rerank
                    :prefix     "rerank"
                    :capability :rerank
                    :keys       #{:input :documents :top-n :return-documents}}
   :generate-image {:call       sdk/generate-image
                    :prefix     "image"
                    :capability :image-generation
                    :keys       #{:input :n :size :quality :style :response-format :user}}
   :transcribe     {:call       sdk/transcribe
                    :prefix     "transcribe"
                    :capability :transcription
                    :keys       #{:file :filename :language :prompt :temperature :response-format :timestamp-granularities}}
   :speak          {:call       sdk/speak
                    :prefix     "speak"
                    :capability :tts
                    :keys       #{:input :voice :format :speed :instructions}}})


(defn- fail! [code message]
  (throw (ex-info message {:code code :collet/redact-params true})))


(def ^:private valid-request?
  (delay
   ;; Workbench can bundle a catalogue from before this action was available.
   (let [resources (enumeration-seq (.getResources (clojure.lang.RT/baseLoader) "collet/catalogue/collet-action-llm.edn"))
         schema    (some (fn [resource]
                           (some #(when (= :collet.actions.llm/request (:type %)) (:params-schema %))
                                 (edn/read-string (slurp resource))))
                         resources)]
     (when-not schema
       (fail! :llm/schema-missing "The LLM request schema is missing from the action package."))
     (m/validator schema))))


(defn- qualify [prefix value]
  (into {} (map (fn [[k v]] [(keyword prefix (name k)) v])) value))


(defn- unqualify [value]
  (into {} (map (fn [[k v]] [(keyword (name k)) v])) value))


(defn- json-key [k]
  (if (keyword? k) (subs (str k) 1) k))


(defn- bounded-bytes [source limit]
  (with-open [in  (io/input-stream source)
              out (ByteArrayOutputStream.)]
    (let [buf (byte-array 8192)]
      (loop [total 0]
        (let [n (.read in buf)]
          (if (neg? n)
            (.toByteArray out)
            (do (when (> (+ total n) limit)
                  (fail! :llm/media-too-large "Media exceeds :max-media-bytes."))
                (.write out buf 0 n)
                (recur (+ total n)))))))))


(defn- image-part [{:keys [file url base64 mime-type detail]} limit inline?]
  (when-not (= 1 (count (remove nil? [file url base64])))
    (fail! :llm/invalid-image "An image needs exactly one of :file, :url or :base64."))
  (when url
    (when-not (and (string? url) (#{"https" "http"} (.getScheme (URI. url))))
      (fail! :llm/invalid-image "Image URLs must use HTTP or HTTPS.")))
  (if (and url (not inline?))
    (cond-> {:part/type :image :image/url url} detail (assoc :image/detail detail))
    (let [bytes (cond
                  file (bounded-bytes file limit)
                  base64 (do (when-not (and (string? base64) (<= (count base64) (* 4 (inc (quot limit 3)))))
                               (fail! :llm/invalid-image "Invalid or oversized base64 image."))
                             (.decode (Base64/getDecoder) ^String base64))
                  url (let [connection (.openConnection (.toURL (URI. url)))]
                        (.setConnectTimeout connection 10000)
                        (.setReadTimeout connection 30000)
                        (bounded-bytes (.getInputStream connection) limit)))
          mime  (or mime-type (.detect (Tika.) ^bytes bytes))]
      (when (or (zero? (alength bytes))
                (> (alength bytes) limit)
                (not (#{"image/png" "image/jpeg" "image/webp" "image/gif"} mime)))
        (fail! :llm/invalid-image "Expected nonempty PNG, JPEG, WebP or GIF image data."))
      (cond-> {:part/type       :image
               :image/data      (.encodeToString (Base64/getEncoder) bytes)
               :image/mime-type mime}
        detail (assoc :image/detail detail)))))


(defn- message [{:keys [content tool-calls] :as msg} limit]
  (cond-> (qualify "message" (dissoc msg :content :tool-calls))
    (contains? msg :content)
    (assoc :message/content
      (if (string? content)
        content
        (mapv (fn [part]
                (case (:type part)
                  :text {:part/type :text :text (:text part)}
                  :image (image-part part limit false)
                  (fail! :llm/unsupported-content "Message parts must be text or images.")))
              content)))
    tool-calls (assoc :message/tool-calls
                 (mapv #(assoc (qualify "tool-call" %) :part/type :tool-call) tool-calls))))


(defn- structure [{:keys [type schema name] :or {name "result"}}]
  (let [json-schema (case type
                      :malli (json-schema/transform schema)
                      :json-schema schema
                      (fail! :llm/invalid-schema "Structured output requires :type :malli or :json-schema."))]
    ;; Local validation must not fetch schemas or resolve remote references.
    (when (some (fn [node]
                  (and (map? node)
                       (some (fn [k]
                               (when-let [ref (get node k)]
                                 (not (and (string? ref) (str/starts-with? ref "#")))))
                             [:$ref "$ref" :$dynamicRef "$dynamicRef" :$recursiveRef "$recursiveRef"])))
                (tree-seq coll? seq json-schema))
      (fail! :llm/invalid-schema "Only local JSON Schema references are supported."))
    (let [registry          (SchemaRegistry/withDefaultDialect SpecificationVersion/DRAFT_2020_12)
          ^Schema validator (.getSchema registry ^String (json/write-json-str json-schema) InputFormat/JSON)]
      {:format   {:type :json_schema :name name :strict true :json-schema json-schema}
       :validate (fn [^String text]
                   (when (or (str/blank? text) (seq (.validate validator text InputFormat/JSON)))
                     (fail! :llm/invalid-structured-output "The model's output does not satisfy the requested schema."))
                   (let [value (json/read-json text :key-fn keyword)]
                     (when (and (= :malli type) (not (m/validate schema value)))
                       (fail! :llm/invalid-structured-output "The model's output does not satisfy the requested schema."))
                     value))})))


(defn- request-data [{:keys [operation model provider-options input questions images messages response-format]
                      :as   options}
                     prefix limit]
  (let [base (qualify prefix
                      (select-keys options
                                   (disj (get-in operations [operation :keys])
                                         :input
                                         :questions :images
                                         :messages :response-format)))
        base (cond-> (assoc base (keyword prefix "model") model)
               provider-options (assoc (keyword prefix "provider-options") provider-options))]
    (case operation
      :chat (let [structured (when response-format (structure response-format))]
              {:request    (cond-> (assoc base :request/messages (mapv #(message % limit) messages))
                             structured (assoc :request/response-format (:format structured)))
               :structured structured})
      :decide
      (let [assets    (into {}
                            (map (fn [[id image]]
                                   (let [part   (image-part image limit true)
                                         format (case (:image/mime-type part)
                                                  "image/png" "png"
                                                  "image/jpeg" "jpg"
                                                  (fail! :llm/unsupported-image "Jev decisions accept PNG or JPEG images."))]
                                     [(json-key id) {:type "image" :format format :data_base64 (:image/data part)}])))
                            images)
            questions (into {}
                            (map (fn [[id q]]
                                   [(json-key id)
                                    (cond-> (update q :type keyword)
                                      (map? (:criteria q)) (update :criteria #(into {} (map (fn [[k v]] [(json-key k) v])) %)))]))
                            questions)]
        (when (and (seq assets) (get-in provider-options [:extra_body :assets]))
          (fail! :llm/invalid-input "Supply images through :images, without overriding provider assets."))
        {:request (cond-> (assoc base
                            :decision/state input
                            :decision/questions questions)
                    (seq assets) (assoc-in [:decision/provider-options :extra_body :assets] assets))})
      :embed {:request (assoc base :embed/inputs (if (string? input) [input] input))}
      :moderate {:request (assoc base :moderation/inputs (if (string? input) [input] input))}
      :rerank {:request (assoc base :rerank/query input)}
      :generate-image {:request (cond-> (assoc base :image/prompt input)
                                  response-format (assoc :image/response-format response-format))}
      :transcribe {:request (cond-> base response-format (assoc :transcribe/response-format response-format))}
      :speak {:request (assoc base :speak/input input)})))


(defn- chat-data [response structured]
  (let [parts  (:response/parts response)
        finish (:response/finish-reason response)
        text   (apply str (keep #(when (= :text (:part/type %)) (:text %)) parts))]
    (when (or (= :content-filter finish)
              (get-in response [:response/provider-data :refusal])
              (some :safety/blocked parts))
      (fail! :llm/refusal "The provider refused this request."))
    (when (#{:length :incomplete} finish)
      (fail! :llm/truncated "The provider returned incomplete output."))
    (when-not (or (seq text) (seq (:response/tool-calls response)))
      (fail! :llm/invalid-response "The provider returned no text or tool calls."))
    (if (and structured (not (seq (:response/tool-calls response))))
      (try ((:validate structured) text)
           (catch Exception _ (fail! :llm/invalid-structured-output "The model's output does not satisfy the requested schema.")))
      text)))


(defn- envelope [{:keys [operation provider model raw?]} response structured]
  (let [data     (case operation
                   :chat (chat-data response structured)
                   :decide {:answers (:decision/answers response)}
                   :embed (:embed/vectors response)
                   :moderate (mapv unqualify (:moderation/results response))
                   :rerank (mapv unqualify (:rerank/results response))
                   :generate-image (mapv unqualify (:image/images response))
                   :transcribe (unqualify (into {} (filter #(= "transcription" (namespace (key %)))) response))
                   :speak {:bytes (:audio/bytes response) :content-type (:audio/content-type response)})
        metadata (into {}
                       (keep (fn [[k v]]
                               (when (and (some? v) (#{"id" "usage" "cost" "finish-reason" "provider-data"} (name k)))
                                 [(keyword (name k)) (if (#{"usage" "cost"} (name k)) (unqualify v) v)])))
                       response)
        model    (or (some (fn [[k v]] (when (= "model" (name k)) v)) response) model)]
    (cond-> (merge {:provider provider :model model :operation operation :data data} metadata)
      (seq (:response/tool-calls response))
      (assoc :tool-calls (mapv #(unqualify (dissoc % :part/type)) (:response/tool-calls response)))
      ;; Keep signed reasoning and other replay state losslessly, without the raw body.
      (:response/parts response) (assoc :parts (:response/parts response))
      raw? (assoc :raw (some (fn [[k v]] (when (= "raw" (name k)) v)) response)))))


(defn request
  "Execute one synchronous operation. Retries are explicit; tools are returned as data."
  [{:keys [operation provider model connection retry max-media-bytes] :as options}]
  (try
    (when-not (@valid-request? options)
      (fail! :llm/invalid-request "Parameters do not satisfy the operation schema."))
    (let [{:keys [call prefix capability keys]} (get operations operation)
          provider-id                  (get {:jev :typesafe :gemini :gemini-native} provider provider)
          profile                      (when (keyword? provider-id) (sdk/provider-profile provider-id))
          limit                        (or max-media-bytes 20971520)
          common                       #{:operation :provider :model :connection :retry :provider-options :raw? :max-media-bytes}
          _ (when-not (and call
                           profile
                           (string? model)
                           (not (str/blank? model))
                           (or (nil? connection) (map? connection))
                           (pos-int? limit)
                           (empty? (set/difference (set (clojure.core/keys options)) (into common keys))))
              (fail! :llm/invalid-request "Invalid operation, provider, model or operation parameters."))
          _ (when-not (contains? (:profile/capabilities profile) capability)
              (fail! :llm/unsupported-capability "This provider does not support the requested operation."))
          _ (when (or (= :codex-backend provider-id)
                      (some (fn [node]
                              (and (map? node)
                                   (some #(true? (get node %)) [:stream :stream? "stream"])))
                            (tree-seq coll? seq (:provider-options options))))
              (fail! :llm/unsupported-capability "Streaming backends and streaming options are outside this synchronous action."))
          _ (doseq [[present? feature] [[(:tools options) :tools]
                                        [(and (= :chat operation) (:response-format options)) :json-schema]]]
              (when (and present? (not (contains? (:profile/capabilities profile) feature)))
                (fail! :llm/unsupported-capability "This provider does not support the requested chat feature.")))
          _ (when (and retry (not (map? retry)))
              (fail! :llm/invalid-retry "Retries require an explicit configuration map."))
          _ (when (and (seq (:images options)) (not (#{:typesafe :openrouter} provider-id)))
              (fail! :llm/unsupported-capability "Image decisions require a Jev-compatible provider."))
          {:keys [request structured]} (request-data options prefix limit)
          config                       (cond-> connection
                                         (and (= :decide operation) (seq (:images options)))
                                         (update :headers #(auth/merge-headers % {"TypeSafe-Danger-Zone-Images" "true"})))
          response                     (call provider-id request :config config :retry (when retry (qualify "retry" retry)))]
      (envelope options response structured))
    (catch InterruptedException _
      (.interrupt (Thread/currentThread))
      (throw (InterruptedException. "LLM request interrupted.")))
    (catch java.util.concurrent.CancellationException _
      (throw (java.util.concurrent.CancellationException. "LLM request cancelled.")))
    (catch Exception e
      ;; Provider exceptions can embed credentials, headers, media and nested causes.
      (let [data (ex-data e)]
        (throw (ex-info "LLM action failed."
                        (merge {:code                 (or (:code data) (:error/type data) :llm/request-failed)
                                :collet/redact-params true
                                :provider             provider
                                :operation            operation}
                               (select-keys data [:status :attempts :request-id :retry-after-ms])
                               (when-let [reason (get-in data [:error :error/reason])]
                                 {:reason reason}))))))))

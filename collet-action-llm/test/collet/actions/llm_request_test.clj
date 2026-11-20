(ns collet.actions.llm-request-test
  (:require [charred.api :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [collet.action :as action]
            [collet.actions.llm :as llm]
            [collet.artifact :as artifact]
            [hato.client :as http]
            [llm.sdk.models-dev :as models-dev])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))


(def base {:provider :openai :model "fixture-model" :connection {:api-key "secret-key" :base-url "http://fixture.test/v1" :timeout-ms 1234}})


(defn chat-response [text]
  {:id "chat-1" :model "fixture-model" :choices [{:message {:role "assistant" :content text} :finish_reason "stop"}]})


(defn wire [options response]
  (let [requests (atom [])]
    (with-redefs [models-dev/fetch-all (constantly nil)
                  http/request         (fn [request]
                                         (swap! requests conj (cond-> request (string? (:body request)) (update :body (fn* [%1#] (json/read-json %1# :key-fn keyword)))))
                                         {:status 200 :headers {"content-type" "application/json"} :body (if (bytes? response) response (json/write-json-str response))})]
      {:result (llm/request (merge base options)) :requests (clojure.core/deref requests)})))


(defn error [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e e)))


(deftest retries-are-opt-in-and-retain-safe-error-metadata
  (doseq [[retry expected] [[nil 1] [{:max-attempts 3 :budget-ms 1000 :base-delay-ms 0 :max-delay-ms 0 :jitter-ratio 0} 3]]]
    (let [calls   (atom 0)
          options (cond-> (assoc base
                            :operation :embed
                            :input "x")
                    retry (assoc :retry retry))]
      (with-redefs [http/request (fn [_]
                                   (swap! calls inc)
                                   {:status  503
                                    :headers {"x-request-id" "request-1" "retry-after" "0"}
                                    :body    "{\"error\":{\"message\":\"secret-key\"}}"})]
        (let [failure (error #(llm/request options))
              data    (ex-data failure)]
          (is (= expected @calls (:attempts data)))
          (is (= [503 "request-1" 0] ((juxt :status :request-id :retry-after-ms) data)))
          (is (nil? (ex-cause failure)))
          (is (not (str/includes? (pr-str data) "secret-key"))))))))


(deftest invalid-or-deferred-features-never-send
  (let [calls (atom 0)]
    (with-redefs [http/request (fn [_] (swap! calls inc))]
      (doseq [extra [{:operation :chat :messages []}
                     {:operation :chat :messages [{:role :user :content "x"}] :provider-options {:extra_body {:stream true}}}
                     {:operation :chat :provider :codex-backend :messages [{:role :user :content "x"}]}
                     {:operation :embed :input "x" :retry {:max-retries 2}}
                     {:operation       :chat
                      :messages        [{:role :user :content "x"}]
                      :response-format {:type :json-schema :schema {:$ref "https://example.test/remote-schema"}}}
                     {:operation       :chat
                      :messages        [{:role :user :content "x"}]
                      :response-format {:type :json-schema :schema {:$recursiveRef "https://example.test/remote-schema"}}}]]
        (is (some? (error #(llm/request (merge base extra))))))
      (is (zero? @calls)))))


(deftest representative-chat-wire-contracts
  (doseq [[provider response path field auth-field] [[:openai (chat-response "hello") "/chat/completions" [:messages 0 :content] "Authorization"]
                                                     [:anthropic {:id "a" :model "fixture-model" :content [{:type "text" :text "hello"}] :stop_reason "end_turn"} "/messages" [:messages 0 :content 0 :text] "x-api-key"]
                                                     [:gemini {:candidates [{:content {:role "model" :parts [{:text "hello"}]} :finishReason "STOP"}]} ":generateContent" [:contents 0 :parts 0 :text] "x-goog-api-key"]]]
    (let [{:keys [result requests]} (wire {:operation :chat :provider provider :messages [{:role :user :content "question"}]} response)
          request (first requests)]
      (is (= 1 (count requests)) (name provider))
      (is (= [provider :chat "hello"] ((juxt :provider :operation :data) result)))
      (is (str/ends-with? (:url request) path))
      (is (= "question" (get-in request (into [:body] field))))
      (is (= 1234 (:timeout request)))
      (if auth-field (is (str/includes? (get-in request [:headers auth-field]) "secret-key")) (is (= "secret-key" (get-in request [:query-params "key"]))))
      (is (not (contains? result :usage)))
      (is (not (contains? result :raw))))))


(deftest every-nonchat-operation-has-a-wire-contract
  (doseq [[options response expected suffix]
          [[{:operation :embed :input ["a" "b"]} {:model "fixture-model" :data [{:index 0 :embedding [0.1 0.2]} {:index 1 :embedding [0.3 0.4]}]} [[0.1 0.2] [0.3 0.4]] "/embeddings"]
           [{:operation :moderate :input "a"} {:id "m" :model "fixture-model" :results [{:flagged false :categories {:hate false} :category_scores {:hate 0.01}}]}
            [{:flagged? false :categories {:hate false} :scores {:hate 0.01}}] "/moderations"]
           [{:operation :rerank :provider :cohere :input "q" :documents ["a"]} {:id "r" :results [{:index 0 :relevance_score 0.9}]} [{:index 0 :score 0.9}] "/rerank"]
           [{:operation :generate-image :model "dall-e-3" :input "a tree"} {:created 123 :data [{:url "https://fixture.test/image"}]} [{:url "https://fixture.test/image"}] "/images/generations"]
           [{:operation :transcribe :model "whisper-1" :file (byte-array [1 2 3]) :filename "voice.wav"} {:text "transcribed" :language "en"} {:text "transcribed" :language "en"} "/audio/transcriptions"]
           [{:operation :speak :model "tts-1" :input "hello" :voice "alloy"} (byte-array [1 2 3]) :audio "/audio/speech"]]]
    (let [{:keys [result requests]} (wire options response)
          req (first requests)]
      (is (= 1 (count requests)) (name (:operation options)))
      (is (str/ends-with? (:url req) suffix))
      (if (= :audio expected) (is (= [1 2 3] (vec (get-in result [:data :bytes])))) (is (= expected (:data result))))
      (when (= :transcribe (:operation options)) (is (= #{"model" "file"} (set (map :name (:multipart req)))))))))


(deftest decisions-preserve-typed-answers-and-translate-assets
  (let [answers {:flag    {:type "noul" :noul 0.8}
                 :choice  {:type "choice" :choice "blue" :confidence 0.9 :probabilities {:blue 0.9 :red 0.1}}
                 :quality {:type "score" :score 1.5 :confidence 0.7 :legend {:0 "bad" :1 "good"} :probabilities {:0 0.3 :1 0.7}}}
        {:keys [result requests]} (wire {:operation :decide
                                         :provider  :jev
                                         :input     {:image "original"}
                                         :questions {:flag    {:type :noul :instructions "Is it blue?"}
                                                     :choice  {:type :choice :instructions "Color?" :criteria {:blue "Blue" :red "Red"}}
                                                     :quality {:type :score :instructions "Quality?" :criteria ["bad" "good"]}}
                                         :images    {"original" {:base64 "AQID" :mime-type "image/png"}}}
                                        {:model "fixture-model" :answers answers})
        req     (first requests)]
    (is (= "true" (get-in req [:headers "TypeSafe-Danger-Zone-Images"])))
    (is (= "png" (get-in req [:body :assets :original :format])))
    (is (= {:image "original"} (get-in req [:body :state])))
    (is (= :choice (get-in result [:data :answers "choice" :type])))
    (is (= {"0" "bad" "1" "good"} (get-in result [:data :answers "quality" :legend])))
    (is (= 0.8 (get-in result [:data :answers "flag" :noul])))))


(deftest structured-output-is-validated-locally
  (doseq [format [{:type :malli :schema [:map [:count :int]]} {:type :json-schema :schema {:type "object" :required ["count"] :properties {:count {:type "integer"}}}}]]
    (let [options {:operation :chat :messages [{:role :user :content "count"}] :response-format format}]
      (is (= {:count 2} (get-in (wire options (chat-response "{\"count\":2}")) [:result :data])))
      (doseq [text ["not json" "{\"count\":\"two\"}" "{}"]] (is (= :llm/invalid-structured-output (:code (ex-data (error (fn* [] (wire options (chat-response text)))))))))))
  (let [called (atom 0)]
    (with-redefs [http/request (fn [_] (swap! called inc) (throw (ex-info "secret-key" {:headers {"Authorization" "secret-key"}})))]
      (let [e (error (fn* [] (llm/request (merge base {:operation :embed :input "x"}))))]
        (is (= 1 (clojure.core/deref called)))
        (is (nil? (ex-cause e)))
        (is (not (str/includes? (pr-str (Throwable->map e)) "secret-key"))))
      (is (= :llm/unsupported-capability (:code (ex-data (error (fn* [] (llm/request (merge base {:operation :embed :provider :anthropic :input "x"}))))))))
      (is (= 1 (clojure.core/deref called)))))
  (is (:collet/sensitive? (action/prep {:type :collet.actions.llm/request})))
  (is (:collet/sensitive? (action/prep {:type :collet.actions.llm/openai}))))


(deftest structured-json-schema-resolves-local-references-and-requires-one-json-value
  (let [options {:operation       :chat
                 :messages        [{:role :user :content "Count the items."}]
                 :response-format {:type   :json-schema
                                   :schema {:$defs {:count {:type "integer" :minimum 1}}
                                            :$ref  "#/$defs/count"}}}]
    (is (= 2 (get-in (wire options (chat-response "2")) [:result :data])))
    (doseq [text ["0" "2 3" "2 trailing"]]
      (is (= :llm/invalid-structured-output
             (:code (ex-data (error #(wire options (chat-response text))))))
          text))
    (let [permissive (assoc-in options [:response-format :schema] {})]
      (is (= :llm/invalid-structured-output
             (:code (ex-data (error #(wire permissive (chat-response " ")))))))
      (is (nil? (get-in (wire permissive (chat-response "null")) [:result :data]))))))


(deftest tools-are-returned-without-execution
  (let [response (assoc-in (chat-response nil) [:choices 0] {:finish_reason "tool_calls" :message {:tool_calls [{:id "t1" :type "function" :function {:name "lookup" :arguments "{\"id\":1}"}}]}})
        {:keys [requests result]} (wire {:operation :chat :messages [{:role :user :content "lookup"}] :tools [{:type :function :function {:name "lookup" :parameters {:type "object"}}}]} response)]
    (is (= 1 (count requests)))
    (is (= [{:id "t1" :name "lookup" :arguments "{\"id\":1}" :provider-data {:wire_type "function"}}] (:tool-calls result)))))


(deftest speech-survives-artifact-persistence
  (let [dir (Files/createTempDirectory "collet-speech-" (make-array FileAttribute 0))]
    (try (let [value     (:result (wire {:operation :speak :model "tts-1" :input "hello" :voice "alloy"} (byte-array [0 1 -2 127])))
               published (artifact/publish-output! dir (random-uuid) (random-uuid) #:output{:kind :scalar :value value})
               restored  (artifact/read-artifact dir (:artifact published))]
           (is (= [0 1 -2 127] (vec (get-in restored [:data :bytes]))))
           (is (= (dissoc value :data) (dissoc restored :data))))
         (finally (with-open [files (Files/walk dir (make-array java.nio.file.FileVisitOption 0))] (doseq [file (reverse (sort (iterator-seq (.iterator files))))] (Files/deleteIfExists file)))))))


(deftest image-inputs-and-raw-opt-in
  (let [file (Files/createTempFile "collet-llm-image" ".png" (make-array FileAttribute 0))]
    (try (Files/write file (byte-array [1 2 3]) (make-array java.nio.file.OpenOption 0))
         (doseq [image [{:url "https://fixture.test/image.png"} {:base64 "AQID" :mime-type "image/png"} {:file (.toFile file) :mime-type "image/png"}]]
           (let [{:keys [result requests]} (wire {:operation :chat :raw? true :messages [{:role :user :content [(assoc image :type :image)]}]} (chat-response "image"))]
             (is (string? (get-in requests [0 :body :messages 0 :content 0 :image_url :url])))
             (is (= "chat-1" (get-in result [:raw :id])))))
         (finally (Files/deleteIfExists file)))))


(deftest failures-are-explicit-and-never-leak-causes
  (doseq [[response expected] [[(assoc-in (chat-response "partial") [:choices 0 :finish_reason] "length") :llm/truncated] [(assoc-in (chat-response nil) [:choices 0 :message :refusal] "no") :llm/refusal]
                               [{} :llm/invalid-response]]]
    (is (= expected (:code (ex-data (error (fn* [] (wire {:operation :chat :messages [{:role :user :content "x"}]} response)))))))))

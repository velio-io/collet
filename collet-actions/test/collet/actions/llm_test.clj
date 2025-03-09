(ns collet.actions.llm-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer :all]
   [wkok.openai-clojure.api :as openai]
   [collet.actions.llm :as sut])
  (:import
   [clojure.lang ExceptionInfo]))


(deftest question->msgs-test
  (testing "string input"
    (is (= [{:user "Hello"}]
           (sut/question->msgs "Hello"))))

  (testing "vector of strings input"
    (is (= [{:user "Hello"} {:user "World"}]
           (sut/question->msgs ["Hello" "World"]))))

  (testing "vector with mixed input"
    (is (= [{:user "Hello"} {:system "I am a system"}]
           (sut/question->msgs ["Hello" {:system "I am a system"}]))))

  (testing "invalid input"
    (is (thrown? ExceptionInfo (sut/question->msgs {:foo "bar"})))))


(deftest ->open-ai-message-test
  (testing "system message"
    (is (= {:role "system" :content "I am a system"}
           (sut/->open-ai-message {:system "I am a system"}))))

  (testing "user message"
    (is (= {:role "user" :content "Hello"}
           (sut/->open-ai-message {:user "Hello"}))))

  (testing "ai message"
    (is (= {:role "assistant" :content "Hello, I am an assistant"}
           (sut/->open-ai-message {:ai "Hello, I am an assistant"}))))

  (testing "tool calls message"
    (let [tool-calls [{:id "call1" :function {:name "test"}}]]
      (is (= {:role "assistant" :tool_calls tool-calls}
             (sut/->open-ai-message {:tool-calls tool-calls})))))

  (testing "tool message"
    (is (= {:role "tool" :content "result" :tool_call_id "call1"}
           (sut/->open-ai-message {:tool "result" :tool-call-id "call1"}))))

  (testing "invalid message"
    (is (thrown? ExceptionInfo (sut/->open-ai-message {:unknown "type"})))))


(defn ^{:desc "Get the current weather in a given location"} get-current-weather
  [^{:type "string" :desc "The city, e.g. San Francisco"} location]
  (case (string/lower-case location)
    "tokyo" {:location "Tokyo" :temperature "10" :unit "fahrenheit"}
    "san francisco" {:location "San Francisco" :temperature "72" :unit "fahrenheit"}
    "paris" {:location "Paris" :temperature "22" :unit "fahrenheit"}
    {:location location :temperature "unknown"}))


(deftest tool->function-test
  (testing "converts tool var to function definition"
    (is (= {:type     "function"
            :function {:description "Get the current weather in a given location"
                       :name        "get-current-weather"
                       :parameters  {:properties {:location {:description "The city, e.g. San Francisco"
                                                             :type        "string"}}
                                     :required   ["location"]
                                     :type       "object"}}}
           (sut/tool->function (var get-current-weather))))))


(deftest parse-arguments-test
  (testing "parses tool call arguments"
    (let [result   {:tool_calls [{:function {:arguments "{\"name\":\"John\",\"age\":30}"}}]}
          expected {:tool_calls [{:function {:arguments {:name "John" :age 30}}}]}]
      (is (= expected (sut/parse-arguments result))))))


(deftest select-tool-by-name-test
  (testing "selects tool by name"
    (let [func1 (with-meta (fn []) {:name 'func1})
          func2 (with-meta (fn []) {:name 'func2})
          tools [func1 func2]]
      (is (= func1 (sut/select-tool-by-name tools {:name "func1"})))
      (is (= func2 (sut/select-tool-by-name tools {:name "func2"})))
      (is (nil? (sut/select-tool-by-name tools {:name "func3"}))))))


(deftest chat-completion-test
  (with-redefs [openai/create-chat-completion
                (constantly
                 {:choices [{:message {:content "Hello"}}]})]
    (testing "basic completion"
      (let [result (sut/chat-completion
                    {:model "gpt-4"
                     :msgs  [{:user "Hi"}]} {})]
        (is (= {:content "Hello"} result))))

    (testing "with tools"
      (let [test-tool (with-meta (fn [name])
                                 {:name     'test-tool
                                  :desc     "A test tool"
                                  :arglists '([name])})
            result    (sut/chat-completion
                       {:model "gpt-4"
                        :msgs  [{:user "Hi"}]
                        :tools [test-tool]}
                       {})]
        (is (= {:content "Hello"} result))))))


(deftest ask-open-ai-test
  (testing "basic question without tools"
    (with-redefs [sut/chat-completion (constantly {:content "Hello, world!"})]
      (let [result (sut/ask-open-ai "Hi" {} {})]
        (is (= [{:user "Hi"} {:ai "Hello, world!"}]
               result)))))

  (testing "with tools and values output"
    (with-redefs [sut/chat-completion     (fn [_params _]
                                            {:tool_calls [{:id       "call1"
                                                           :function {:name      "echo"
                                                                      :arguments "{\"text\":\"hello\"}"}}]})
                  sut/select-tool-by-name (constantly (with-meta (fn [text] text)
                                                                 {:name     'echo
                                                                  :arglists '([text])}))
                  sut/apply-fn            (constantly "hello")]
      (let [result (sut/ask-open-ai "Use echo" {:tools [(var identity)] :as :values} {})]
        (is (= [{:user "Use echo"} {:ai [{:echo "hello"}]}]
               result))))))


(deftest ask-openai-test
  (when-let [api-token (System/getenv "OPENAI_API_KEY")]
    (testing "basic question"
      (let [result (sut/ask-openai {:question "What programming language was created by Rich Hickey?"
                                    :api-key  api-token})]
        (is (string/includes? result "Clojure"))))

    (testing "with images"
      (let [result (sut/ask-openai {:question "Logo of which programming language is this?"
                                    :images   ["resources/Clojure_logo.png"]
                                    :api-key  api-token})]
        (is (string/includes? result "Clojure"))))))
(ns collet.actions.http-test
  (:require
   [charred.api :as json]
   [clojure.test :refer :all]
   [collet.actions.http :as sut]
   [collet.arrow :as arrow]
   [collet.core :as collet]
   [collet.test-fixtures :as tf]
   [collet.test-http-server :as http]
   [tech.v3.dataset :as ds])
  (:import
   [java.io Closeable]
   [java.nio.file Files LinkOption Path]))


(use-fixtures :once
              http/with-server
              (tf/instrument! 'collet.actions.http))


(deftest basic-auth-uses-the-declared-credential-vector
  (doseq [[username password :as credentials] [["operator" "p:a:ss word"] ["usér" "päss"] ["" ""]]]
    (let [request  {:url        (http/url "/auth/basic")
                    :as         :json
                    :headers    {"X-Collet-Test" "preserved"}
                    :basic-auth credentials}
          expected (str "Basic "
                        (.encodeToString (java.util.Base64/getEncoder)
                                         (.getBytes (str username ":" password) "UTF-8")))]
      (is (= {:authorization expected :custom-header "preserved"}
             (:body (sut/make-request request))))))
  (is (nil? (get-in (sut/make-request {:url (http/url "/auth/basic") :as :json}) [:body :authorization]))))


(deftest oauth2-uses-the-same-basic-auth-contract
  (is (= "Basic Y2xpZW50OnM6ZWNyZXQ="
         (get-in (sut/get-oauth2-token {:url (http/url "/auth/basic") :basic-auth ["client" "s:ecret"]})
                 [:body :authorization]))))


(deftest basic-auth-resolves-credentials-from-pipeline-configuration
  (let [spec   {:name  :native-basic-auth
                :tasks [{:name       :fetch
                         :keep-state true
                         :actions    [{:name      :request
                                       :type      :collet.actions.http/request
                                       :selectors {'username [:config :username] 'password [:config :password]}
                                       :params    {:url        (http/url "/auth/basic")
                                                   :as         :json
                                                   :basic-auth ['username 'password]}
                                       :return    [:body :authorization]}]}]}
        config {:username "operator" :password "password"}]
    (is (= "Basic b3BlcmF0b3I6cGFzc3dvcmQ="
           (:fetch (tf/run-pipeline! (collet/compile-pipeline spec) config))))))


(deftest http-request-test
  (testing "simple http request"
    (let [actual (sut/make-request {:url (http/musicbrainz-url "/genre/all?limit=10")})]
      (is (map? actual))
      (is (every? #(some? (val %)) (select-keys actual [:body :headers :status])))
      (is (string? (:body actual)))))

  (testing "convert body to Clojure data structure"
    (let [actual (-> (sut/make-request
                      {:url    (http/musicbrainz-url "/genre/all?limit=10")
                       :accept :json
                       :as     :json})
                     :body)]
      (is (map? actual))
      (is (number? (:genre-count actual)))
      (is (vector? (:genres actual)))))

  (testing "pass query params to the request"
    (let [actual (-> (sut/make-request
                      {:url          (http/musicbrainz-url "/artist")
                       :accept       :json
                       :as           :json
                       :query-params {:limit  5
                                      :offset 10
                                      :query  "artist:fred%20AND%20type:group%20AND%20country:US"}})
                     :body)]
      (is (= (-> actual :artists count) 5))
      (is (= (-> actual :offset) 10))))

  (testing "exceptional and explicitly accepted statuses"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"unavailable"
         (sut/make-request {:url (http/url "/status/503")})))
    (is (= 204
           (:status (sut/make-request {:url (http/url "/status/204")}))))))


(deftest http-pipeline-test
  (let [events  (atom [])
        artists (atom [])]
    (testing "extracting a list of events with pagination"
      (let [pipeline-spec         {:name      :city-events
                                   :use-arrow false
                                   :tasks     [{:name         :area-events
                                                :keep-state   true
                                                :state-format :flatten
                                                :setup        [{:type      :clj/format
                                                                :name      :area-query
                                                                :selectors {'city [:config :city]}
                                                                :params    ["area:%s AND type:City" 'city]}
                                                               {:type      :collet.actions.http/request
                                                                :name      :area-request
                                                                :selectors {'area-query [:state :area-query]}
                                                                :params    {:url          (http/musicbrainz-url "/area")
                                                                            :accept       :json
                                                                            :as           :json
                                                                            :query-params {:limit 1
                                                                                           :query 'area-query}}
                                                                :return    [:body :areas [:$/cat :id]]}
                                                               {:type      :clj/ffirst
                                                                :name      :area-id
                                                                :selectors '{area-ids [:state :area-request]}
                                                                :params    '[area-ids]}
                                                               {:type      :clj/format
                                                                :name      :events-query
                                                                :selectors '{area-id [:state :area-id]}
                                                                :params    ["aid:%s AND type:Concert" 'area-id]}]
                                                :actions      [{:type      :collet.actions.http/request
                                                                :name      :events-request
                                                                :selectors {'events-query [:state :events-query]}
                                                                :params    {:url          (http/musicbrainz-url "/event")
                                                                            :accept       :json
                                                                            :as           :json
                                                                            :query-params {:limit  10
                                                                                           :offset 0
                                                                                           :query  'events-query}}
                                                                :return    [:body :events]}]

                                                :iterator     {:next false}
                                                :return       [:state :events-request]}]}
            pipeline              (collet/compile-pipeline pipeline-spec)
            run                   (tf/run-pipeline! pipeline {:city "London"})
            {:keys [area-events]} run]
        (is (= (count area-events) 10))
        (reset! events area-events)))

    (testing "extracting all artists from events"
      (let [pipeline-spec           {:name      :city-events-artists
                                     :use-arrow false
                                     :tasks     [{:name       :event-artists-rating
                                                  :keep-state true
                                                  :setup      [{:type      :slicer
                                                                :name      :event-artists
                                                                :selectors {'events [:config :events]}
                                                                :params    {:sequence 'events
                                                                            :apply    [[:flatten {:by {:artist [:relations [:$/cat [:$/cond [:not-nil? :artist]] :artist]]}}]]}}]
                                                  :actions    [{:type      :mapper
                                                                :name      :event-artist-item
                                                                :selectors {'event-artists [:state :event-artists]}
                                                                :params    {:sequence 'event-artists}}
                                                               {:type      :collet.actions.http/request
                                                                :name      :artist-details
                                                                :when      [:not-nil? [:$mapper/item :artist]]
                                                                :selectors {'artist-id [:$mapper/item :artist :id]}
                                                                :params    {:url          [(http/musicbrainz-url "/artist/%s") 'artist-id]
                                                                            :accept       :json
                                                                            :as           :json
                                                                            :query-params {:inc "ratings"}}
                                                                :return    [:body]}]
                                                  :iterator   {:next [:true? [:$mapper/has-next-item]]}
                                                  :return     [{:artist-rate [:state :artist-details :rating :value]
                                                                :event-id    [:$mapper/item :id]}]}]}
            pipeline                (collet/compile-pipeline pipeline-spec)
            run                     (tf/run-pipeline! pipeline {:events @events})
            {:keys [event-artists-rating]} run
            events-with-artists-num (->> (mapcat :relations @events)
                                         (filter (comp identity :artist))
                                         (count))
            events-no-artists-num   (->> @events
                                         (filter (fn [{:keys [relations]}]
                                                   (not (some (comp identity :artist) relations))))
                                         (count))]
        (is (= (+ events-with-artists-num events-no-artists-num)
               (count event-artists-rating)))
        (reset! artists (vec event-artists-rating))))

    (testing "combining artists ratings with events"
      (let
        [pipeline-spec
         {:name :city-events-artists
          :tasks
          [{:name :best-events
            :keep-state true
            :setup [{:type      :slicer
                     :name      :events-ratings
                     :selectors {'artists [:config :artists]}
                     :params    {:sequence 'artists
                                 :apply    [[:fold {:by [:event-id]}]]}}]
            :actions
            [{:type      :mapper
              :name      :event-rating
              :selectors {'ratings [:state :events-ratings]}
              :params    {:sequence 'ratings}}
             {:type :custom
              :name :calculated-rating
              :selectors {'event-ratings [:$mapper/item]}
              :params ['event-ratings]
              :fn
              '(fn
                [{:keys [event-id artist-rate]}]
                (let
                 [rating
                  (if
                   (seq artist-rate)
                   (double
                    (/
                     (apply + artist-rate)
                     (count artist-rate)))
                   0)]
                 {:event-id event-id
                  :rating   rating}))}]
            :iterator {:next [:true? [:$mapper/has-next-item]]}
            :return [:state :calculated-rating]}]}
         pipeline (collet/compile-pipeline pipeline-spec)
         run (tf/run-pipeline! pipeline {:artists @artists})
         {:keys [best-events]} run
         best-events-ds (first best-events)]
        (is (every? #(contains? % :rating) (ds/rows best-events-ds))
            "All events should have a rating")

        (is (= (count @events)
               (ds/row-count best-events-ds))
            "Number of events should be the same as number of rated events")))))


(deftest pipeline-complex-http-flow-test
  (testing "complex pipeline to extract artists"
    (let
      [pipeline-spec
       {:name :city-best-events
        :use-arrow false
        :tasks
        [{:name         :area-events
          :state-format :flatten
          :setup        [{:type      :clj/format
                          :name      :area-query
                          :selectors {'city [:config :city]}
                          :params    ["area:%s AND type:City" 'city]}
                         {:type      :collet.actions.http/request
                          :name      :area-request
                          :selectors {'area-query [:state :area-query]}
                          :params    {:url          (http/musicbrainz-url "/area")
                                      :accept       :json
                                      :as           :json
                                      :query-params {:limit 1
                                                     :query 'area-query}}
                          :return    [:body :areas [:$/cat :id]]}
                         {:type      :clj/first
                          :name      :area-id
                          :selectors '{area-ids [:state :area-request]}
                          :params    '[area-ids]}
                         {:type      :clj/format
                          :name      :events-query
                          :selectors '{area-id [:state :area-id]}
                          :params    ["aid:%s AND type:Concert" 'area-id]}]
          :actions      [{:type :counter
                          :name :req-count}
                         {:type      :clj/*
                          :name      :offset
                          :selectors {'req-count [:state :req-count]}
                          :params    ['req-count 10]}
                         {:type      :collet.actions.http/request
                          :name      :events-request
                          :selectors {'events-query [:state :events-query]
                                      'offset       [:state :offset]}
                          :params    {:url          (http/musicbrainz-url "/event")
                                      :accept       :json
                                      :as           :json
                                      :query-params {:limit  10
                                                     :offset 'offset
                                                     :query  'events-query}}
                          :return    [:body :events]}]
          :iterator     {:next [:< [:state :req-count] 3]}}

         {:name     :events-with-artists
          :inputs   [:area-events]
          :setup    [{:type      :slicer
                      :name      :event-artists
                      :selectors {'events [:inputs :area-events]}
                      :params    {:sequence 'events
                                  :apply    [[:flatten {:by {:artist [:relations [:$/cat [:$/cond [:not-nil? :artist]] :artist]]}}]]}}]
          :actions  [{:type      :enrich
                      :name      :artist-details
                      :target    [:state :event-artists]
                      :when      [:not-nil? [:$enrich/item :artist :id]]
                      :action    :collet.actions.http/request
                      :selectors {'artist-id [:$enrich/item :artist :id]}
                      :params    {:url          [(http/musicbrainz-url "/artist/%s") 'artist-id]
                                  :accept       :json
                                  :as           :json
                                  :query-params {:inc "ratings"}}
                      :return    [:body]
                      :fold-in   [:artist]}]
          :iterator {:next [:true? [:$enrich/has-next-item]]}}

         {:name :rated-events
          :keep-state true
          :inputs [:events-with-artists]
          :setup [{:type      :slicer
                   :name      :events-with-ratings
                   :selectors {'events [:inputs :events-with-artists]}
                   :params    {:sequence 'events
                               :apply    [[:fold
                                           {:by            :id
                                            :rollup        [:artist]
                                            :rollup-except true}]]}}]
          :actions
          [{:type      :mapper
            :name      :event-rating
            :selectors {'ratings [:state :events-with-ratings]}
            :params    {:sequence 'ratings}}
           {:type :custom
            :name :event-with-rating
            :selectors {'event-ratings [:$mapper/item]}
            :params ['event-ratings]
            :fn
            '(fn
              [{:keys [artist] :as event}]
              (let
               [ratings
                (->>
                 artist
                 (map (comp :value :rating))
                 (filter number?))
                rating
                (if
                 (seq ratings)
                 (double
                  (/
                   (apply + ratings)
                   (count ratings)))
                 0)]
               (assoc event :rating rating)))}]
          :iterator {:next [:true? [:$mapper/has-next-item]]}}]}
       pipeline (collet/compile-pipeline pipeline-spec)
       run (tf/run-pipeline! pipeline {:city "London"})]

      (let [{:keys [area-events events-with-artists rated-events]} run
            events-with-artists-num (->> (mapcat :relations area-events)
                                         (filter (comp identity :artist))
                                         (count))
            events-no-artists-num   (->> area-events
                                         (filter (fn [{:keys [relations]}]
                                                   (not (some (comp identity :artist) relations))))
                                         (count))]
        (is (= 40 (count area-events))
            "40 events should be fetched")

        (is (= (+ events-with-artists-num events-no-artists-num)
               (count events-with-artists))
            "artists should have all the events with artists or not")

        (is (every? #(contains? % :artist) events-with-artists)
            "all artists should have an artist field")

        (is (= (->> area-events
                    (map :id)
                    (distinct)
                    (count)) ;; sometimes API returns same events in different pages
               (count rated-events))
            "should match the number of events fetched in the first step")

        (is (every? #(contains? % :rating) rated-events)
            "all events should have a rating")

        (is (every? #(and (number? (:rating %))
                          (<= 0 (:rating %) 5))
                    rated-events)
            "all ratings should be numbers")))))


(deftest managed-pagination-follows-request-shapes
  (doseq [[mode config second-request headers body]
          [[:page {} [:query-params "page"] {} {}]
           [:offset {:start 0 :step 2} [:query-params "offset"] {} {}]
           [:body-cursor
            {:next-path    [:body :next]
             :cursor-param [:body :cursor]
             :continuation {:url "http://fixture/continue" :body {}}}
            [:body] {} {:next "cursor"}]
           [:header-cursor {:next-header "X-Next"} [:query-params "cursor"] {"x-next" "cursor"} {}]
           [:body-url {:next-path [:body :next]} [:url] {} {:next "/continue"}]
           [:link {} [:url] {"Link" ["</previous>; rel=prev" "<?page=2>; type=\"application/json\"; rel=\"next\""]} {}]]]
    (let [seen     (atom [])
          expected (case mode
                     (:page :offset) 2
                     :body-cursor "{\"cursor\":\"cursor\"}"
                     :header-cursor "cursor"
                     :link "http://fixture/start?page=2"
                     "http://fixture/continue")]
      (with-redefs [sut/http-request
                    (fn [request]
                      (let [n (count (swap! seen conj request))]
                        {:status  200
                         :headers (if (= n 1) headers {})
                         :body    (json/write-json-str
                                   (if (= 1 n) (assoc body :items [{:id 1}]) {:items [{:id 2}] :done true}))}))]
        (let [result (sut/request {:url          "http://fixture/start"
                                   :as           :json
                                   :content-type :json
                                   :pagination   (merge {:mode       mode
                                                         :items-path [:body :items]
                                                         :until      [:true? [:body :done]]}
                                                        config)})]
          (is (= [1 2] (mapv :id (mapcat ds/rows (:body result)))))
          (is (= expected (get-in (second @seen) second-request))))))))


(deftest pagination-spools-pages-before-fetching-the-next
  (let [paths        (atom [])
        reads        (atom [])
        written      (atom [])
        make-writer  arrow/make-writer
        read-batches arrow/read-arrow-record-batches
        request      sut/http-request
        exists?      #(Files/exists ^Path % (make-array LinkOption 0))]
    (with-redefs [arrow/make-writer               (fn [path columns]
                                                    (swap! paths conj path)
                                                    (make-writer path columns))
                  arrow/read-arrow-record-batches (fn [path columns]
                                                    (swap! reads conj path)
                                                    (read-batches path columns))
                  sut/http-request                (fn [options]
                                                    (swap! written conj (count (filter exists? @paths)))
                                                    (request options))]
      (let [{:keys [status headers body]}
            (sut/request {:url        (http/url "/pagination/batches")
                          :as         :json
                          :pagination {:mode :page :items-path [:body :items]}})]
        (with-open [pages ^Closeable body]
          (is (= 200 status))
          (is (= "41" (get headers "x-page")))
          (is (= (vec (range 41)) @written))
          (is (= 40 (count pages)))
          (is (:ds-seq (meta pages)))
          (is (empty? @reads))
          (let [first-page (first pages)]
            (is (= [1 nil "two"] (get-in (first (ds/rows first-page)) [:a/b (keyword "key with space")])))
            (is (= [(first @paths)] @reads))
            (is (not (identical? first-page (first pages)))))
          (let [second-page (second pages)]
            (is (= #{:id :new-column} (set (ds/column-names second-page))))
            (is (= [nil] (vec (:new-column second-page)))))
          (is (= (vec (range 1 41)) (mapv #(get (first (ds/rows %)) :id) pages))))
        (is (every? (complement exists?) @paths))
        (is (not (exists? (.getParent ^Path (first @paths)))))))))


(deftest paginated-arrow-pages-preserve-string-keys-and-empty-results
  (let [options {:url        (http/url "/pagination/batches")
                 :as         :json
                 :keywordize false
                 :pagination {:mode :page :items-path [:body "items"] :until [:always-true]}}]
    (with-open [pages ^Closeable (:body (sut/request options))]
      (is (= [{"id" 1 "a/b" {"key with space" [1 nil "two"]}}]
             (vec (ds/rows (first pages))))))
    (let [empty-body (:body (sut/request (assoc-in options [:pagination :start] 41)))]
      (is (empty? empty-body))
      (is (:ds-seq (meta empty-body))))))


(deftest pagination-cleans-up-pages-on-limit-failure
  (let [directories (atom #{})
        make-writer arrow/make-writer]
    (with-redefs [arrow/make-writer (fn [^Path path columns]
                                      (swap! directories conj (.getParent path))
                                      (make-writer path columns))]
      (is (= "http-page-limit"
             (try
               (sut/request {:url        (http/url "/pagination/batches")
                             :as         :json
                             :pagination {:mode :page :items-path [:body :items] :max-pages 2}})
               (catch clojure.lang.ExceptionInfo e (:code (ex-data e))))))
      (is (= 1 (count @directories)))
      (is (not (Files/exists ^Path (first @directories) (make-array LinkOption 0)))))))


(deftest managed-requests-distinguish-response-failures
  (doseq [[status body options code]
          [[200 "" {:empty-body :error} "empty-http-body"]
           [200 "{" {:empty-body :error} "invalid-json"]
           [200 "{\"x\":false}" {:response-spec [:map [:x :int]]} "invalid-http-response"]
           [429 "{}" {:retry {:max-retries 0}} "http-status"]
           [200 "{\"items\":[{\"id\":1}]}" {:pagination {:mode :page :items-path [:body :items] :max-pages 1}} "http-page-limit"]]]
    (with-redefs [sut/http-request (constantly {:status status :headers {} :body body})]
      (is (= code
             (try (sut/request (merge {:url "http://fixture" :as :json} options))
                  (catch clojure.lang.ExceptionInfo e (:code (ex-data e)))))))))


(deftest managed-requests-refuse-to-send-authentication-to-another-origin
  (let [seen (atom [])]
    (with-redefs [sut/http-request (fn [request]
                                     (swap! seen conj (:url request))
                                     {:status  200
                                      :headers {"Link" "<http://other.test/next>; rel=next"}
                                      :body    "[{\"id\":1}]"})]
      (is (= "http-origin-changed"
             (try (sut/request {:url        "http://fixture"
                                :as         :json
                                :auth       {:type :bearer :value "synthetic"}
                                :pagination {:mode :link}})
                  (catch clojure.lang.ExceptionInfo e (:code (ex-data e))))))
      (is (= ["http://fixture"] @seen)))))


(deftest poll-deadline-includes-a-blocked-request
  (with-redefs [sut/http-request (fn [_] (Thread/sleep 1000) {:status 200 :body "{}"})]
    (is (= "http-poll-timeout"
           (try (sut/poll {:url "http://fixture" :as :json :poll {:until [:always-true] :deadline-ms 20}})
                (catch clojure.lang.ExceptionInfo e (:code (ex-data e))))))))


(deftest oauth-refresh-is-shared-and-a-rejected-token-is-replaced-once
  (let [grants  (atom [])
        auth    {:type          :oauth2
                 :token-url     "http://fixture/oauth"
                 :client-id     (str (random-uuid))
                 :client-secret "synthetic"
                 :refresh-token "initial"}
        request {:url "http://fixture/resource" :as :json :auth auth}]
    (with-redefs [sut/http-request
                  (fn [{:keys [url form-params headers]}]
                    (if (= url (:token-url auth))
                      (let [n (count (swap! grants conj (:refresh_token form-params)))]
                        {:status 200
                         :body   (json/write-json-str {:access_token  (str "token-" n)
                                                       :refresh_token (str "refresh-" n)
                                                       :expires_in    3600})})
                      (if (= "Bearer token-1" (get headers "Authorization"))
                        {:status 401 :body "{}"}
                        {:status 200 :body "{\"ok\":true}"})))]
      (let [jobs (mapv (fn [_] (future (sut/request request))) (range 8))]
        (is (every? #(= {:ok true} (:body (deref % 5000 nil))) jobs))
        (is (= ["initial" "refresh-1"] @grants))))))

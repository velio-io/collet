(ns collet.actions.http-test
  (:require
   [clojure.test :refer :all]
   [collet.core :as collet]
   [collet.actions.http :as sut]
   [collet.test-fixtures :as tf]))


(use-fixtures :once (tf/instrument! 'collet.actions.http))


;; API docs https://musicbrainz.org/doc/MusicBrainz_API


(deftest http-request-test
  (testing "simple http request"
    (let [actual (sut/make-request {:url "https://musicbrainz.org/ws/2/genre/all?limit=10"})]
      (is (map? actual))
      (is (every? #{:opts :body :headers :status} (keys actual)))
      (is (string? (:body actual)))))

  (testing "convert body to Clojure data structure"
    (let [actual (-> (sut/make-request
                      {:url    "https://musicbrainz.org/ws/2/genre/all?limit=10"
                       :accept :json
                       :as     :json})
                     :body)]
      (is (map? actual))
      (is (number? (:genre-count actual)))
      (is (vector? (:genres actual)))))

  (testing "pass query params to the request"
    (let [actual (-> (sut/make-request
                      {:url          "https://musicbrainz.org/ws/2/artist"
                       :accept       :json
                       :as           :json
                       :query-params {:limit  5
                                      :offset 10
                                      :query  "artist:fred%20AND%20type:group%20AND%20country:US"}})
                     :body)]
      (is (= (-> actual :artists count) 5))
      (is (= (-> actual :offset) 10)))))


(deftest http-pipeline-test
  (let [events  (atom [])
        artists (atom [])]
    (testing "extracting a list of events with pagination"
      (let [pipeline-spec {:name  :city-events
                           :tasks [{:name     :area-events
                                    :setup    [{:type      :clj/format
                                                :name      :area-query
                                                :selectors {'city [:config :city]}
                                                :params    ["area:%s AND type:City" 'city]}
                                               {:type      :http
                                                :name      :area-request
                                                :selectors {'area-query [:state :area-query]}
                                                :params    {:url          "https://musicbrainz.org/ws/2/area"
                                                            :accept       :json
                                                            :as           :json
                                                            :query-params {:limit 1
                                                                           :query 'area-query}}
                                                :return    [:body :areas [:cat :id]]}
                                               {:type      :clj/ffirst
                                                :name      :area-id
                                                :selectors '{area-ids [:state :area-request]}
                                                :params    '[area-ids]}
                                               {:type      :clj/format
                                                :name      :events-query
                                                :selectors '{area-id [:state :area-id]}
                                                :params    ["aid:%s AND type:Concert" 'area-id]}]
                                    :actions  [{:type :counter
                                                :name :req-count}
                                               {:type      :clj/*
                                                :name      :offset
                                                :selectors {'req-count [:state :req-count]}
                                                :params    ['req-count 10]}
                                               {:type      :http
                                                :name      :events-request
                                                :selectors {'events-query [:state :events-query]
                                                            'offset       [:state :offset]}
                                                :params    {:url          "https://musicbrainz.org/ws/2/event"
                                                            :accept       :json
                                                            :as           :json
                                                            :rate         1
                                                            :query-params {:limit  10
                                                                           :offset 'offset
                                                                           :query  'events-query}}
                                                :return    [:body :events]}]
                                    :iterator {:data [:state :events-request]
                                               :next [:< [:state :req-count] 3]}}]}
            pipeline      (collet/compile-pipeline pipeline-spec)
            {:keys [area-events]} (pipeline {:city "London"})]
        (is (= (count (flatten area-events)) 40))
        (reset! events area-events)))

    (testing "extracting all artists from events"
      (let [pipeline-spec {:name  :city-events-artists
                           :tasks [{:name     :event-artists-rating
                                    :actions  [{:type      :slicer
                                                :name      :event-artists
                                                :selectors {'events [:config :events]}
                                                :params    {:sequence   'events
                                                            :cat?       true
                                                            :flatten-by {:artist-id [:relations [:cat [:cond [:not-nil? :artist]] :artist :id]]}
                                                            :keep-keys  {:event-id [:id]}}}
                                               {:type      :http
                                                :name      :artist-details
                                                :selectors {'artist [:state :event-artists :current :artist-id]}
                                                :params    {:url          ["https://musicbrainz.org/ws/2/artist/%s" 'artist]
                                                            :accept       :json
                                                            :as           :json
                                                            :rate         1
                                                            :query-params {:inc "ratings"}}
                                                :return    [:body]}]
                                    :iterator {:data [{:artist-rate [:state :artist-details :rating :value]
                                                       :event-id    [:state :event-artists :current :event-id]}]
                                               :next [:not-nil? [:state :event-artists :next]]}}]}
            pipeline      (collet/compile-pipeline pipeline-spec)
            {:keys [event-artists-rating]} (pipeline {:events @events})
            events        (flatten @events)]
        (let [number-of-artists (->> (mapcat :relations events)
                                     (filter (comp identity :artist))
                                     (count))]
          (is (= (count event-artists-rating) number-of-artists))
          (reset! artists event-artists-rating))))

    (testing "combining artists ratings with events"
      (let [pipeline-spec {:name  :city-events-artists
                           :tasks [{:name     :best-events
                                    :actions  [{:type      :slicer
                                                :name      :ratings-with-event
                                                :selectors {'ratings [:config :artists]
                                                            'events  [:config :events]}
                                                :params    {:sequence 'ratings
                                                            :group-by [:event-id]
                                                            :join     {:sequence 'events
                                                                       :cat?     true
                                                                       :on       {:source [[:op :first] :event-id]
                                                                                  :target [:id]}}}}
                                               {:type      :custom
                                                :name      :rated-events
                                                :selectors {'ratings-event [:state :ratings-with-event :current]}
                                                :params    ['ratings-event]
                                                :fn        (fn [[ratings event]]
                                                             (let [ratings' (->> ratings
                                                                                 (map :artist-rate)
                                                                                 (filter (comp not nil?)))
                                                                   mean     (double (/ (apply + ratings') (count ratings)))]
                                                               {:id     (:id event)
                                                                :name   (:name event)
                                                                :rating mean}))}]
                                    :iterator {:data [:state :rated-events]
                                               :next [:not-nil? [:state :ratings-with-event :next]]}}]}
            pipeline      (collet/compile-pipeline pipeline-spec)
            {:keys [best-events]} (pipeline {:events  @events
                                             :artists @artists})
            events        (flatten @events)]
        (let [events-no-artists (->> events
                                     (filter (fn [{:keys [relations]}]
                                               (some #(contains? % :artist) relations)))
                                     (count))]
          (is (every? #(contains? % :rating) best-events))
          (is (= (count best-events) events-no-artists)))))))





(def events (atom []))
(def artists (atom []))
(def rated-events (atom []))

(deftest pipeline-complex-http-flow-test
  (testing "complex pipeline to extract artists"
    (let [pipeline-spec {:name  :city-best-events
                         :tasks [{:name     :area-events
                                  :setup    [{:type      :clj/format
                                              :name      :area-query
                                              :selectors {'city [:config :city]}
                                              :params    ["area:%s AND type:City" 'city]}
                                             {:type      :http
                                              :name      :area-request
                                              :selectors {'area-query [:state :area-query]}
                                              :params    {:url          "https://musicbrainz.org/ws/2/area"
                                                          :accept       :json
                                                          :as           :json
                                                          :query-params {:limit 1
                                                                         :query 'area-query}}
                                              :return    [:body :areas [:cat :id]]}
                                             {:type      :clj/ffirst
                                              :name      :area-id
                                              :selectors '{area-ids [:state :area-request]}
                                              :params    '[area-ids]}
                                             {:type      :clj/format
                                              :name      :events-query
                                              :selectors '{area-id [:state :area-id]}
                                              :params    ["aid:%s AND type:Concert" 'area-id]}]
                                  :actions  [{:type :counter
                                              :name :req-count}
                                             {:type      :clj/*
                                              :name      :offset
                                              :selectors {'req-count [:state :req-count]}
                                              :params    ['req-count 10]}
                                             {:type      :http
                                              :name      :events-request
                                              :selectors {'events-query [:state :events-query]
                                                          'offset       [:state :offset]}
                                              :params    {:url          "https://musicbrainz.org/ws/2/event"
                                                          :accept       :json
                                                          :as           :json
                                                          :rate         1
                                                          :query-params {:limit  10
                                                                         :offset 'offset
                                                                         :query  'events-query}}
                                              :return    [:body :events]}
                                             {:type      :custom
                                              :name      :test-collect-events
                                              :selectors {'events [:state :events-request]}
                                              :params    ['events]
                                              :fn        (fn [es] (swap! events concat es))}]
                                  :iterator {:data [:state :events-request]
                                             :next [:< [:state :req-count] 3]}}

                                 {:name     :event-artists-rating
                                  :inputs   [:area-events]
                                  :actions  [{:type      :slicer
                                              :name      :event-artists
                                              :selectors {'events [:inputs :area-events]}
                                              :params    {:sequence   'events
                                                          :cat?       true
                                                          :flatten-by {:artist-id [:relations [:cat [:cond [:not-nil? :artist]] :artist :id]]}
                                                          :keep-keys  {:event-id [:id]}}}
                                             {:type      :http
                                              :name      :artist-details
                                              :selectors {'artist [:state :event-artists :current :artist-id]}
                                              :params    {:url          ["https://musicbrainz.org/ws/2/artist/%s" 'artist]
                                                          :accept       :json
                                                          :as           :json
                                                          :rate         1
                                                          :query-params {:inc "ratings"}}
                                              :return    [:body]}
                                             {:type      :custom
                                              :name      :test-collect-artists
                                              :selectors {'artist-details [:state :artist-details]}
                                              :params    ['artist-details]
                                              :fn        (fn [artist]
                                                           (swap! artists conj artist))}]
                                  :iterator {:data [{:artist-rate [:state :artist-details :rating :value]
                                                     :event-id    [:state :event-artists :current :event-id]}]
                                             :next [:not-nil? [:state :event-artists :next]]}}

                                 {:name     :best-events
                                  :inputs   [:event-artists-rating :area-events]
                                  :actions  [{:type      :slicer
                                              :name      :ratings-with-event
                                              :selectors {'ratings [:inputs :event-artists-rating]
                                                          'events  [:inputs :area-events]}
                                              :params    {:sequence 'ratings
                                                          :group-by [:event-id]
                                                          :join     {:sequence 'events
                                                                     :cat?     true
                                                                     :on       {:source [[:op :first] :event-id]
                                                                                :target [:id]}}}}
                                             {:type      :custom
                                              :name      :rated-events
                                              :selectors {'ratings-event [:state :ratings-with-event :current]}
                                              :params    ['ratings-event]
                                              :fn        (fn [[ratings event]]
                                                           (let [ratings' (->> ratings
                                                                               (map :artist-rate)
                                                                               (filter (comp not nil?)))
                                                                 mean     (double (/ (apply + ratings') (count ratings)))]
                                                             {:id     (:id event)
                                                              :name   (:name event)
                                                              :rating mean}))}
                                             {:type      :custom
                                              :name      :test-collect-rated-events
                                              :selectors {'rated-events [:state :rated-events]}
                                              :params    ['rated-events]
                                              :fn        (fn [event]
                                                           (swap! rated-events conj event))}]
                                  :iterator {:data [:state :rated-events]
                                             :next [:not-nil? [:state :ratings-with-event :next]]}}]}
          pipeline      (collet/compile-pipeline pipeline-spec)]
      (pipeline {:city "London"})

      (is (= (count @events) 40))
      (is (every? #(contains? % :rating) @rated-events))

      (let [events-no-artists (->> @events
                                   (filter (fn [{:keys [relations]}]
                                             (some #(contains? % :artist) relations)))
                                   (count))
            number-of-artists (->> (mapcat :relations @events)
                                   (filter (comp identity :artist))
                                   (count))]
        (is (= (count @rated-events) events-no-artists))
        (is (= (count @artists) number-of-artists))))))

(ns collet.actions.http-test
  (:require
   [clojure.test :refer :all]
   [collet.core :as collet]
   [collet.actions.http :as sut]
   [collet.test-fixtures :as tf]
   [tech.v3.dataset :as ds]))


(use-fixtures :once (tf/instrument! 'collet.actions.http))


;; API docs https://musicbrainz.org/doc/MusicBrainz_API


(deftest http-request-test
  (testing "simple http request"
    (let [actual (sut/make-request {:url "https://musicbrainz.org/ws/2/genre/all?limit=10"})]
      (is (map? actual))
      (is (every? #(some? (val %)) (select-keys actual [:body :headers :status])))
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
                           :tasks [{:name         :area-events
                                    :keep-state   true
                                    :state-format :flatten
                                    :setup        [{:type      :clj/format
                                                    :name      :area-query
                                                    :selectors {'city [:config :city]}
                                                    :params    ["area:%s AND type:City" 'city]}
                                                   {:type      :collet.actions.http/request
                                                    :name      :area-request
                                                    :selectors {'area-query [:state :area-query]}
                                                    :params    {:url          "https://musicbrainz.org/ws/2/area"
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
                                                    :params    {:url          "https://musicbrainz.org/ws/2/event"
                                                                :accept       :json
                                                                :as           :json
                                                                :rate         1
                                                                :query-params {:limit  10
                                                                               :offset 0
                                                                               :query  'events-query}}
                                                    :return    [:body :events]}]

                                    :iterator     {:next false}
                                    :return       [:state :events-request]}]}
            pipeline      (collet/compile-pipeline pipeline-spec)
            _             @(pipeline {:city "London"})
            {:keys [area-events]} pipeline]
        (is (= (count area-events) 10))
        (reset! events area-events)))

    (testing "extracting all artists from events"
      (let [pipeline-spec           {:name  :city-events-artists
                                     :tasks [{:name       :event-artists-rating
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
                                                            :params    {:url          ["https://musicbrainz.org/ws/2/artist/%s" 'artist-id]
                                                                        :accept       :json
                                                                        :as           :json
                                                                        :rate         1
                                                                        :query-params {:inc "ratings"}}
                                                            :return    [:body]}]
                                              :iterator   {:next [:true? [:$mapper/has-next-item]]}
                                              :return     [{:artist-rate [:state :artist-details :rating :value]
                                                            :event-id    [:$mapper/item :id]}]}]}
            pipeline                (collet/compile-pipeline pipeline-spec)
            _                       @(pipeline {:events @events})
            {:keys [event-artists-rating]} pipeline
            event-artists-rating-ds (first (collet/arrow->dataset event-artists-rating))
            events-with-artists-num (->> (mapcat :relations @events)
                                         (filter (comp identity :artist))
                                         (count))
            events-no-artists-num   (->> @events
                                         (filter (fn [{:keys [relations]}]
                                                   (not (some (comp identity :artist) relations))))
                                         (count))]
        (is (= (+ events-with-artists-num events-no-artists-num)
               (ds/row-count event-artists-rating-ds)))
        (reset! artists (ds/rows event-artists-rating-ds))))

    (testing "combining artists ratings with events"
      (let [pipeline-spec  {:name  :city-events-artists
                            :tasks [{:name       :best-events
                                     :keep-state true
                                     :setup      [{:type      :slicer
                                                   :name      :events-ratings
                                                   :selectors {'artists [:config :artists]}
                                                   :params    {:sequence 'artists
                                                               :apply    [[:fold {:by [:event-id]}]]}}]
                                     :actions    [{:type      :mapper
                                                   :name      :event-rating
                                                   :selectors {'ratings [:state :events-ratings]}
                                                   :params    {:sequence 'ratings}}
                                                  {:type      :custom
                                                   :name      :calculated-rating
                                                   :selectors {'event-ratings [:$mapper/item]}
                                                   :params    ['event-ratings]
                                                   :fn        (fn [{:keys [event-id artist-rate]}]
                                                                (let [rating (if (seq artist-rate)
                                                                               (double (/ (apply + artist-rate)
                                                                                          (count artist-rate)))
                                                                               0)]
                                                                  {:event-id event-id
                                                                   :rating   rating}))}]
                                     :iterator   {:next [:true? [:$mapper/has-next-item]]}
                                     :return     [:state :calculated-rating]}]}
            pipeline       (collet/compile-pipeline pipeline-spec)
            _              @(pipeline {:artists @artists})
            {:keys [best-events]} pipeline
            best-events-ds (first (collet/arrow->dataset best-events))]
        (is (every? #(contains? % :rating) (ds/rows best-events-ds))
            "All events should have a rating")

        (is (= (count @events)
               (ds/row-count best-events-ds))
            "Number of events should be the same as number of rated events")))))


(deftest pipeline-complex-http-flow-test
  (testing "complex pipeline to extract artists"
    (let [pipeline-spec {:name      :city-best-events
                         :use-arrow false
                         :tasks     [{:name         :area-events
                                      :state-format :flatten
                                      :setup        [{:type      :clj/format
                                                      :name      :area-query
                                                      :selectors {'city [:config :city]}
                                                      :params    ["area:%s AND type:City" 'city]}
                                                     {:type      :collet.actions.http/request
                                                      :name      :area-request
                                                      :selectors {'area-query [:state :area-query]}
                                                      :params    {:url          "https://musicbrainz.org/ws/2/area"
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
                                                      :params    {:url          "https://musicbrainz.org/ws/2/event"
                                                                  :accept       :json
                                                                  :as           :json
                                                                  :rate         1
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
                                                  :params    {:url          ["https://musicbrainz.org/ws/2/artist/%s" 'artist-id]
                                                              :accept       :json
                                                              :as           :json
                                                              :rate         0.5
                                                              :query-params {:inc "ratings"}}
                                                  :return    [:body]
                                                  :fold-in   [:artist]}]
                                      :iterator {:next [:true? [:$enrich/has-next-item]]}}

                                     {:name       :rated-events
                                      :keep-state true
                                      :inputs     [:events-with-artists]
                                      :setup      [{:type      :slicer
                                                    :name      :events-with-ratings
                                                    :selectors {'events [:inputs :events-with-artists]}
                                                    :params    {:sequence 'events
                                                                :apply    [[:fold {:by            :id
                                                                                   :rollup        [:artist]
                                                                                   :rollup-except true}]]}}]
                                      :actions    [{:type      :mapper
                                                    :name      :event-rating
                                                    :selectors {'ratings [:state :events-with-ratings]}
                                                    :params    {:sequence 'ratings}}
                                                   {:type      :custom
                                                    :name      :event-with-rating
                                                    :selectors {'event-ratings [:$mapper/item]}
                                                    :params    ['event-ratings]
                                                    :fn        (fn [{:keys [artist] :as event}]
                                                                 (let [ratings (->> artist
                                                                                    (map (comp :value :rating))
                                                                                    (filter number?))
                                                                       rating  (if (seq ratings)
                                                                                 (double (/ (apply + ratings)
                                                                                            (count ratings)))
                                                                                 0)]
                                                                   (assoc event :rating rating)))}]
                                      :iterator   {:next [:true? [:$mapper/has-next-item]]}}]}
          pipeline      (collet/compile-pipeline pipeline-spec)]
      @(pipeline {:city "London"})

      (let [{:keys [area-events events-with-artists rated-events]} pipeline
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

        (is (= (->> area-events (map :id) (distinct) (count)) ;; sometimes API returns same events in different pages
               (count rated-events))
            "should match the number of events fetched in the first step")

        (is (every? #(contains? % :rating) rated-events)
            "all events should have a rating")

        (is (every? #(and (number? (:rating %))
                          (<= 0 (:rating %) 5))
                    rated-events)
            "all ratings should be numbers")))))

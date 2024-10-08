(ns collet.actions.enrich-test
  (:require
   [clojure.test :refer :all]
   [collet.core :as collet]
   [collet.actions.enrich :as sut]))


(deftest enrich-action-test
  (testing "enrich function returns a set of actions"
    (let [actual (sut/enrich {:type           :enrich
                              :name           :enrich-artist-details
                              :target         [:inputs :area-events]
                              :flatten-target true
                              :iterate-on     [:relations [:cat [:cond [:not-nil? :artist]] :artist :id]]
                              :method         :replace
                              :action         :http
                              :params         {:url          ["https://musicbrainz.org/ws/2/artist/%s" '$target-item]
                                               :accept       :json
                                               :as           :json
                                               :rate         0.5
                                               :query-params {:inc "ratings"}}
                              :return         [:body]})]
      (is (= 3 (count actual)))
      (is (= (map :type actual) [:slicer :http :fold]))))

  (testing "enrich action in the context of task execution"
    (let [task-spec {:name     :enrich-test
                     :actions  [{:type      :enrich
                                 :name      :enrich-collection
                                 :target    [:config :collection]
                                 :method    :merge
                                 :action    :custom
                                 :selectors {'data [:config :data]}
                                 :params    ['data '$target-item]
                                 :fn        (fn [data item]
                                              (get data (:id item)))}]
                     :iterator {:data [:state :enrich-collection]
                                :next [:not-empty? [:state :enrich-collection-slicer :next]]}}
          task-fn   (collet/compile-task task-spec)
          result    (-> (task-fn {:config {:collection [{:id 1} {:id 2} {:id 3}]
                                           :data       {1 {:name "one"}
                                                        2 {:name "two"}
                                                        3 {:name "three"}}}
                                  :state  {}})
                        (vec))]
      (is (= 3 (count result)))
      (is (= [{:id 1, :name "one"} {:id 2, :name "two"} {:id 3, :name "three"}]
             (last result)))))

  (testing "enrich action in the context of pipeline execution"
    (let [pipeline-spec {:name  :city-events-with-artists
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
                                             :next [:< [:state :req-count] 1]}}

                                 {:name         :events-with-artists
                                  :inputs       [:area-events]
                                  :keep-state?  true
                                  :keep-latest? true
                                  :setup        [{:type      :clj/flatten
                                                  :name      :city-events-list
                                                  :selectors {'events [:inputs :area-events]}
                                                  :params    ['events]}]
                                  :actions      [{:type       :enrich
                                                  :name       :enrich-artist-details
                                                  :target     [:state :city-events-list]
                                                  :iterate-on [:relations [:cat [:cond [:not-nil? :artist]] :artist :id]]
                                                  :method     :merge
                                                  :action     :http
                                                  :params     {:url          ["https://musicbrainz.org/ws/2/artist/%s" '$target-item]
                                                               :accept       :json
                                                               :as           :json
                                                               :rate         0.5
                                                               :query-params {:inc "ratings"}}
                                                  :return     [:body]}]
                                  :iterator     {:data [:state :enrich-artist-details]
                                                 :next [:not-nil? [:state :enrich-artist-details-slicer :next]]}}]}
          pipeline      (collet/compile-pipeline pipeline-spec)]
      @(pipeline {:city "London"})

      (let [events  (last (get pipeline :events-with-artists))
            artists (mapcat (comp (fn [r] (filter #(contains? % :artist) r)) :relations)
                            events)]
        (is (= 20 (count events)))
        (is (every? (comp :rating :artist) artists)
            "every artist has a rating attached")))))

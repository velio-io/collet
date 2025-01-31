(ns collet.actions.enrich-test
  (:require
   [clojure.test :refer :all]
   [collet.core :as collet]
   [collet.actions.enrich :as sut]
   [collet.test-fixtures :as tf]
   [collet.utils :as utils]))


(use-fixtures :once (tf/instrument! 'collet.actions.enrich))


(def test-events-data
  [[{:id "9a624d46-8cca-43bf-a58a-f8f21a2f98d8", :type "Concert", :score 100, :name "Alice Cooper at Grand Casino Hinckley Events & Convention Center", :life-span {:begin "2017-06-08", :end "2017-06-08"}, :time "20:00:00", :relations [{:type "support act", :type-id "492a850e-97eb-306a-a85e-4b6d98527796", :direction "backward", :artist {:id "bd6b6464-0cf5-48d9-8b7f-52d88d4e87f0", :name "Chris Hawkey", :sort-name "Chris Hawkey"}} {:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "ee58c59f-8e7f-4430-b8ca-236c4d3745ae", :name "Alice Cooper", :sort-name "Cooper, Alice", :disambiguation "the musician born Vincent Damon Furnier, changed his name legally to Alice Cooper in 1974"}} {:type "held at", :type-id "e2c6f697-07dc-38b1-be0b-83d740165532", :direction "backward", :place {:id "8cfeea3a-132e-47f0-aaf5-d7f908f02f86", :name "Grand Casino Hinckley Amphitheater"}}]}
    {:id "a824bfa5-ed32-4b2a-82c4-8c9045d311b1", :type "Concert", :score 100, :name "Lost In The Manor - Old Queen's Head, Angel, London", :life-span {:begin "2016-09-18", :end "2016-09-18"}, :time "18:30:00", :relations [{:type "held in", :type-id "542f8484-8bc7-3ce5-a022-747850b2b928", :direction "backward", :area {:id "f03d09b3-39dc-4083-afd6-159e3f0d462f", :name "London"}}]}
    {:id "5e98db6f-6132-4190-881c-acdf2ea94b59", :type "Concert", :score 100, :name "Queensrÿche at 日本青年館", :life-span {:begin "1984-08-03", :end "1984-08-03"}, :relations [{:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "deeea939-7f89-4762-b09f-79269cd70d3b", :name "Queensrÿche", :sort-name "Queensrÿche"}} {:type "held at", :type-id "e2c6f697-07dc-38b1-be0b-83d740165532", :direction "backward", :place {:id "4f21f35e-6d44-493a-93a2-94f1ac712c14", :name "日本青年館"}}]}
    {:id "a91fac23-3d87-4bdb-a89e-abcccea8c200", :type "Concert", :score 100, :name "1971‐11‐12: Student Prince, Asbury Park, NJ, USA", :disambiguation "The Bruce Springsteen Band", :life-span {:begin "1971-11-12", :end "1971-11-13"}, :time "21:30:00", :relations [{:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "70248960-cb53-4ea4-943a-edb18f7d336f", :name "Bruce Springsteen", :sort-name "Springsteen, Bruce"}} {:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "1607e961-c4a7-4602-ac22-d0d87833eee3", :name "The Bruce Springsteen Band", :sort-name "Bruce Springsteen Band, The"}} {:type "held at", :type-id "e2c6f697-07dc-38b1-be0b-83d740165532", :direction "backward", :place {:id "7c0e5bb2-9a14-4732-a87e-796c1acba1c6", :name "Student Prince"}}]}
    {:id "be5782a4-1bfe-4d03-8fd1-39e54b77bb80", :type "Concert", :score 100, :name "1971‐11‐21: Student Prince, Asbury Park, NJ, USA", :disambiguation "The Bruce Springsteen Band", :life-span {:begin "1971-11-21", :end "1971-11-22"}, :time "21:30:00", :relations [{:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "70248960-cb53-4ea4-943a-edb18f7d336f", :name "Bruce Springsteen", :sort-name "Springsteen, Bruce"}} {:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "1607e961-c4a7-4602-ac22-d0d87833eee3", :name "The Bruce Springsteen Band", :sort-name "Bruce Springsteen Band, The"}} {:type "held at", :type-id "e2c6f697-07dc-38b1-be0b-83d740165532", :direction "backward", :place {:id "7c0e5bb2-9a14-4732-a87e-796c1acba1c6", :name "Student Prince"}}]}]
   [{:id "69f7153a-2fff-425a-9b00-e02f14b54f2b", :type "Concert", :score 100, :name "1971‐11‐23: New Plaza Theater, Linden, NJ, USA", :disambiguation "jam session", :life-span {:begin "1971-11-23", :end "1971-11-23"}, :relations [{:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "23ab1c9b-5295-404d-9143-80e06b93341e", :name "Psychotic Blues Band", :sort-name "Band, Psychotic Blues", :disambiguation "New Jersey"}} {:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "051f7534-cad4-4f7f-a5ef-7403b1fcee02", :name "Tumbleweed", :sort-name "Tumbleweed", :disambiguation "New Jersey"}} {:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "5ba1e8d6-7c62-477e-bf01-10ef3048eb68", :name "Pat Karwin", :sort-name "Karwin, Pat"}} {:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "4cda092b-95fb-45fc-ae29-5830c82bf036", :name "Taylors Mills Road", :sort-name "Road, Taylors Mills"}} {:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "41a8f637-21f3-408c-a1d8-972bea1523e2", :name "The Rich Baron Band", :sort-name "Rich Baron Band, The"}} {:type "guest performer", :type-id "292df906-98a6-307e-86e8-df01a579a321", :direction "backward", :artist {:id "70248960-cb53-4ea4-943a-edb18f7d336f", :name "Bruce Springsteen", :sort-name "Springsteen, Bruce"}} {:type "held at", :type-id "e2c6f697-07dc-38b1-be0b-83d740165532", :direction "backward", :place {:id "cfe59d21-9ede-45ef-b6e4-21b6fa3e433b", :name "New Plaza Theater"}}]}
    {:id "c7c94544-5693-4569-8927-53f03b7c1be3", :type "Concert", :score 100, :name "1971‐11‐28: Student Prince, Asbury Park, NJ, USA", :disambiguation "The Bruce Springsteen Band", :life-span {:begin "1971-11-28", :end "1971-11-29"}, :time "21:30:00", :relations [{:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "70248960-cb53-4ea4-943a-edb18f7d336f", :name "Bruce Springsteen", :sort-name "Springsteen, Bruce"}} {:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "1607e961-c4a7-4602-ac22-d0d87833eee3", :name "The Bruce Springsteen Band", :sort-name "Bruce Springsteen Band, The"}} {:type "held at", :type-id "e2c6f697-07dc-38b1-be0b-83d740165532", :direction "backward", :place {:id "7c0e5bb2-9a14-4732-a87e-796c1acba1c6", :name "Student Prince"}}]}
    {:id "807f3f14-f3b0-41ff-959a-82bfae11b3a9", :type "Concert", :score 100, :name "Mats/Morgan at L'Austrasique", :life-span {:begin "2005-05-11", :end "2005-05-11"}, :relations [{:type "support act", :type-id "492a850e-97eb-306a-a85e-4b6d98527796", :direction "backward", :artist {:id "bc4e41d2-da66-415b-a57f-8f76dbed1729", :name "Jacques Tellitocci", :sort-name "Tellitocci, Jacques"}} {:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "90b0a86c-b692-478b-8eff-457121c0b870", :name "Mats/Morgan Band", :sort-name "Mats/Morgan Band"}} {:type "held at", :type-id "e2c6f697-07dc-38b1-be0b-83d740165532", :direction "backward", :place {:id "5dc331d4-efdb-4968-b18b-4b824a56bb2c", :name "L'Austrasique"}}]}
    {:id "10fbfb6e-a6e0-4390-9d70-35e3e14c2020", :type "Concert", :score 100, :name "1972‐02‐05: The Back Door, Richmond, VA, USA", :disambiguation "The Bruce Springsteen Band", :life-span {:begin "1972-02-05", :end "1972-02-05"}, :relations [{:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "70248960-cb53-4ea4-943a-edb18f7d336f", :name "Bruce Springsteen", :sort-name "Springsteen, Bruce"}} {:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "1607e961-c4a7-4602-ac22-d0d87833eee3", :name "The Bruce Springsteen Band", :sort-name "Bruce Springsteen Band, The"}} {:type "held at", :type-id "e2c6f697-07dc-38b1-be0b-83d740165532", :direction "backward", :place {:id "6232a780-e557-4aa3-b5a2-c76bf572433f", :name "The Back Door"}}]}
    {:id "b1f81805-88ef-48e1-9f9d-19684e702b4a", :type "Concert", :score 100, :name "A Head Full of Dreams Tour: La Plata", :disambiguation "night 1", :life-span {:begin "2016-03-31", :end "2016-03-31"}, :relations [{:type "support act", :type-id "492a850e-97eb-306a-a85e-4b6d98527796", :direction "backward", :artist {:id "680466d6-f05b-44f6-858d-625d1b5087f6", :name "Lianne La Havas", :sort-name "La Havas, Lianne"}} {:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "cc197bad-dc9c-440d-a5b5-d52ba2e14234", :name "Coldplay", :sort-name "Coldplay"}} {:type "held at", :type-id "e2c6f697-07dc-38b1-be0b-83d740165532", :direction "backward", :place {:id "6cec7a8f-12ca-4754-b861-fd6293110506", :name "Estadio Ciudad de La Plata"}}]}]])


(deftest enrich-action-test
  (testing "enrich function returns a set of actions"
    (let [actual (sut/enrich {:name      :enrich-artist-details
                              :target    [:inputs :area-events]
                              :action    :collet.actions.http/request
                              :selectors {'artist-id [:path :to :artist :id]}
                              :params    {:url          ["https://musicbrainz.org/ws/2/artist/%s" 'artist-id]
                                          :accept       :json
                                          :as           :json
                                          :rate         0.5
                                          :query-params {:inc "ratings"}}
                              :return    [:body]
                              :fold-in   [:artist]})]
      (is (= 3 (count actual)))
      (is (= (map :type actual) [:mapper :collet.actions.http/request :fold]))))

  (testing "enrich action in the context of task execution"
    (let [task-spec {:name     :enrich-test
                     :actions  [{:type      :enrich
                                 :name      :enrich-collection
                                 :target    [:config :collection]
                                 :action    :custom
                                 :selectors {'id   [:$enrich/item :id]
                                             'data [:config :data]}
                                 :params    ['data 'id]
                                 :fn        (fn [data id]
                                              (get data id))}]
                     :iterator {:next [:true? [:$enrich/has-next-item]]}}
          {:keys [task-fn]} (collet/compile-task (utils/eval-ctx) task-spec)
          result    (-> (task-fn {:config {:collection [{:id 1} {:id 2} {:id 3}]
                                           :data       {1 {:name "one"}
                                                        2 {:name "two"}
                                                        3 {:name "three"}}}
                                  :state  {}})
                        (vec))]
      (is (= 3 (count result)))
      (is (= [{:id 1, :name "one"} {:id 2, :name "two"} {:id 3, :name "three"}]
             (last result))))))


(deftest enrich-data-pipeline-test
  (testing "enrich action in the context of pipeline execution"
    (let [pipeline-spec {:name  :city-events-with-artists
                         :deps  {:coordinates '[[io.velio/collet-actions "0.2.3"]]}
                         :tasks [{:name       :events-with-artists
                                  :keep-state true
                                  :setup      [{:type      :slicer
                                                :name      :city-events-list
                                                :selectors {'events [:config :area-events]}
                                                :params    {:sequence 'events
                                                            :cat?     true
                                                            :apply    [[:flatten {:by {:artist [:relations [:$/cat [:$/cond [:not-nil? :artist]] :artist]]}}]]}}]
                                  :actions    [{:type      :enrich
                                                :name      :enrich-artist-details
                                                :target    [:state :city-events-list]
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
                                  :iterator   {:next [:true? [:$enrich/has-next-item]]}}]}
          pipeline      (collet/compile-pipeline pipeline-spec)]
      @(pipeline {:area-events test-events-data})

      (let [events  (:events-with-artists pipeline)
            artists (filter #(some? (get-in % [:artist :rating])) events)]
        (is (= 22 (count events)))
        (is (= 21 (count artists))
            "one event doesn't have artist relations")))))

(ns collet.actions.slicer-test
  (:require
   [clojure.test :refer :all]
   [tech.v3.dataset :as ds]
   [collet.test-fixtures :as tf]
   [collet.core :as collet]
   [collet.actions.slicer :as sut]))


(use-fixtures :once (tf/instrument! 'collet.actions.slicer))


(deftest prep-dataset-test
  (testing "flattening a sequence over the nested collection (by :street value)"
    (let [result (sut/prep-dataset
                  {:sequence [{:id 1 :name "John" :addresses [{:street "Main St." :city "Springfield"}
                                                              {:street "NorthG St." :city "Springfield"}]}
                              {:id 2 :name "Jane" :addresses [{:street "Elm St." :city "Springfield"}]}
                              {:id 3 :name "James" :addresses []}]
                   :apply    [[:flatten {:by {:address [:addresses [:$/cat :street]]}}]]})]
      (is (= 4 (ds/row-count result)))
      (is (= 4 (ds/column-count result)))
      (is (= [:id :name :addresses :address]
             (ds/column-names result)))
      (is (= ["Main St." "NorthG St." "Elm St." nil]
             (-> result (ds/column :address) vec)))))

  (testing "folding a sequence by :street"
    (let [result (sut/prep-dataset
                  {:sequence [{:id 1 :name "John" :street "Main St."}
                              {:id 2 :name "Jane" :street "NorthG St."}
                              {:id 3 :name "James" :street "Elm St."}
                              {:id 4 :name "Jacob" :street "Elm St."}
                              {:id 5 :name "Jason" :street "Main St."}]
                   :apply    [[:fold {:by     [:street]
                                      :rollup true}]]})]
      (is (= 3 (ds/row-count result)))
      (is (= [{:street "Main St." :id [1 5] :name ["John" "Jason"]}
              {:street "NorthG St." :id 2 :name "Jane"}
              {:street "Elm St." :id [3 4] :name ["James" "Jacob"]}]
             (ds/rows result)))))

  (testing "grouping a sequence by :city"
    (let [result (sut/prep-dataset
                  {:sequence [{:id 1 :name "John" :city "Springfield"}
                              {:id 3 :name "Jack" :city "Springfield"}
                              {:id 2 :name "Jane" :city "Lakeside"}
                              {:id 4 :name "Jill" :city "Lakeside"}
                              {:id 5 :name "Joe" :city "Lakeside"}
                              {:id 3 :name "Jack" :city "Springfield"}
                              {:id 5 :name "Joe" :city "Lakeside"}]
                   :apply    [[:group {:by          :city
                                       :join-groups false}]]})]
      (is (= ["Springfield" "Lakeside"] (keys result)))
      (is (every? ds/dataset? (vals result)))
      (is (= 3 (ds/row-count (get result "Springfield"))))
      (is (= 4 (ds/row-count (get result "Lakeside")))))

    (let [result (sut/prep-dataset
                  {:sequence [{:id 1 :name "John" :city "Springfield"}
                              {:id 3 :name "Jack" :city "Springfield"}
                              {:id 2 :name "Jane" :city "Lakeside"}
                              {:id 4 :name "Jill" :city "Lakeside"}
                              {:id 5 :name "Joe" :city "Lakeside"}
                              {:id 3 :name "Jack" :city "Springfield"}
                              {:id 5 :name "Joe" :city "Lakeside"}]
                   :apply    [[:group {:by :city}]]})]
      (ds/dataset? result)
      (is (= 7 (ds/row-count result)))
      (is (= [:id :name :city :_group_by_key] (ds/column-names result)))))

  (testing "joining a sequence with another sequence"
    (let [result (sut/prep-dataset
                  {:sequence [{:id 1 :name "John"}
                              {:id 2 :name "Jane"}
                              {:id 3 :name "Jack"}]
                   :apply    [[:join {:with   [{:user {:id 1} :city "Springfield"}
                                               {:user {:id 2} :city "Lakeside"}
                                               {:user {:id 3} :city "Springfield"}]
                                      :source :id
                                      :target [:user :id]}]]})]
      (is (= [:id :name :user :city]
             (ds/column-names result)))
      (is (= 3 (ds/row-count result))))

    (let [result (sut/prep-dataset
                  {:sequence [{:id 1 :name "John"}
                              {:id 2 :name "Jane"}]
                   :apply    [[:join {:with   [{:user {:id 1} :city "Springfield"}
                                               {:user {:id 2} :city "Lakeside"}
                                               {:user {:id 3} :city "Springfield"}]
                                      :source :id
                                      :target [:user :id]}]]})]
      (is (= [:id :name :user :city]
             (ds/column-names result)))
      (is (= 2 (ds/row-count result))
          "joining works as a left join")))

  (testing "multiple transformations"
    (let [result    (sut/prep-dataset
                     {:sequence [{:id 1 :name "John" :addresses [{:street "Main St." :city "Springfield"}
                                                                 {:street "NorthG St." :city "Springfield"}]}
                                 {:id 2 :name "Jane" :addresses [{:street "Elm St." :city "Springfield"}]}
                                 {:id 3 :name "Joshua" :addresses [{:street "NorthG St." :city "Springfield"}]}]
                      :apply    [[:flatten {:by {:address [:addresses [:$/cat :street]]}}]
                                 [:join {:with   [{:user {:id 1} :phone 1234567}
                                                  {:user {:id 2} :phone 7654321}
                                                  {:user {:id 3} :phone 4561237}]
                                         :source :id
                                         :target [:user :id]}]
                                 [:group {:by          :address
                                          :join-groups false}]]})
          main-st   (get result "Main St.")
          northg-st (get result "NorthG St.")]
      (is (= ["Main St." "NorthG St." "Elm St."]
             (keys result)))
      (is (every? ds/dataset? (vals result)))
      (is (= [:id :name :addresses :address :user :phone]
             (ds/column-names main-st)))
      (is (= 1 (ds/row-count main-st)))
      (is (= 2 (ds/row-count northg-st))))))


(def test-events-data
  [{:id "9a624d46-8cca-43bf-a58a-f8f21a2f98d8", :type "Concert", :score 100, :name "Alice Cooper at Grand Casino Hinckley Events & Convention Center", :life-span {:begin "2017-06-08", :end "2017-06-08"}, :time "20:00:00", :relations [{:type "support act", :type-id "492a850e-97eb-306a-a85e-4b6d98527796", :direction "backward", :artist {:id "bd6b6464-0cf5-48d9-8b7f-52d88d4e87f0", :name "Chris Hawkey", :sort-name "Chris Hawkey"}} {:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "ee58c59f-8e7f-4430-b8ca-236c4d3745ae", :name "Alice Cooper", :sort-name "Cooper, Alice", :disambiguation "the musician born Vincent Damon Furnier, changed his name legally to Alice Cooper in 1974"}} {:type "held at", :type-id "e2c6f697-07dc-38b1-be0b-83d740165532", :direction "backward", :place {:id "8cfeea3a-132e-47f0-aaf5-d7f908f02f86", :name "Grand Casino Hinckley Amphitheater"}}]}
   {:id "a824bfa5-ed32-4b2a-82c4-8c9045d311b1", :type "Concert", :score 100, :name "Lost In The Manor - Old Queen's Head, Angel, London", :life-span {:begin "2016-09-18", :end "2016-09-18"}, :time "18:30:00", :relations [{:type "held in", :type-id "542f8484-8bc7-3ce5-a022-747850b2b928", :direction "backward", :area {:id "f03d09b3-39dc-4083-afd6-159e3f0d462f", :name "London"}}]}
   {:id "5e98db6f-6132-4190-881c-acdf2ea94b59", :type "Concert", :score 100, :name "Queensrÿche at 日本青年館", :life-span {:begin "1984-08-03", :end "1984-08-03"}, :relations [{:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "deeea939-7f89-4762-b09f-79269cd70d3b", :name "Queensrÿche", :sort-name "Queensrÿche"}} {:type "held at", :type-id "e2c6f697-07dc-38b1-be0b-83d740165532", :direction "backward", :place {:id "4f21f35e-6d44-493a-93a2-94f1ac712c14", :name "日本青年館"}}]}
   {:id "a91fac23-3d87-4bdb-a89e-abcccea8c200", :type "Concert", :score 100, :name "1971‐11‐12: Student Prince, Asbury Park, NJ, USA", :disambiguation "The Bruce Springsteen Band", :life-span {:begin "1971-11-12", :end "1971-11-13"}, :time "21:30:00", :relations [{:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "70248960-cb53-4ea4-943a-edb18f7d336f", :name "Bruce Springsteen", :sort-name "Springsteen, Bruce"}} {:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "1607e961-c4a7-4602-ac22-d0d87833eee3", :name "The Bruce Springsteen Band", :sort-name "Bruce Springsteen Band, The"}} {:type "held at", :type-id "e2c6f697-07dc-38b1-be0b-83d740165532", :direction "backward", :place {:id "7c0e5bb2-9a14-4732-a87e-796c1acba1c6", :name "Student Prince"}}]}
   {:id "be5782a4-1bfe-4d03-8fd1-39e54b77bb80", :type "Concert", :score 100, :name "1971‐11‐21: Student Prince, Asbury Park, NJ, USA", :disambiguation "The Bruce Springsteen Band", :life-span {:begin "1971-11-21", :end "1971-11-22"}, :time "21:30:00", :relations [{:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "70248960-cb53-4ea4-943a-edb18f7d336f", :name "Bruce Springsteen", :sort-name "Springsteen, Bruce"}} {:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "1607e961-c4a7-4602-ac22-d0d87833eee3", :name "The Bruce Springsteen Band", :sort-name "Bruce Springsteen Band, The"}} {:type "held at", :type-id "e2c6f697-07dc-38b1-be0b-83d740165532", :direction "backward", :place {:id "7c0e5bb2-9a14-4732-a87e-796c1acba1c6", :name "Student Prince"}}]}
   {:id "69f7153a-2fff-425a-9b00-e02f14b54f2b", :type "Concert", :score 100, :name "1971‐11‐23: New Plaza Theater, Linden, NJ, USA", :disambiguation "jam session", :life-span {:begin "1971-11-23", :end "1971-11-23"}, :relations [{:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "23ab1c9b-5295-404d-9143-80e06b93341e", :name "Psychotic Blues Band", :sort-name "Band, Psychotic Blues", :disambiguation "New Jersey"}} {:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "051f7534-cad4-4f7f-a5ef-7403b1fcee02", :name "Tumbleweed", :sort-name "Tumbleweed", :disambiguation "New Jersey"}} {:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "5ba1e8d6-7c62-477e-bf01-10ef3048eb68", :name "Pat Karwin", :sort-name "Karwin, Pat"}} {:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "4cda092b-95fb-45fc-ae29-5830c82bf036", :name "Taylors Mills Road", :sort-name "Road, Taylors Mills"}} {:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "41a8f637-21f3-408c-a1d8-972bea1523e2", :name "The Rich Baron Band", :sort-name "Rich Baron Band, The"}} {:type "guest performer", :type-id "292df906-98a6-307e-86e8-df01a579a321", :direction "backward", :artist {:id "70248960-cb53-4ea4-943a-edb18f7d336f", :name "Bruce Springsteen", :sort-name "Springsteen, Bruce"}} {:type "held at", :type-id "e2c6f697-07dc-38b1-be0b-83d740165532", :direction "backward", :place {:id "cfe59d21-9ede-45ef-b6e4-21b6fa3e433b", :name "New Plaza Theater"}}]}
   {:id "c7c94544-5693-4569-8927-53f03b7c1be3", :type "Concert", :score 100, :name "1971‐11‐28: Student Prince, Asbury Park, NJ, USA", :disambiguation "The Bruce Springsteen Band", :life-span {:begin "1971-11-28", :end "1971-11-29"}, :time "21:30:00", :relations [{:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "70248960-cb53-4ea4-943a-edb18f7d336f", :name "Bruce Springsteen", :sort-name "Springsteen, Bruce"}} {:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "1607e961-c4a7-4602-ac22-d0d87833eee3", :name "The Bruce Springsteen Band", :sort-name "Bruce Springsteen Band, The"}} {:type "held at", :type-id "e2c6f697-07dc-38b1-be0b-83d740165532", :direction "backward", :place {:id "7c0e5bb2-9a14-4732-a87e-796c1acba1c6", :name "Student Prince"}}]}
   {:id "807f3f14-f3b0-41ff-959a-82bfae11b3a9", :type "Concert", :score 100, :name "Mats/Morgan at L'Austrasique", :life-span {:begin "2005-05-11", :end "2005-05-11"}, :relations [{:type "support act", :type-id "492a850e-97eb-306a-a85e-4b6d98527796", :direction "backward", :artist {:id "bc4e41d2-da66-415b-a57f-8f76dbed1729", :name "Jacques Tellitocci", :sort-name "Tellitocci, Jacques"}} {:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "90b0a86c-b692-478b-8eff-457121c0b870", :name "Mats/Morgan Band", :sort-name "Mats/Morgan Band"}} {:type "held at", :type-id "e2c6f697-07dc-38b1-be0b-83d740165532", :direction "backward", :place {:id "5dc331d4-efdb-4968-b18b-4b824a56bb2c", :name "L'Austrasique"}}]}
   {:id "10fbfb6e-a6e0-4390-9d70-35e3e14c2020", :type "Concert", :score 100, :name "1972‐02‐05: The Back Door, Richmond, VA, USA", :disambiguation "The Bruce Springsteen Band", :life-span {:begin "1972-02-05", :end "1972-02-05"}, :relations [{:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "70248960-cb53-4ea4-943a-edb18f7d336f", :name "Bruce Springsteen", :sort-name "Springsteen, Bruce"}} {:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "1607e961-c4a7-4602-ac22-d0d87833eee3", :name "The Bruce Springsteen Band", :sort-name "Bruce Springsteen Band, The"}} {:type "held at", :type-id "e2c6f697-07dc-38b1-be0b-83d740165532", :direction "backward", :place {:id "6232a780-e557-4aa3-b5a2-c76bf572433f", :name "The Back Door"}}]}
   {:id "b1f81805-88ef-48e1-9f9d-19684e702b4a", :type "Concert", :score 100, :name "A Head Full of Dreams Tour: La Plata", :disambiguation "night 1", :life-span {:begin "2016-03-31", :end "2016-03-31"}, :relations [{:type "support act", :type-id "492a850e-97eb-306a-a85e-4b6d98527796", :direction "backward", :artist {:id "680466d6-f05b-44f6-858d-625d1b5087f6", :name "Lianne La Havas", :sort-name "La Havas, Lianne"}} {:type "main performer", :type-id "936c7c95-3156-3889-a062-8a0cd57f8946", :direction "backward", :artist {:id "cc197bad-dc9c-440d-a5b5-d52ba2e14234", :name "Coldplay", :sort-name "Coldplay"}} {:type "held at", :type-id "e2c6f697-07dc-38b1-be0b-83d740165532", :direction "backward", :place {:id "6cec7a8f-12ca-4754-b861-fd6293110506", :name "Estadio Ciudad de La Plata"}}]}])


(deftest slicer-pipeline-test
  (testing "expand events on artist"
    (let [pipeline-spec {:name  :city-events-with-artists
                         :tasks [{:name       :events-with-artists
                                  :keep-state true
                                  :actions    [{:type      :slicer
                                                :name      :city-events-list
                                                :selectors {'events [:config :area-events]}
                                                :params    {:sequence   'events
                                                            :apply      [[:flatten {:by {:artist [:relations
                                                                                                  [:$/cat [:$/cond [:not-nil? :artist]]
                                                                                                   :artist]]}}]]}}]}]}
          pipeline      (collet/compile-pipeline pipeline-spec)]
      @(pipeline {:area-events test-events-data})

      (let [events-with-artists (-> pipeline :events-with-artists last)]
        (is (= (map :id test-events-data)
               (distinct (ds/column events-with-artists :id)))
            "all events are present")

        (is (some? (ds/column events-with-artists :artist))
            "dataset has artist column")

        (is (= 22 (ds/row-count events-with-artists))
            "all artists are present")))))

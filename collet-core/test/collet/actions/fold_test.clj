(ns collet.actions.fold-test
  (:require
   [clojure.test :refer :all]
   [collet.test-fixtures :as tf]
   [collet.actions.fold :as sut]))


(use-fixtures :once (tf/instrument! 'collet.actions.fold))


(deftest append-path-test
  (testing "appending path with prefix"
    (is (= [:prefix :path]
           (sut/append-path :path :prefix)))
    (is (= [:prefix :path1 :path2]
           (sut/append-path [:path1 :path2] :prefix)))))


(deftest apply-to-matching-items-test
  (testing "applying function to matching items"
    (let [data      [{:id 1 :name "Alice"}
                     {:id 2 :name "Bob"}
                     {:id 3 :name "Charlie"}]
          condition [:= :id 2]
          with      {:role "Admin"}
          f         (fn [item with] (merge item with))
          result    (sut/apply-to-matching-items data condition with f)]
      (is (= [{:id 1 :name "Alice"}
              {:id 2 :name "Bob" :role "Admin"}
              {:id 3 :name "Charlie"}]
             result)))))


(deftest conjoin-test
  (is (= (sut/conjoin [1 2 3] nil 4)
         [1 2 3 4]))

  (is (= (sut/conjoin [1 2 3] [1] 4)
         [1 4 3]))

  (is (= (sut/conjoin {:a [1 2 3]} [:a 1] 4)
         {:a [1 4 3]}))

  (is (= (sut/conjoin {:a [{:b [1 2]}
                           {:b [3 4]}]}
                      [:a 1 :b]
                      5)
         {:a [{:b [1 2]} {:b 5}]}))

  (is (= (sut/conjoin [] nil 123)
         [123]))

  (is (= (sut/conjoin [123] nil 321)
         [123 321]))

  (is (= (sut/conjoin {:a {:b 123}} [:a] [321])
         {:a [321]}))

  (is (= (sut/conjoin {:a {:b {:c 123}}} [:a :b] {:d 321})
         {:a {:b {:d 321}}}))

  (is (= (sut/conjoin {:a 123} nil {:b 321})
         {:a 123 :b 321}))

  (is (= (sut/conjoin {:a 123} [:b] {:c 321})
         {:a 123, :b {:c 321}}))

  (is (= (sut/conjoin {:a 123} [:b 10] 321)
         {:a 123, :b '(321)})))


(deftest basic-fold-test
  (testing "adding item into resulting collection"
    (is (= (sut/fold {:item 123} nil)
           [123]))
    (is (= (sut/fold {:item 321} [123])
           [123 321]))
    (is (= (sut/fold {:item 321} [123])
           [123 321])))

  (testing "merging data before adding"
    (is (= (sut/fold {:item {:a 123} :with {:b 321}} nil)
           [{:a 123 :b 321}]))

    (is (= (sut/fold {:item {:a 123} :in [:b] :with {:c 321}} nil)
           [{:a 123, :b {:c 321}}]))

    (is (= (sut/fold {:item {:a 123} :in [:b 10] :with 321} nil)
           [{:a 123, :b '(321)}])))

  (testing "accumulating value"
    (let [first-iteration  (sut/fold {:item {:a 123} :with {:b 321}} nil)
          second-iteration (sut/fold {:item {:a 456} :with {:b 654 :c 987}}
                                     first-iteration)]
      (is (= [{:a 123 :b 321}]
             first-iteration))
      (is (= [{:a 123 :b 321}
              {:a 456 :b 654 :c 987}]
             second-iteration))))

  (testing "condition-based merging"
    ;; Simple equality condition
    (let [data   [{:id 1 :name "Alice"}
                  {:id 2 :name "Bob"}
                  {:id 3 :name "Charlie"}]
          result (sut/fold {:item data
                            :in   [[:= :id 2]]
                            :with {:role "Admin"}}
                           nil)]
      (is (= [[{:id 1 :name "Alice"}
               {:id 2 :name "Bob" :role "Admin"}
               {:id 3 :name "Charlie"}]]
             result)))

    ;; Path condition with nested data
    (let [users  [{:id 1 :profile {:active true}}
                  {:id 2 :profile {:active false}}
                  {:id 3 :profile {:active true}}]
          roles  {:admin-id 3}
          result (sut/fold {:item users
                            :in   [[:= :id :admin-id]]
                            :with roles}
                           nil)]
      (is (= [[{:id 1 :profile {:active true}}
               {:id 2 :profile {:active false}}
               {:id 3 :profile {:active true} :admin-id 3}]]
             result)))

    ;; Contains condition
    (let [users  [{:id 1 :tags ["user", "premium"]}
                  {:id 2 :tags ["user"]}
                  {:id 3 :tags ["admin", "premium"]}]
          result (sut/fold {:item users
                            :in   [[:contains :tags "premium"]]
                            :with {:benefits true}}
                           nil)]
      (is (= [[{:id 1 :tags ["user", "premium"] :benefits true}
               {:id 2 :tags ["user"]}
               {:id 3 :tags ["admin", "premium"] :benefits true}]]
             result)))

    ;; Nested path with condition
    (let [users  [{:id 1, :addresses [{:type "home", :street "123 Main St"}
                                      {:type "work", :street "456 Market St"}]}
                  {:id 2, :addresses [{:type "home", :street "789 Oak Ave"}]}]
          result (sut/fold {:item users
                            :in   [[:= :id 1] :addresses [:= :type "work"] :street]
                            :with "UPDATED WORK ADDRESS"}
                           nil)]
      (is (= [[{:id 1, :addresses [{:type "home", :street "123 Main St"}
                                   {:type "work", :street "UPDATED WORK ADDRESS"}]}
               {:id 2, :addresses [{:type "home", :street "789 Oak Ave"}]}]]
             result)))

    ;; Multiple conditions with sequence of 'with' values
    (let [users       [{:id 1, :roles ["user"]}
                       {:id 2, :roles ["user"]}
                       {:id 3, :roles ["user"]}]
          permissions ["view", "edit"]
          result      (sut/fold {:item users
                                 :in   [[:= :id 2]]
                                 :with {:permissions permissions}}
                                nil)]
      (is (= [[{:id 1, :roles ["user"]}
               {:id 2, :roles ["user"], :permissions ["view", "edit"]}
               {:id 3, :roles ["user"]}]]
             result)))

    ;; Multiple nested conditions in a path
    (let [data   [{:id          1,
                   :departments [{:dept-id 101, :name "HR",
                                  :teams   [{:team-id 1001, :name "Recruiting"}
                                            {:team-id 1002, :name "Training"}]}
                                 {:dept-id 102, :name "Engineering",
                                  :teams   [{:team-id 2001, :name "Frontend"}
                                            {:team-id 2002, :name "Backend"}]}]}
                  {:id          2,
                   :departments [{:dept-id 201, :name "Sales",
                                  :teams   [{:team-id 3001, :name "Direct Sales"}
                                            {:team-id 3002, :name "Channel Sales"}]}]}]
          result (sut/fold {:item data
                            :in   [[:= :id 1] :departments [:= :name "Engineering"] :teams [:= :name "Backend"] :status]
                            :with "Active"}
                           nil)]
      (is (= [[{:id          1,
                :departments [{:dept-id 101, :name "HR",
                               :teams   [{:team-id 1001, :name "Recruiting"}
                                         {:team-id 1002, :name "Training"}]}
                              {:dept-id 102, :name "Engineering",
                               :teams   [{:team-id 2001, :name "Frontend"}
                                         {:team-id 2002, :name "Backend", :status "Active"}]}]}
               {:id          2,
                :departments [{:dept-id 201, :name "Sales",
                               :teams   [{:team-id 3001, :name "Direct Sales"}
                                         {:team-id 3002, :name "Channel Sales"}]}]}]]
             result)))))

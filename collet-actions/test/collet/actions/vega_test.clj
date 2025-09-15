(ns collet.actions.vega-test
  (:require
   [charred.api :as charred]
   [clojure.java.io :as io]
   [next.jdbc :as jdbc]
   [collet.test-fixtures :as tf]
   [collet.actions.jdbc-test :as jdbc-test]
   [collet.core :as collet]
   [clj-test-containers.core :as tc]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [collet.actions.vega :as sut]
   [tech.v3.dataset :as ds]))


(use-fixtures :once (tf/instrument! 'collet.actions.vega))


(def vega-lite-spec
  {:data
   {:values
    [{:a "A", :b 28}
     {:a "B", :b 55}
     {:a "C", :b 43}
     {:a "D", :b 91}]},
   :mark "bar",
   :encoding
   {:x {:field "a", :type "nominal", :axis {:labelAngle 0}},
    :y {:field "b", :type "quantitative"}}})


(def vega-spec
  {:axes
   [{:orient "bottom", :scale "xscale"}
    {:orient "left", :scale "yscale"}],
   :width 400,
   :scales
   [{:name "xscale",
     :type "band",
     :domain {:data "table", :field "category"},
     :range "width",
     :padding 0.05,
     :round true}
    {:name "yscale",
     :domain {:data "table", :field "amount"},
     :nice true,
     :range "height"}],
   :padding 5,
   :marks
   [{:type "rect",
     :from {:data "table"},
     :encode
     {:enter
      {:x {:scale "xscale", :field "category"},
       :width {:scale "xscale", :band 1},
       :y {:scale "yscale", :field "amount"},
       :y2 {:scale "yscale", :value 0}},
      :update {:fill {:value "steelblue"}},
      :hover {:fill {:value "red"}}}}
    {:type "text",
     :encode
     {:enter
      {:align {:value "center"},
       :baseline {:value "bottom"},
       :fill {:value "#333"}},
      :update
      {:x {:scale "xscale", :signal "tooltip.category", :band 0.5},
       :y {:scale "yscale", :signal "tooltip.amount", :offset -2},
       :text {:signal "tooltip.amount"},
       :fillOpacity
       [{:test "isNaN(tooltip.amount)", :value 0} {:value 1}]}}}],
   :$schema "https://vega.github.io/schema/vega/v6.json",
   :signals
   [{:name "tooltip",
     :value {},
     :on
     [{:events "rect:mouseover", :update "datum"}
      {:events "rect:mouseout", :update "{}"}]}],
   :height 200,
   :data
   [{:name "table",
     :values
     [{:category "A", :amount 10}
      {:category "B", :amount 55}
      {:category "C", :amount 43}
      {:category "D", :amount 91}
      {:category "E", :amount 81}
      {:category "F", :amount 53}
      {:category "G", :amount 19}
      {:category "H", :amount 87}]}]})


(def md5
  (let [hasher (java.security.MessageDigest/getInstance "MD5")]
    (fn [string]
      (javax.xml.bind.DatatypeConverter/printHexBinary
       (.digest hasher (.getBytes string "UTF-8"))))))

(defn file->md5 [path] (md5 (slurp path)))
(defn file->edn [path] (charred/read-json (slurp path) :key-fn keyword))
(defn file-exists? [path] (.exists (io/file path)))


(deftest write-vega-into-svg-test
  (testing "render plot only"
    (doseq [:let [svg-file-path "./tmp/vega-plain.svg"]
            {:keys [name input expected-hash]}
            [{:name "vega-lite"
              :input {:vega-lite-spec vega-lite-spec
                      :svg-file-path svg-file-path}
              :expected-hash "BC7311FD90D4ECD20A4155C9CCB3D0B6"}

             {:name "vega"
              :input {:vega-spec vega-spec
                      :svg-file-path svg-file-path}
              :expected-hash "CE332F7022C5FDA8504DF9ABC0E85CCF"}

             {:name "vega-lite: render from file"
              :input {:vega-lite-spec (-> vega-lite-spec
                                          (dissoc :data)
                                          (assoc-in [:data :url] "./resources/vega-test.json"))
                      :svg-file-path svg-file-path}
              :expected-hash "714ED41EA2AF1020E282A5BDD0DD7E55"}]]
      (testing name
        (sut/write-vega-into-svg input)
        (is (file-exists? svg-file-path))
        (is (= expected-hash (file->md5 svg-file-path)))

        (io/delete-file (io/file svg-file-path)))))


  (testing "render plot and store data in JSON"
    (doseq [:let [data-file-path "./tmp/vega-data.json"
                  svg-file-path "./tmp/vega-data.svg"]
            {:keys [name input expected-hash expected-data]}
            [{:name "vega-lite: plain data"
              :input {:vega-lite-spec vega-lite-spec
                      :data {:file-path data-file-path
                             :format :json}
                      :svg-file-path svg-file-path}
              :expected-hash "BC7311FD90D4ECD20A4155C9CCB3D0B6"
              :expected-data [{:a "A", :b 28}
                              {:a "B", :b 55}
                              {:a "C", :b 43}
                              {:a "D", :b 91}]}

             {:name "vega-lite: render dataset"
              :input {:vega-lite-spec
                      (-> vega-lite-spec
                          (dissoc :data)
                          (assoc-in [:data :values]
                                    (seq [(ds/->dataset [{:a 1 :b 2} {:a 3 :b 4}])
                                          (ds/->dataset [{:a 5 :b 6} {:a 7 :b 8}])])))
                      :data {:file-path data-file-path
                             :format :json}
                      :svg-file-path svg-file-path}
              :expected-hash "F87E018AA5BE34BB3C3292F8C4B6BBD5"
              :expected-data [{:a 1 :b 2} {:a 3 :b 4} {:a 5 :b 6} {:a 7 :b 8}]}

             {:name "vega: render plain plot"
              :input {:vega-spec vega-spec
                      :data {"table" {:file-path data-file-path
                                      :format :json}}
                      :svg-file-path svg-file-path}
              :expected-hash "CE332F7022C5FDA8504DF9ABC0E85CCF"
              :expected-data [{:amount 10, :category "A"}
                              {:amount 55, :category "B"}
                              {:amount 43, :category "C"}
                              {:amount 91, :category "D"}
                              {:category "E", :amount 81}
                              {:category "F", :amount 53}
                              {:category "G", :amount 19}
                              {:category "H", :amount 87}]}

             {:name "vega: render dataset"
              :input {:vega-spec
                      (assoc vega-spec
                             :data [{:name "table"
                                     :values
                                     (seq [(ds/->dataset
                                            [{:category 1 :amount 2}
                                             {:category 3 :amount 4}])
                                           (ds/->dataset
                                            [{:category 5 :amount 6}
                                             {:category 7 :amount 8}])])}])
                      :data {"table" {:file-path data-file-path
                                      :format :json}}
                      :svg-file-path svg-file-path}
              :expected-hash "B0C55522B562105BE7BFE710ECAB8D52"
              :expected-data [{:amount 2, :category 1}
                              {:amount 4, :category 3}
                              {:amount 6, :category 5}
                              {:amount 8, :category 7}]}]]
      (testing name
        (sut/write-vega-into-svg input)

        (is (file-exists? svg-file-path))
        (is (= expected-hash (file->md5 svg-file-path)))
        (is (file-exists? data-file-path))
        (is (= expected-data (file->edn data-file-path)))

        (io/delete-file (io/file data-file-path))
        (io/delete-file (io/file svg-file-path)))))


  (testing "vega: render 2 datasets"
    (let [svg-file-path "./tmp/vega-test.svg"
          table-path-json "./tmp/vega-test.json"
          second-table-json "./tmp/vega-test-2.json"
          values [{:category 100500 :amount 100501}]]

      (sut/write-vega-into-svg
       {:vega-spec (-> vega-spec
                       (update :data conj {:name "second-table"
                                           :values values}))
        :data {"table" {:file-path table-path-json :format :json}
               "second-table" {:file-path second-table-json :format :json}}
        :svg-file-path svg-file-path})

      (is (file-exists? svg-file-path))
      (is (= "CE332F7022C5FDA8504DF9ABC0E85CCF" (file->md5 svg-file-path)))
      (is (file-exists? second-table-json))
      (is (= values (file->edn second-table-json)))

      (io/delete-file (io/file svg-file-path))
      (io/delete-file (io/file table-path-json))
      (io/delete-file (io/file second-table-json))))


  (testing "vega-lite csv source"
    (let [svg-file-path "./tmp/vega-lite-csv.svg"
          data-file-path "./tmp/vega-lite-csv.csv"]

      (sut/write-vega-into-svg
       {:vega-lite-spec vega-lite-spec
        :data {:file-path data-file-path
               :format :csv
               :csv-header? true}
        :svg-file-path svg-file-path})

      (is (file-exists? svg-file-path))
      (is (= "BC7311FD90D4ECD20A4155C9CCB3D0B6" (file->md5 svg-file-path)))

      (is (file-exists? data-file-path))
      (is (= [["a" "b"] ["A" "28"] ["B" "55"] ["C" "43"] ["D" "91"]]
             (charred/read-csv (slurp data-file-path))))

      (io/delete-file (io/file svg-file-path))
      (io/delete-file (io/file data-file-path))))


  (testing "vega-lite: incorrect spec with clear error message"
    (is (thrown-with-msg?
         Exception #"vega/lite spec is incorrect: Error: Invalid field type \"INCORRECT_TYPE\"."
         (sut/write-vega-into-svg
          {:vega-lite-spec (assoc-in vega-lite-spec [:encoding :x :type] "INCORRECT_TYPE")
           :data {:file-path "./tmp/vega-lite-test.json"
                  :format :json}
           :svg-file-path "./tmp/vega-test.svg"}))))


  (testing "vega-lite: incorrect spec but the message is unclear"
    (is (thrown-with-msg?
         Exception #"vega/lite spec is incorrect: TypeError: Cannot convert undefined or null to object"
         (sut/write-vega-into-svg
          {:vega-lite-spec (assoc vega-lite-spec :mark "INCORRECT_PLOT_TYPE")
           :data {:file-path "./tmp/vega-lite-test.json"
                  :format :json}
           :svg-file-path "./tmp/vega-lite-test.svg"})))))



(deftest vega-pipeline-test
  (let [pg             (jdbc-test/pg-container)
        port           (get (:mapped-ports pg) 5432)
        connection-map {:dbtype   "postgresql"
                        :host     "localhost"
                        :port     port
                        :dbname   "test"
                        :user     "test"
                        :password "password"}]
    (with-open [conn (jdbc/get-connection connection-map)]
      (jdbc-test/populate-table conn))

    (let [pipeline (collet/compile-pipeline
                    {:name  :vega-sink-test
                     :deps  {:coordinates '[[org.postgresql/postgresql "42.7.3"]]
                             :requires    '[[collet.actions.jdbc-pg :as jdbc-pg]]}
                     :tasks [{:name    :query
                              :actions [{:name      :query-action
                                         :type      :collet.actions.jdbc/query
                                         :selectors {'connection [:config :connection]}
                                         :params    {:connection 'connection
                                                     :query      {:select [:*]
                                                                  :from   :users}}}
                                        {:name      :sink-action
                                         :type      :collet.actions.vega/sink
                                         :selectors {'input [:state :query-action]}
                                         :params    {:vega-lite-spec {:data {:values 'input},
                                                                      :mark "bar",
                                                                      :encoding
                                                                      {:x {:field "users/user_name", :type "nominal", :axis {:labelAngle 0}},
                                                                       :y {:field "users/age", :type "quantitative"}}}
                                                     :svg-file-path "./tmp/vega-sink-test.svg"
                                                     :data {:format :json
                                                            :file-path "./tmp/vega-sink-test.json"}}}]}]})]

      @(pipeline {:connection connection-map})

      (is (= [{"users/id" 1,"users/user_name" "Alice" "users/age" 30},
              {"users/id" 2 "users/user_name" "Bob" "users/age" 40},
              {"users/id" 3,"users/user_name" "Charlie" "users/age" 50}]
             (charred/read-json (slurp "./tmp/vega-sink-test.json"))))

      (tc/stop! pg)
      (io/delete-file (io/file "./tmp/vega-sink-test.json"))
      (io/delete-file (io/file "./tmp/vega-sink-test.svg")))))


(comment
  (md5 (slurp "./tmp/vega-lite-test.svg"))

  (write-vega-into-svg-test)
  ;;
  )
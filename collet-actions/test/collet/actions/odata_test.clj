(ns collet.actions.odata-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer :all]
   [collet.core :as collet]
   [collet.test-fixtures :as tf]
   [collet.actions.odata :as sut]))


(use-fixtures :once (tf/instrument! 'collet.actions.odata))


(deftest odata-segment-parsing-test
  (are [form _sep result] (= result (sut/segment-parser form))
    [:People] "=>>" [[:segment/name :People]]

    [:People :Friends] "=>>" [[:segment/name :People]
                              [:segment/name :Friends]]

    [[:People 2115]] "=>>" [[:segment/composite {:segment/composite-name :People,
                                                 :segment/params         [:segment/ident 2115]}]]

    [[:People "russellwhyte"]] "=>>" [[:segment/composite {:segment/composite-name :People,
                                                           :segment/params         [:segment/ident "russellwhyte"]}]]

    [[:OrderLine {:OrderId 1 :LineNumber 1}]] "=>>" [[:segment/composite {:segment/composite-name :OrderLine,
                                                                          :segment/params         [:segment/keys {:OrderId    1,
                                                                                                                  :LineNumber 1}]}]])

  (are [form result] (= result (sut/compile-odata-struct sut/segment-parser sut/join-as-path form))
    [:People :Friends] "People/Friends"
    [[:People 10] [:Friends "russellwhyte"]] "People(10)/Friends(russellwhyte)"
    [[:OrderLine {:OrderId 1 :LineNumber 1}]] "OrderLine(OrderId=1,LineNumber=1)"))


(deftest odata-filter-parsing-test
  (are [form result] (= result (sut/filter-parser form))
    [:and [:eq :FirstName "Vincent"] [:lt :Age 40]]
    [:filter/logic-expression
     {:logical/op          :and
      :logical/expressions [[:filter/expression {:expr/op       :eq
                                                 :expr/property [:segment/name :FirstName]
                                                 :expr/value    "Vincent"}]
                            [:filter/expression {:expr/op       :lt
                                                 :expr/property [:segment/name :Age]
                                                 :expr/value    40}]]}]

    [:eq [:Location :City :Region] "California"]
    [:filter/expression
     {:expr/op       :eq
      :expr/property [:segment/path [:Location :City :Region]]
      :expr/value    "California"}]

    [:eq :FirstName "Vincent"]
    [:filter/expression
     {:expr/op       :eq
      :expr/property [:segment/name :FirstName]
      :expr/value    "Vincent"}]

    [:and
     [:not [:contains :FirstName "Q"]]
     [:or
      [:any [:x :AddressInfo] [:eq [:x :City :Region] "WA"]]
      [:any [:x :AddressInfo] [:eq [:x :City :Region] "ID"]]]]
    [:filter/logic-expression
     {:logical/op          :and
      :logical/expressions [[:filter/function
                             {:function/fn   :not
                              :function/args [[:filter/function
                                               {:function/fn   :contains
                                                :function/args [[:segment/name :FirstName]
                                                                [:filter/value "Q"]]}]]}]
                            [:filter/logic-expression
                             {:logical/op          :or
                              :logical/expressions [[:filter/lambda
                                                     {:lambda/fn   :any
                                                      :lambda/args {:lambda/segment [:segment/name :AddressInfo]
                                                                    :lambda/var     :x}
                                                      :lambda/body [:filter/expression
                                                                    {:expr/op       :eq
                                                                     :expr/property [:segment/path [:x :City :Region]]
                                                                     :expr/value    "WA"}]}]
                                                    [:filter/lambda
                                                     {:lambda/fn   :any
                                                      :lambda/args {:lambda/segment [:segment/name :AddressInfo]
                                                                    :lambda/var     :x}
                                                      :lambda/body [:filter/expression
                                                                    {:expr/op       :eq
                                                                     :expr/property [:segment/path [:x :City :Region]]
                                                                     :expr/value    "ID"}]}]]}]]}])

  (are [form result] (= result (sut/compile-odata-struct sut/filter-parser form))
    [:eq :FirstName "Vincent"]
    "FirstName eq 'Vincent'"

    [:and [:eq :FirstName "Vincent"] [:lt :Age 40]]
    "(FirstName eq 'Vincent') and (Age lt 40)"

    [:eq [:Location :City :Region] "California"]
    "Location/City/Region eq 'California'"

    [:and
     [:not [:contains :FirstName "Q"]]
     [:or
      [:any [:x :AddressInfo] [:eq [:x :City :Region] "WA"]]
      [:any [:x :AddressInfo] [:eq [:x :City :Region] "ID"]]]]
    "(not(contains(FirstName, 'Q'))) and ((AddressInfo/any(x:x/City/Region eq 'WA')) or (AddressInfo/any(x:x/City/Region eq 'ID')))"))


(deftest odata-select-parsing-test
  (are [form result] (= result (sut/select-parser form))
    [:FirstName]
    [[:segment/name :FirstName]]

    [:AddressInfo :City :Region]
    [[:segment/name :AddressInfo]
     [:segment/name :City]
     [:segment/name :Region]])

  (are [form result] (= result (sut/compile-odata-struct sut/select-parser sut/join-as-list form))
    [:FirstName] "FirstName"
    [:AddressInfo :City :Region] "AddressInfo,City,Region"))


(deftest odata-order-by-parsing-test
  (are [form result] (= result (sut/order-by-parser form))
    [:FirstName]
    [[:segment/ident [:segment/name :FirstName]]]

    [:FirstName :UserName]
    [[:segment/ident [:segment/name :FirstName]]
     [:segment/ident [:segment/name :UserName]]]

    [[:Person :FirstName]]
    [[:segment/ident [:segment/path [:Person :FirstName]]]]

    [[:length :FirstName]]
    [[:segment/ident [:filter/function #:function{:fn :length, :args [[:segment/name :FirstName]]}]]]

    [{:segment :FirstName :dir :desc}]
    [[:order-by/expression {:segment [:segment/name :FirstName], :dir :desc}]]

    [{:segment :FirstName :dir :desc}
     :UserName]
    [[:order-by/expression {:segment [:segment/name :FirstName], :dir :desc}]
     [:segment/ident [:segment/name :UserName]]]

    [{:segment :FirstName :dir :desc}
     {:segment :UserName :dir :asc}]
    [[:order-by/expression {:segment [:segment/name :FirstName], :dir :desc}]
     [:order-by/expression {:segment [:segment/name :UserName], :dir :asc}]]

    [{:segment [:length :FirstName] :dir :desc}]
    [[:order-by/expression
      {:segment [:filter/function #:function{:fn :length, :args [[:segment/name :FirstName]]}],
       :dir     :desc}]]

    [{:segment [:Person :FirstName] :dir :asc}]
    [[:order-by/expression
      {:segment [:segment/path [:Person :FirstName]],
       :dir     :asc}]]

    [{:segment [:length :FirstName] :dir :desc}
     :UserName]
    [[:order-by/expression
      {:segment [:filter/function #:function{:fn :length, :args [[:segment/name :FirstName]]}],
       :dir     :desc}]
     [:segment/ident [:segment/name :UserName]]])

  (are [form result] (= result (sut/compile-odata-struct sut/order-by-parser sut/join-as-list form))
    [:FirstName]
    "FirstName"

    [:FirstName :UserName]
    "FirstName,UserName"

    [[:Person :FirstName]]
    "Person/FirstName"

    [[:length :FirstName]]
    "length(FirstName)"

    [{:segment :FirstName :dir :desc}]
    "FirstName desc"

    [{:segment :FirstName :dir :desc}
     :UserName]
    "FirstName desc,UserName"

    [{:segment :FirstName :dir :desc}
     {:segment :UserName :dir :asc}]
    "FirstName desc,UserName asc"

    [{:segment [:length :FirstName] :dir :desc}]
    "length(FirstName) desc"

    [{:segment [:Person :FirstName] :dir :asc}]
    "Person/FirstName asc"

    [{:segment [:length :FirstName] :dir :desc}
     :UserName]
    "length(FirstName) desc,UserName"))


(deftest odata-expand-parsing-test
  (are [form result] (= result (sut/expand-parser form))
    [:Friends]
    [[:segment/name :Friends]]

    [:Friends :Trips]
    [[:segment/name :Friends] [:segment/name :Trips]]

    [[:Friends :Trips]]
    [[:segment/path [:Friends :Trips]]]

    [[:Trips {:select [:Name :Budget]
              :filter [:eq :FirstName "Vincent"]}]]
    [[:expand/expression
      #:expand{:segment [:segment/name :Trips],
               :options {:select [:segment/list [[:segment/name :Name] [:segment/name :Budget]]],
                         :filter [:filter/expression
                                  #:expr{:op :eq, :property [:segment/name :FirstName], :value "Vincent"}]}}]])

  (are [form result] (= result (sut/compile-odata-struct sut/expand-parser sut/join-as-list form))
    [:Friends]
    "Friends"

    [:Friends :Trips]
    "Friends,Trips"

    [[:Friends :Trips]]
    "Friends/Trips"

    [[:Trips {:select [:Name :Budget]
              :filter [:eq :FirstName "Vincent"]}]]
    "Trips($filter=FirstName eq 'Vincent'; $select=Name,Budget)"))


(deftest odata-request-test
  (testing "request map"
    (let [req-map (sut/make-odata-request-map
                   {:service-url "http://services.odata.org/V4/TripPinService/"
                    :segment     [:People]
                    :select      [:FirstName :LastName]
                    :expand      [[:Friends {:select [:UserName]}]]
                    :filter      [:any [:x :AddressInfo]
                                  [:startswith [:x :City :Name] "B"]]
                    :order       [{:segment [:length :FirstName]
                                   :dir     :desc}]
                    :count       true})]
      (is {:url          "http://services.odata.org/V4/TripPinService/People",
           :query-params {:$filter  "AddressInfo/any(x:startswith(x/City/Name, 'B'))",
                          :$select  "FirstName,LastName",
                          :$expand  "Friends($select=UserName)",
                          :$orderby "length(FirstName) desc",
                          :$count   true}}
          req-map)))

  (testing "get the total count of records"
    (let [result (sut/odata-request
                  {:service-url     "http://services.odata.org/V4/TripPinService/"
                   :segment         [:People]
                   :get-total-count true}
                  nil)]
      (is (int? (:body result)))))

  (testing "performing request"
    (let [result (sut/odata-request
                  {:service-url "http://services.odata.org/V4/TripPinService/"
                   :segment     [:People]
                   :select      [:FirstName :LastName :AddressInfo]
                   :expand      [[:Friends {:select [:UserName]}]]
                   :filter      [:any [:x :AddressInfo]
                                 [:startswith [:x :City :Name] "B"]]
                   :order       [{:segment [:length :FirstName]
                                  :dir     :desc}]
                   :count       true}
                  nil)
          people (get-in result [:body "value"])]
      (is (= 200 (:status result)))
      (is (int? (get-in result [:body "@odata.count"])))
      (is (= (get-in result [:body "@odata.count"])
             (count people)))

      (is (every? #(string/starts-with? % "B")
                  (map #(get-in % ["AddressInfo" 0 "City" "Name"]) people))))))

(comment
 (sut/odata-request
  {:service-url "http://services.odata.org/V4/TripPinService/"
   :segment     [:Airports]}
  ;;:order            [:AirlineCode]
  ;;:filter           [:ne :AirlineCode nil]}
  nil)
 nil)

(deftest odata-pipeline-test
  (let [total-count (:body (sut/odata-request
                            {:service-url     "http://services.odata.org/V4/TripPinService/"
                             :segment         [:Airports]
                             :get-total-count true}
                            nil))]
    (testing "server side pagination"
      (let [pipeline-spec {:name  :airports-pipeline
                           :tasks [{:name       :airports
                                    :keep-state true
                                    :actions    [{:type   :collet.actions.odata/request
                                                  :name   :airports-request
                                                  :params {:service-url      "http://services.odata.org/V4/TripPinService/"
                                                           :segment          [:Airports]
                                                           :order            [:IcaoCode]
                                                           :follow-next-link true}}]
                                    :iterator   {:data [:state :airports-request :body "value"]
                                                 :next [:not-nil? [:state :airports-request :body "@odata.nextLink"]]}}]}
            pipeline      (collet/compile-pipeline pipeline-spec)
            _             @(pipeline {})
            {:keys [airports]} pipeline]
        (is (= (-> (/ total-count 8) (Math/ceil) int)
               (count airports)))
        (is (= 8 (count (first airports))))
        (is (= total-count (count (flatten airports))))))

    (testing "client side pagination"
      (let [pipeline-spec {:name  :airports-pipeline
                           :tasks [{:name       :airports
                                    :keep-state true
                                    :actions    [{:type      :counter
                                                  :name      :skip
                                                  :selectors {'bs [:config :batch-size]}
                                                  :params    {:start 0 :step 'bs}}
                                                 {:type      :collet.actions.odata/request
                                                  :name      :airports-request
                                                  :selectors {'bs   [:config :batch-size]
                                                              'skip [:state :skip]}
                                                  :params    {:service-url "http://services.odata.org/V4/TripPinService/"
                                                              :segment     [:Airports]
                                                              :order       [:IcaoCode]
                                                              :top         'bs
                                                              :skip        'skip}
                                                  :return    [:body "value"]}]
                                    :iterator   {:data [:state :airports-request]
                                                 :next [:not-empty? [:state :airports-request]]}}]}
            pipeline      (collet/compile-pipeline pipeline-spec)
            _             @(pipeline {:batch-size 4})
            {:keys [airports]} pipeline]
        ;; will make one additional request to get to the state when (not (empty? [])) will return true
        (is (= (-> (/ total-count 4) (Math/ceil) int inc)
               (count airports)))
        (is (= 4 (count (first airports))))
        (is (= total-count (count (flatten airports))))))

    (testing "manual client side pagination"
      (let [pipeline-spec {:name  :airports-pipeline
                           :tasks [{:name       :airports
                                    :keep-state true
                                    :setup      [{:type   :collet.actions.odata/request
                                                  :name   :total-airports-count
                                                  :params {:service-url     "http://services.odata.org/V4/TripPinService/"
                                                           :segment         [:Airports]
                                                           :get-total-count true}
                                                  :return [:body]}]
                                    :actions    [{:type      :counter
                                                  :name      :skip
                                                  :selectors {'bs [:config :batch-size]}
                                                  :params    {:start 0 :step 'bs}}
                                                 {:type      :collet.actions.odata/request
                                                  :name      :airports-request
                                                  :selectors {'bs   [:config :batch-size]
                                                              'skip [:state :skip]}
                                                  :params    {:service-url "http://services.odata.org/V4/TripPinService/"
                                                              :segment     [:Airports]
                                                              :order       [:IcaoCode]
                                                              :top         'bs
                                                              :skip        'skip}
                                                  :return    [:body "value"]}
                                                 {:type      :custom
                                                  :name      :continue?
                                                  :selectors {'batch-size           [:config :batch-size]
                                                              'skip                 [:state :skip]
                                                              'total-airports-count [:state :total-airports-count]}
                                                  :params    ['batch-size 'skip 'total-airports-count]
                                                  :fn        (fn [batch-size skip total-airports-count]
                                                               (< (+ skip batch-size) total-airports-count))}]
                                    :iterator   {:data [:state :airports-request]
                                                 :next [:true? [:state :continue?]]}}]}
            pipeline      (collet/compile-pipeline pipeline-spec)
            _             @(pipeline {:batch-size 4})
            {:keys [airports]} pipeline]
        (is (= (-> (/ total-count 4) (Math/ceil) int)
               (count airports)))
        (is (= 4 (count (first airports))))
        (is (= total-count (count (flatten airports))))))))

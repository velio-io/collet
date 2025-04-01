(ns collet.actions.intervals-test
  (:require
   [clojure.test :refer :all]
   [java-time.api :as jt]
   [collet.action :as action]
   [collet.test-fixtures :as tf]
   [collet.actions.intervals :as sut])
  (:import
   [java.time Instant]))


(use-fixtures :once (tf/instrument! 'collet.actions.intervals))


(deftest calculate-date-test
  (testing "string date parsing"
    (is (= (jt/local-date 2025 4 15)
           (sut/calculate-date "2025-04-15")))

    (is (= (jt/instant "2025-04-15T10:15:30Z")
           (sut/calculate-date "2025-04-15T10:15:30Z"))))

  (testing "instant handling"
    (let [test-instant (jt/instant "2025-04-15T10:15:30Z")]
      (is (= test-instant
             (sut/calculate-date test-instant)))))
  
  (testing "relative date calculations with :ahead and :ago"
    (let [today (jt/local-date)]
      ;; Test :ago direction
      (is (= (jt/minus today (jt/days 1))
             (sut/calculate-date [:day :ago])))
      (is (= (jt/minus today (jt/weeks 1))
             (sut/calculate-date [:week :ago])))
      (is (= (jt/minus today (jt/months 1))
             (sut/calculate-date [:month :ago])))
      (is (= (jt/minus today (jt/years 1))
             (sut/calculate-date [:year :ago])))
      
      ;; Test :ahead direction
      (is (= (jt/plus today (jt/days 1))
             (sut/calculate-date [:day :ahead])))
      (is (= (jt/plus today (jt/weeks 1))
             (sut/calculate-date [:week :ahead])))
      (is (= (jt/plus today (jt/months 1))
             (sut/calculate-date [:month :ahead])))
      (is (= (jt/plus today (jt/years 1))
             (sut/calculate-date [:year :ahead]))))))


(deftest format-date-test
  (let [test-date    (jt/local-date 2025 4 15)
        test-instant (jt/instant "2025-04-15T10:15:30Z")]

    (testing "format strings"
      (is (= "2025-04-15"
             (sut/format-date test-date :iso-date)))

      (is (= "2025-04-15"
             (sut/format-date test-instant :iso-date)))

      (is (= "2025-04-15T10:15:30Z"
             (sut/format-date test-instant :iso)))

      (is (= "2025-04-15T10:15:30"
             (sut/format-date test-instant :timestamp)))

      (is (= "2025-04-15T10:15:30Z"
             (sut/format-date test-instant :rfc3339)))

      (is (= "2025-04-15 10:15:30"
             (sut/format-date test-instant :sql-timestamp))))

    (testing "custom format patterns"
      (is (= "15/04/2025"
             (sut/format-date test-date "dd/MM/yyyy")))

      (is (= "Apr 15, 2025"
             (sut/format-date test-date "MMM dd, yyyy"))))

    (testing "epoch time"
      (is (= (jt/to-millis-from-epoch test-instant)
             (sut/format-date test-instant :epoch))))))


(deftest generate-recurring-dates-test
  (testing "recurring day patterns"
    (let [from    (jt/local-date 2025 1 1)
          to      (jt/local-date 2025 1 31)
          mondays (sut/generate-recurring-dates {:type :recurring-day :value :monday} from to)]

      (is (= 4 (count mondays)))
      (is (every? #(= 1 (jt/as % :day-of-week)) mondays))
      (is (= [(jt/local-date 2025 1 6)
              (jt/local-date 2025 1 13)
              (jt/local-date 2025 1 20)
              (jt/local-date 2025 1 27)]
             mondays))))

  (testing "recurring week patterns"
    (let [from           (jt/local-date 2025 1 1)
          to             (jt/local-date 2025 12 31)
          second-mondays (sut/generate-recurring-dates {:type :recurring-week :value [2 :monday]} from to)]

      (is (= 12 (count second-mondays)))
      (is (every? #(and (= 1 (jt/as % :day-of-week))
                        (= 2 (jt/as % :aligned-week-of-month))) second-mondays))))

  (testing "recurring month patterns"
    (let [from       (jt/local-date 2025 1 1)
          to         (jt/local-date 2025 12 31)
          fifteenths (sut/generate-recurring-dates {:type :recurring-month :value 15} from to)]

      (is (= 12 (count fifteenths)))
      (is (every? #(= 15 (jt/as % :day-of-month)) fifteenths))
      (is (= [(jt/local-date 2025 1 15)
              (jt/local-date 2025 2 15)
              (jt/local-date 2025 3 15)
              (jt/local-date 2025 4 15)
              (jt/local-date 2025 5 15)
              (jt/local-date 2025 6 15)
              (jt/local-date 2025 7 15)
              (jt/local-date 2025 8 15)
              (jt/local-date 2025 9 15)
              (jt/local-date 2025 10 15)
              (jt/local-date 2025 11 15)
              (jt/local-date 2025 12 15)]
             fifteenths))))

  (testing "string value support"
    (let [from    (jt/local-date 2025 1 1)
          to      (jt/local-date 2025 1 31)
          mondays (sut/generate-recurring-dates {:type :recurring-day :value "monday"} from to)]

      (is (= 4 (count mondays)))
      (is (every? #(= 1 (jt/as % :day-of-week)) mondays)))))


(deftest generate-intervals-test
  (testing "recurring patterns return collections"
    (let [result (sut/generate-intervals {:from    "2025-01-01"
                                          :to      "2025-01-31"
                                          :pattern {:type  :recurring-day
                                                    :value :monday}})]
      (is (vector? result))
      (is (= 4 (count result)))
      (is (= ["2025-01-06" "2025-01-13" "2025-01-20" "2025-01-27"]
             result)))

    (let [result (sut/generate-intervals {:from      "2025-01-01"
                                          :to        "2025-03-31"
                                          :pattern   {:type  :recurring-week
                                                      :value [2 :monday]}
                                          :return-as :objects})]
      (is (vector? result))
      (is (= 3 (count result)))
      (is (every? #(map? %) result))
      (is (every? #(contains? % :date) result))))
  
  (testing "relative date specifications with :ahead and :ago"
    (let [result (sut/generate-intervals {:from [:day :ago]
                                          :to   [:day :ahead]})]
      (is (map? result))
      (is (= 2 (count result)))
      (is (contains? result :from))
      (is (contains? result :to)))
    
    (let [result (sut/generate-intervals {:from    [:month :ago]
                                          :to      [:week :ahead]
                                          :pattern {:type  :recurring-day
                                                    :value :monday}})]
      (is (vector? result))
      (is (> (count result) 0))
      (is (every? string? result))))

  (testing "interval count returns multiple intervals"
    (let [result (sut/generate-intervals {:from     "2025-01-01"
                                          :to       "2025-01-31"
                                          :interval :weeks
                                          :count    4})]
      (is (vector? result))
      (is (= 4 (count result)))
      (is (= [{:from "2025-01-01", :to "2025-01-07"}
              {:from "2025-01-08", :to "2025-01-14"}
              {:from "2025-01-15", :to "2025-01-21"}
              {:from "2025-01-22", :to "2025-01-31"}]
             result)))

    (let [result (sut/generate-intervals {:from      "2025-01-01"
                                          :to        "2025-03-31"
                                          :interval  :months
                                          :count     3
                                          :return-as :objects})]
      (is (vector? result))
      (is (= 3 (count result)))
      (is (every? #(map? %) result))
      (is (every? #(and (contains? % :from) (contains? % :to)) result))))

  (testing "various return formats"
    (let [from-date       (jt/local-date 2025 3 24)
          to-date         (jt/local-date 2025 3 31)
          strings-result  (sut/generate-intervals {:from      from-date
                                                   :to        to-date
                                                   :return-as :strings})
          objects-result  (sut/generate-intervals {:from      from-date
                                                   :to        to-date
                                                   :return-as :objects})
          instants-result (sut/generate-intervals {:from      from-date
                                                   :to        to-date
                                                   :return-as :instants})
          dates-result    (sut/generate-intervals {:from      from-date
                                                   :to        to-date
                                                   :return-as :dates})]
      (is (= {:from "2025-03-24" :to "2025-03-31"}
             strings-result))
      (is (= {:from from-date :to to-date}
             dates-result))
      (is (= from-date
             (:from objects-result)))
      (is (instance? Instant (:from instants-result))))))


(deftest action-integration-test
  (testing "action function registration with recurring pattern"
    (let [action-spec     {:name   :mondays
                           :type   :intervals
                           :params {:from    "2025-01-01"
                                    :to      "2025-01-31"
                                    :pattern {:type  :recurring-day
                                              :value :monday}}}
          prepared-spec   (action/prep action-spec)
          action-function (action/action-fn prepared-spec)
          result          (action-function (:params prepared-spec))]

      (is (vector? result))
      (is (= 4 (count result)))
      (is (= ["2025-01-06" "2025-01-13" "2025-01-20" "2025-01-27"]
             result)))))
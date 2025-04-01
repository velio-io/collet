(ns collet.actions.intervals
  (:require
   [collet.action :as action]
   [clojure.string :as string]
   [java-time.api :as jt]
   [malli.core :as m])
  (:import
   [java.time.format DateTimeFormatter]
   [java.time.temporal Temporal]
   [java.util Locale]))


(def temporal?
  (m/-simple-schema
   {:type :temporal?
    :pred #(instance? Temporal %)
    :type-properties
    {:error/message "must implement the java.time.temporal.Temporal interface"}}))


(def intervals-params-spec
  [:map
   [:from
    [:or
     :string
     temporal?
     [:enum :today :yesterday :now]
     [:cat [:enum :day :week :month :year] [:enum :ago :ahead]]]]
   [:to
    [:or
     :string
     temporal?
     [:enum :today :yesterday :now]
     [:cat [:enum :day :week :month :year] [:enum :ago :ahead]]]]
   [:format {:optional true}
    [:or
     :string
     [:enum :iso :iso-date :timestamp :rfc3339 :sql-timestamp :epoch]]]
   [:interval {:optional true}
    [:enum :days :weeks :months :years]]
   [:count {:optional true} :int]
   [:pattern {:optional true}
    [:or
     :string
     [:map
      [:type [:enum :recurring-day :recurring-week :recurring-month]]
      [:value :any]]]]
   [:return-as {:optional true}
    [:enum :strings :objects :instants :dates]]])


(defn calculate-date
  "Calculate date based on given relative keywords or actual date"
  [date-spec]
  (cond
    (= date-spec :today)
    (jt/local-date)

    (= date-spec :yesterday)
    (jt/minus (jt/local-date) (jt/days 1))

    (= date-spec :now)
    (jt/instant)

    (vector? date-spec)
    (let [[unit direction] date-spec]
      (case direction
        :ago
        (case unit
          :day (jt/minus (jt/local-date) (jt/days 1))
          :week (jt/minus (jt/local-date) (jt/weeks 1))
          :month (jt/minus (jt/local-date) (jt/months 1))
          :year (jt/minus (jt/local-date) (jt/years 1))
          nil)

        :ahead
        (case unit
          :day (jt/plus (jt/local-date) (jt/days 1))
          :week (jt/plus (jt/local-date) (jt/weeks 1))
          :month (jt/plus (jt/local-date) (jt/months 1))
          :year (jt/plus (jt/local-date) (jt/years 1))
          nil)

        nil))

    (string? date-spec)
    (try
      (jt/instant date-spec)
      (catch Exception _
        (try
          (jt/local-date date-spec)
          (catch Exception _
            nil))))

    (jt/instant? date-spec)
    date-spec

    :else date-spec))


(defn format-date
  "Format date based on given format"
  [date format-spec]
  (if (nil? date)
    nil
    (let [date (if (jt/instant? date)
                 (jt/zoned-date-time date "UTC")
                 date)]
      (cond
        (= format-spec :iso)
        (str (jt/instant date))

        (= format-spec :iso-date)
        (jt/format "yyyy-MM-dd" date)

        (= format-spec :timestamp)
        (jt/format "yyyy-MM-dd'T'HH:mm:ss" date)

        (= format-spec :rfc3339)
        (jt/format "yyyy-MM-dd'T'HH:mm:ssXXX" date)

        (= format-spec :sql-timestamp)
        (jt/format "yyyy-MM-dd HH:mm:ss" date)

        (= format-spec :epoch)
        (jt/to-millis-from-epoch date)

        (string? format-spec)
        (jt/format
         (DateTimeFormatter/ofPattern format-spec Locale/ENGLISH)
         date)

        :otherwise
        (str date)))))


(defn generate-recurring-dates
  "Generate recurring dates based on pattern"
  [{:keys [type value]} from to]
  (let [from-date (if (jt/local-date? from) from (jt/local-date from))
        to-date   (if (jt/local-date? to) to (jt/local-date to))]
    (case type
      :recurring-day
      (let [day-of-week (if (string? value)
                          (keyword (string/lower-case value))
                          value)
            day-num     (case day-of-week
                          :monday 1
                          :tuesday 2
                          :wednesday 3
                          :thursday 4
                          :friday 5
                          :saturday 6
                          :sunday 7)]
        (->> (iterate #(jt/plus % (jt/days 1)) from-date)
             (take-while #(jt/not-after? % to-date))
             (filter #(= (jt/as % :day-of-week) day-num))))

      :recurring-week
      (let [[week-num day-of-week] value
            day-num (case (if (string? day-of-week)
                            (keyword (string/lower-case day-of-week))
                            day-of-week)
                      :monday 1
                      :tuesday 2
                      :wednesday 3
                      :thursday 4
                      :friday 5
                      :saturday 6
                      :sunday 7)]
        (->> (iterate #(jt/plus % (jt/days 1)) from-date)
             (take-while #(jt/not-after? % to-date))
             (filter #(and (= (jt/as % :day-of-week) day-num)
                           (= (jt/as % :aligned-week-of-month) week-num)))))

      :recurring-month
      (let [day-of-month value]
        (->> (iterate #(jt/plus % (jt/days 1)) from-date)
             (take-while #(jt/not-after? % to-date))
             (filter #(= (jt/as % :day-of-month) day-of-month))))

      [])))


(defn generate-interval-sequence
  "Generate a sequence of interval maps based on the specified count and interval"
  [from-date to-date interval count format return-as]
  (let [interval-fn (case interval
                      :days jt/days
                      :weeks jt/weeks
                      :months jt/months
                      :years jt/years)]
    (->> (range count)
         (mapv (fn [i]
                 (let [start (if (= i 0)
                               from-date
                               (jt/plus from-date (interval-fn i)))
                       end   (if (= i (dec count))
                               to-date
                               (jt/minus (jt/plus start (interval-fn 1)) (jt/days 1)))]
                   (case return-as
                     :strings
                     {:from (format-date start format)
                      :to   (format-date end format)}
                     :objects
                     {:from start
                      :to   end}
                     :instants
                     {:from (jt/instant start)
                      :to   (jt/instant end)}
                     :dates
                     {:from start
                      :to   end})))))))


(defn generate-intervals
  "Generate time intervals based on provided parameters.
   
   Parameters:
   - :from - start date/time (can be a keyword like [:week :ago], a string date, or a java-time instant)
   - :to - end date/time (can be a keyword like :today, a string date, or a java-time instant)
   - :format - output format for date strings (:iso, :iso-date, :timestamp, :rfc3339, :sql-timestamp, :epoch, or custom format string)
   - :interval - time interval between generated dates (:days, :weeks, :months, :years)
   - :count - number of intervals to generate (default 1)
   - :pattern - pattern for generating recurring dates (e.g., {:type :recurring-week, :value [2 :monday]} for every second Monday)
   - :return-as - format of returned values (:strings, :objects, :instants, :dates)
   
   Returns a map with :from and :to keys containing the formatted dates,
   a sequence of dates if a pattern is specified,
   or a sequence of interval maps if count > 1.
   Note that if you specify a count for intervals and the whole range represented by `from` `to` parameters
   wouldn't fit in the count (let's say month contains more than 4 weeks),
   then the last interval will contain as many days as needed to cover the range."
  {:malli/schema [:=> [:cat intervals-params-spec]
                  :any]}
  [{:keys [from to format interval count pattern return-as]
    :or   {from :today to :today format :iso-date interval :days count 1 return-as :strings}}]
  (let [from-date (calculate-date from)
        to-date   (calculate-date to)]
    (cond
      ;; Generate recurring dates based on pattern
      pattern
      (let [dates (generate-recurring-dates pattern from-date to-date)]
        (case return-as
          :strings
          (mapv #(format-date % format) dates)
          :objects
          (mapv #(hash-map :date %) dates)
          :instants
          (mapv jt/instant dates)
          :dates
          dates))

      ;; Generate multiple intervals if count > 1
      (> count 1)
      (generate-interval-sequence from-date to-date interval count format return-as)

      ;; Generate a single from/to interval
      :else
      (case return-as
        :strings
        {:from (format-date from-date format)
         :to   (format-date to-date format)}
        :objects
        {:from from-date
         :to   to-date}
        :instants
        {:from (jt/instant (jt/zoned-date-time from-date "UTC"))
         :to   (jt/instant (jt/zoned-date-time to-date "UTC"))}
        :dates
        {:from from-date
         :to   to-date}))))


(defmethod action/action-fn :intervals [_]
  generate-intervals)

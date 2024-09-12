(ns collet.conditions)


(def condition->fn
  "Map containing the functions associated to the where options"
  {:pos?        pos?
   :neg?        neg?
   :zero?       zero?
   :>           >
   :>=          >=
   :<           <
   :<=          <=
   :=           =
   :always-true (constantly true)
   :true?       true?
   :false?      false?
   :contains    (fn [field value]
                  (some #(= value %) field))
   :absent      (fn [field value]
                  (not (some #(= value %) field)))
   :regex       #(re-matches %2 %1)
   :nil?        nil?
   :not-nil?    (comp not nil?)
   :not=        not=
   :empty?      empty?
   :not-empty?  (comp not empty?)})


(defn valid-condition-value?
  "Checks if the value of a condition is a valid keyword or a list of keywords"
  [condition-value]
  (if (sequential? condition-value)
    (every? identity (map #(or (keyword? %) (string? %)) condition-value))
    (keyword? condition-value)))


(defn valid-condition?
  "Checks if a condition is valid"
  [condition]
  (and
   (sequential? condition)
   (cond
     (or (= :or (first condition))
         (= :and (first condition)))
     (every? identity (map #(valid-condition? %) (rest condition)))

     (= :always-true (first condition))
     true

     :else
     (and (contains? condition->fn (first condition))
          (valid-condition-value? (second condition))))))


(defn prep-args
  "If argument is a vector, it will be replaced by the value in the data
   at the path specified by the vector"
  [args data]
  (map
   (fn [arg]
     (if (vector? arg)
       (get-in data arg)
       arg))
   args))


(defn compile-condition
  "Takes a condition and returns a function which can be applied to the
   data to check if the condition is valid for it"
  [[condition field & args]]
  (let [condition-fn (get condition->fn condition)
        regex?       (= :regex condition)
        args         (if (and regex? (string? (first args)))
                       [(-> (first args) re-pattern)]
                       args)
        fields       (if (sequential? field)
                       field
                       [field])]
    (when (nil? condition-fn)
      (throw (ex-info "Invalid condition" {:condition condition})))

    (fn [data]
      (try
        (apply condition-fn
               (get-in data fields)
               (prep-args args data))
        (catch Exception e
          ;; condition didn't match
          false)))))


(def compile-conditions
  "Takes a condition and returns a function which can be applied to the
   data to check if the condition is valid for it"
  (memoize
   (fn [conditions]
     (let [compile-conditions-fn
           (fn [cd] (reduce
                     (fn [state [operation :as condition]]
                       (if (or (= :and operation) (= :or operation))
                         (conj state (compile-conditions condition))
                         (conj state (compile-condition condition))))
                     []
                     cd))]
       (cond
         (= :or (first conditions))
         (let [cond-fns (compile-conditions-fn (rest conditions))]
           (fn [data] (some identity (map #(% data) cond-fns))))

         (= :and (first conditions))
         (let [cond-fns (compile-conditions-fn (rest conditions))]
           (fn [data] (every? identity (map #(% data) cond-fns))))

         :else
         (let [cond-fn (compile-condition conditions)]
           (fn [data]
             (cond-fn data))))))))

(ns collet.actions.fold
  (:require
   [collet.actions.common :as common])
  (:import
   [clojure.lang ISeq]))


(defn lazy-seq? [x]
  (instance? ISeq x))


(defn adjust-path [data path pred]
  (loop [p path]
    (if (pred (get-in data p))
      p
      (recur (butlast p)))))


(def fold-params-spec
  [:map
   [:value {:optional true} :any]
   [:op [:enum :replace :merge :conj]]
   [:in [:vector :any]]
   [:with :any]])


(defn fold
  "This action can fold (accumulate or build) a value over multiple iterations.
   If no previous value is provided, the value argument will be used as the initial value.
   Next iterations will use the value returned by the previous iteration."
  {:malli/schema [:=> [:cat fold-params-spec :any]
                  :any]}
  [{:keys [value op in with]} prev-state]
  (let [old-value  (or prev-state value)
        old-value' (if (lazy-seq? old-value) (vec old-value) old-value)]
    (case op
      :replace (assoc-in old-value' in with)
      :merge (update-in old-value' (adjust-path old-value' in map?) merge with)
      :conj (update-in old-value' (adjust-path old-value' in sequential?) conj with)
      old-value')))


(def fold-action
  {:action fold
   :prep   common/prep-stateful-action})
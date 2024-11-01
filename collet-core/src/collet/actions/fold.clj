(ns collet.actions.fold
  (:require
   [collet.actions.common :as common]))


(defn conjoin
  "Will traverse through the data according to provided path.
   At the end of the path, depending on the data type, it will either
   merge the item into the map or add it to the vector."
  ([data path item]
   (conjoin data path item false))

  ([data path item in-loop]
   (let [[p & rest-path] path]
     (cond
       ;; empty path case
       (and (nil? p) in-loop)
       item
       ;; seq case
       (and (nil? p) (sequential? data))
       (conj data item)
       ;; map case
       (and (nil? p) (map? data))
       (merge data item)
       ;; root base case
       (nil? p) data
       ;; try to add new item
       (number? p)
       (cond (seq rest-path) (assoc data p (conjoin (get data p) rest-path item true))
             (< p (count data)) (assoc data p item)
             :otherwise (conj data item))
       ;; try to enter the map
       :otherwise (assoc data p (conjoin (get data p) rest-path item true))))))


(def fold-params-spec
  [:map
   [:item :any]
   [:in {:optional true} [:vector [:or :string :keyword :int]]]
   [:with {:optional true} :any]])


(defn fold
  "Collect items into a vector. Item can be updated with provided value."
  {:malli/schema [:=> [:cat fold-params-spec
                       [:maybe [:vector :any]]]
                  [:vector :any]]}
  [{:keys [item in with]} prev-state]
  (let [data (or prev-state [])]
    (conj data (conjoin item in with))))


(defn expand-task-params
  "Keep latest state from task as fold will accumulate value"
  [task _action]
  (assoc task :state-format (or (:state-format task) :latest)))


(def fold-action
  {:action fold
   :prep   common/prep-stateful-action
   :expand expand-task-params})
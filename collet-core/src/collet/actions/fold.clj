(ns collet.actions.fold
  (:require
   [collet.action :as action]
   [collet.actions.common :as common]
   [collet.conditions :as conds]))


(defn append-path
  [path prefix]
  (cond
    (vector? path) (cons prefix path)
    (keyword? path) [prefix path]
    :otherwise path))


(defn apply-to-matching-items
  "Apply a function to items in a sequence that match a condition"
  [data condition with f]
  (if (sequential? data)
    (let [[cfn field value & rest] condition
          condition'     (vec (concat [cfn (append-path field :item) (append-path value :with)] rest))
          condition-fn   (conds/compile-conditions condition')
          update-matched (fn [data with]
                           (mapv (fn [item]
                                   (if (condition-fn {:item item :with with})
                                     (f item with)
                                     item))
                                 data))]
      ;; TODO optimize this to avoid traversing the data twice
      (if (sequential? with)
        (reduce (fn [d w]
                  (update-matched d w))
                data with)
        (update-matched data with)))
    data))


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

       ;; condition vector case ([:= :id :user-id])
       (and (vector? p) (conds/valid-condition? p))
       (apply-to-matching-items
        data p item
        (fn [d i]
          (if (seq rest-path)
            (conjoin d rest-path item true)
            (merge d i))))

       ;; try to add new item with index
       (number? p)
       (cond (seq rest-path) (assoc data p (conjoin (get data p) rest-path item true))
             (< p (count data)) (assoc data p item)
             :otherwise (conj data item))

       ;; try to enter the map
       :otherwise (assoc data p (conjoin (get data p) rest-path item true))))))


(def fold-params-spec
  [:map
   [:item :any]
   [:into {:optional true} [:sequential :any]]
   [:op {:optional true} [:enum :concat]]
   [:in {:optional true} [:vector [:or :string :keyword :int conds/condition?]]]
   [:with {:optional true} :any]])


(defn fold
  "Collect items into a vector. Item can be updated with provided value.
   
   Parameters:
   - :item - The item to add to the collection
   - :into - (optional) Initial collection to use (default: [])
   - :op - (optional) Operation to perform [:concat]
   - :in - (optional) Path to traverse within the item before merging 'with' value
   - :with - (optional) Value to merge with the item at the specified path
   
   The :in parameter supports condition vectors for finding elements in collections:
   [:= :id 2] - Match elements where :id equals 2
   [:contains :tags \"premium\"] - Match elements where :tags contains \"premium\"
   
   The condition syntax supports all condition functions from collet.conditions:
   :=, :not=, :<, :>, :<=, :>=, :contains, :regex, :nil?, :not-nil?, :empty?, :not-empty?
   
   Examples:
   ```
   ;; Add a role to user with ID 2
   (fold {:item users :in [[:= :id 2]] :with {:role \"Admin\"}} nil)
   
   ;; Update street for work addresses
   (fold {:item users :in [:addresses [:= :type \"work\"] :street] :with \"New Address\"} nil)
   ```"
  {:malli/schema [:=> [:cat fold-params-spec
                       [:maybe [:vector :any]]]
                  [:vector :any]]}
  [{:keys [into op item in with]
    :or   {into []}}
   prev-state]
  (let [data (or prev-state into)]
    (if (= op :concat)
      (vec (concat data item))
      (conj data (conjoin item in with)))))


(defmethod action/action-fn :fold [_]
  fold)


(defmethod action/prep :fold [action-spec]
  (common/prep-stateful-action action-spec))


(defmethod action/expand :fold [task _action]
  ;; Keep latest state from task as fold will accumulate value
  (assoc task :state-format (or (:state-format task) :latest)))
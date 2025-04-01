(ns collet.actions.enrich
  (:require
   [collet.action :as action]
   [collet.utils :as utils]))


(def enrich-params-spec
  [:map
   [:name :keyword]
   [:action :keyword]
   [:target collet.select/select-path]
   [:when {:optional true} [:vector :any]]
   [:selectors {:optional true} [:map-of :symbol collet.select/select-path]]
   [:params {:optional true} [:or map? [:vector :any]]]
   [:return {:optional true} collet.select/select-path]
   [:fold-in {:optional true} [:vector [:or :string :keyword :int]]]
   [:fn {:optional true} [:or fn? list?]]])


(defn enrich
  "Enriches data mapping over items in the target collection.
   Specified action will be invoked for each item.
   All updated items are collected in the result.
   This action works like a macro call,
   so it will replace itself with a vector of three actions: mapper, action and fold."
  {:malli/schema [:=> [:cat enrich-params-spec]
                  [:vector map?]]}
  [{:keys        [target fold-in action selectors params return]
    execute-when :when
    :or          {selectors {}}
    action-name  :name
    custom-fn    :fn}]
  (let [base-name     (name action-name)
        mapper-key    (keyword (str base-name "-mapper"))
        action-key    (keyword (str base-name "-action"))
        target-sym    (gensym "target")
        item-sym      (gensym "item")
        with-sym      (gensym "with")
        selectors'    (reduce-kv
                       (fn [acc sym path]
                         (->> (utils/replace-all path {:$enrich/item [:state mapper-key :current]})
                              (assoc acc sym)))
                       {} selectors)
        execute-when' (utils/replace-all execute-when {:$enrich/item [:state mapper-key :current]})]
    ;; return a vector of three actions
    ;; slicer action
    [{:type      :mapper
      :name      mapper-key
      :selectors {target-sym target}
      :params    {:sequence target-sym}}
     ;; enrich (get additional data) action
     (utils/assoc-some
       {:type      action
        :name      action-key
        :selectors selectors'
        :params    params}
       :when execute-when'
       :fn custom-fn
       :return return)
     ;; fold (collect results) action
     {:type      :fold
      :name      action-name
      :selectors {item-sym [:state mapper-key :current]
                  with-sym [:state action-key]}
      :params    (utils/assoc-some
                   {:item item-sym
                    :with with-sym}
                   :in fold-in)}]))


(defmethod action/prep :enrich [action-spec]
  (enrich action-spec))

;; TODO add option to run enrich in parallel

(defmethod action/expand :enrich [task action]
  ;; Unwraps the enrich bindings and replaces the iterator with the mapper keys
  (let [action-name (:name action)
        base-name   (name action-name)
        mapper-key  (keyword (str base-name "-mapper"))]
    (cond-> task
      :always
      (assoc :state-format (or (:state-format task) :latest))
      (some? (:iterator task))
      (update :iterator utils/replace-all
              {:$enrich/item          [:state mapper-key :current]
               :$enrich/has-next-item [:state mapper-key :next]})
      (some? (:update task))
      (update :return utils/replace-all
              {:$enrich/item          [:state mapper-key :current]
               :$enrich/has-next-item [:state mapper-key :next]}))))

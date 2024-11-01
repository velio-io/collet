(ns collet.actions.odata
  (:require
   [clojure.set :as set]
   [clojure.string :as string]
   [clojure.walk :as walk]
   [malli.core :as m]
   [collet.actions.common :as common]
   [collet.actions.http :as collet.http]
   [collet.utils :as utils]))


(def registry
  (merge (m/default-schemas)
         {::simple-value [:or :string :int :double :boolean]
          ::expression   [:catn
                          [:expr/op :keyword]
                          [:expr/property [:orn
                                           [:segment/name :keyword]
                                           [:segment/path [:vector :keyword]]]]
                          [:expr/value ::simple-value]]
          ::function     [:catn
                          [:function/fn [:enum :not :contains :endswith :startswith :length]]
                          [:function/args [:+ [:orn
                                               [:segment/name :keyword]
                                               [:segment/path [:vector :keyword]]
                                               [:filter/value ::simple-value]
                                               [:filter/function [:schema [:ref ::function]]]
                                               [:filter/expression [:schema [:ref ::expression]]]]]]]
          ::lambda       [:catn
                          [:lambda/fn [:enum :any :all]]
                          [:lambda/args [:schema [:catn
                                                  [:lambda/var :keyword]
                                                  [:lambda/segment [:orn
                                                                    [:segment/name :keyword]
                                                                    [:segment/path [:vector :keyword]]]]]]]
                          [:lambda/body [:orn
                                         [:filter/function [:schema [:ref ::function]]]
                                         [:filter/expression [:schema [:ref ::expression]]]]]]
          ::logical      [:catn
                          [:logical/op [:enum :and :or]]
                          [:logical/expressions [:repeat {:min 2}
                                                 [:orn
                                                  [:filter/function [:schema [:ref ::function]]]
                                                  [:filter/lambda [:schema [:ref ::lambda]]]
                                                  [:filter/logic-expression [:schema [:ref ::logical]]]
                                                  [:filter/expression [:schema [:ref ::expression]]]]]]]}))


(def segment-spec
  [:* [:orn
       [:segment/name :keyword]
       [:segment/composite
        [:catn
         [:segment/composite-name :keyword]
         [:segment/params
          [:orn
           [:segment/ident ::simple-value]
           [:segment/keys [:map-of :keyword ::simple-value]]]]]]]])


(def filter-spec
  [:orn
   [:filter/lambda [:ref ::lambda]]
   [:filter/function [:ref ::function]]
   [:filter/logic-expression [:ref ::logical]]
   [:filter/expression [:ref ::expression]]])


(def select-spec
  [:* [:orn
       [:segment/name :keyword]]])


(def order-by-spec
  [:schema {:registry
            {::segment [:orn
                        [:segment/name :keyword]
                        [:filter/function [:catn
                                           [:function/fn [:enum :not :contains :endswith :startswith :length]]
                                           [:function/args [:+ [:orn
                                                                [:segment/name :keyword]
                                                                [:filter/value ::simple-value]]]]]]
                        [:segment/path [:vector :keyword]]]}}
   [:* [:orn
        [:segment/ident [:schema [:ref ::segment]]]
        [:order-by/expression [:map
                               [:segment [:schema [:ref ::segment]]]
                               [:dir [:enum :asc :desc]]]]]]])


(def expand-spec
  [:* [:orn
       [:segment/name :keyword]
       [:segment/path [:vector :keyword]]
       [:expand/expression
        [:catn
         [:expand/segment [:orn [:segment/name :keyword]]]
         [:expand/options
          [:map
           [:filter {:optional true} filter-spec]
           [:select {:optional true} [:orn [:segment/list select-spec]]]
           [:order-by {:optional true} order-by-spec]
           [:skip {:optional true} :int]
           [:top {:optional true} :int]
           [:count {:optional true} :boolean]]]]]]])


(def segment-parser
  (m/parser segment-spec {:registry registry}))

(def filter-parser
  (m/parser filter-spec {:registry registry}))

(def select-parser
  (m/parser select-spec {:registry registry}))

(def order-by-parser
  (m/parser order-by-spec {:registry registry}))

(def expand-parser
  (m/parser expand-spec {:registry registry}))


(defn dispatch-fn [x]
  (if (sequential? x)
    (first x)
    :default))


(defmulti stringify
  "Multi-method to stringify the parsed OData parameters"
  #'dispatch-fn)

(defmethod stringify :segment/name
  [[_ segment-name]]
  (name segment-name))

(defmethod stringify :segment/composite
  [[_ segment]]
  (let [{segment-name :segment/composite-name params :segment/params} segment]
    (str (name segment-name) "(" params ")")))

(defmethod stringify :segment/ident
  [[_ value]]
  (str value))

(defmethod stringify :segment/keys
  [[_ params]]
  (->> params
       (map (fn [[k v]] (str (name k) "=" v)))
       (string/join ",")))

(defmethod stringify :segment/path
  [[_ path]]
  (->> path
       (map name)
       (string/join "/")))

(defmethod stringify :segment/list
  [[_ list]]
  (string/join "," list))

(defmethod stringify :filter/expression
  [[_ expression]]
  (let [{:expr/keys [op property value]} expression
        expr-value (if (string? value) (str "'" value "'") value)]
    (format "%s %s %s" property (name op) expr-value)))

(defmethod stringify :filter/value
  [[_ value]]
  (if (string? value) (str "'" value "'") value))

(defmethod stringify :filter/logic-expression
  [[_ expression]]
  (let [{:logical/keys [op expressions]} expression]
    (->> expressions
         (map #(str "(" % ")"))
         (string/join (str " " (name op) " ")))))

(defmethod stringify :filter/function
  [[_ func]]
  (let [{:function/keys [fn args]} func]
    (format "%s(%s)" (name fn) (string/join ", " args))))

(defmethod stringify :filter/lambda
  [[_ func]]
  (let [{:lambda/keys [fn args body]} func
        {:lambda/keys [var segment]} args
        var-name (name var)]
    (format "%s/%s(%s:%s)" segment (name fn) var-name body)))

(defmethod stringify :order-by/expression
  [[_ expression]]
  (let [{:keys [segment dir]} expression]
    (str segment (when dir (str " " (name dir))))))

(defmethod stringify :expand/expression
  [[_ expression]]
  (let [{:expand/keys [segment options]} expression
        expand-ops  (-> options
                        (select-keys [:filter :select :order-by :skip :top :count])
                        (set/rename-keys {:order-by :orderby}))
        options-str (->> expand-ops
                         (map (fn [[k v]] (str "$" (name k) "=" v)))
                         (string/join "; "))]
    (format "%s(%s)" segment options-str)))

(defmethod stringify :default [x] x)


(defn compile-odata-struct
  "Given a parser, a combine function and a data structure,
   it will compile the data structure into a string representation of the OData query parameters"
  ([parser data]
   (compile-odata-struct parser identity data))
  ([parser combine data]
   (let [parsed (parser data)]
     (when (not= ::m/invalid parsed)
       (-> (walk/postwalk stringify parsed)
           (combine))))))


(def join-as-path
  (partial string/join "/"))


(def join-as-list
  (partial string/join ","))


(defn make-odata-request-map
  "Builds the request map for an OData request"
  [{:keys  [service-url segment filter select expand order top skip
            follow-next-link next-link get-total-count]
    :or    {follow-next-link false
            get-total-count  false}
    $count :count}]
  (if (and follow-next-link next-link)
    ;; follow server side pagination
    {:url next-link}
    ;; build the request from scratch
    (let [segment-part (compile-odata-struct segment-parser join-as-path segment)]
      {:url          (cond-> service-url
                       ;; drop trailing slash
                       (string/ends-with? service-url "/")
                       (subs 0 (- (count service-url) 1))
                       ;; combine with segment part
                       (some? segment-part)
                       (str "/" segment-part)
                       ;; get the total records count. Useful to build the client side pagination
                       get-total-count
                       (str "/$count"))
       :query-params (utils/assoc-some {}
                       :$filter (compile-odata-struct filter-parser filter)
                       :$select (compile-odata-struct select-parser join-as-list select)
                       :$expand (compile-odata-struct expand-parser join-as-list expand)
                       :$orderby (compile-odata-struct order-by-parser join-as-list order)
                       :$top top
                       :$skip skip
                       :$count $count)})))


(def odata-params-spec
  [:map
   [:service-url :string]
   [:segment segment-spec]
   [:filter {:optional true} filter-spec]
   [:select {:optional true} select-spec]
   [:expand {:optional true} expand-spec]
   [:order {:optional true} order-by-spec]
   [:top {:optional true} :int]
   [:skip {:optional true} :int]
   [:count {:optional true} :boolean]
   [:follow-next-link {:optional true} :boolean]
   [:get-total-count {:optional true} :boolean]])


(defn odata-request
  "Makes an OData request
   Reuses the HTTP action to make the request but formats the request according to OData specs
   OData specific options: :service-url, :segment, :filter, :select, :expand, :order, :top, :skip, :count, :follow-next-link, :get-total-count
   Also other HTTP options can be passed like :rate-limiter, :headers, :unexceptional-status, :rate, etc"
  ;; @TODO: function spec erroring with custom registry
  ;; {:malli/schema [:function {:registry registry}
  ;;                 [:=> [:cat odata-params-spec :any] :any]]}
  [params prev-state]
  (let [next-link (some-> prev-state :body (get "@odata.nextLink"))
        request   (-> params
                      (assoc :next-link next-link)
                      (make-odata-request-map))]
    (-> (merge (dissoc params :service-url :segment :filter :select :expand :order :top :skip :count :follow-next-link :get-total-count)
               {:content-type :json
                :as           :json
                :keywordize   false}
               request)
        (collet.http/make-request))))


(def odata-action
  {:action odata-request
   :prep   (comp collet.http/attach-rate-limiter common/prep-stateful-action)})

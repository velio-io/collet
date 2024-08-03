(ns collet.metrics
  (:require
   [clojure.java.jmx :as jmx]))


(defonce beans
  (atom {}))


(defn ->bean-name [{:keys [name type]}]
  (format "collet.engine:type=%s,name=%s" type name))


(defn create-bean
  "Create a new MBean with the given name and rest of attributes"
  [attrs & [init]]
  (let [bean-ref  (ref (or init {}))
        bean-name (->bean-name attrs)
        bean      (jmx/create-bean bean-ref)]
    (jmx/register-mbean bean bean-name)
    (swap! beans assoc bean-ref bean-name)
    bean-ref))


(defn drop-bean
  "Drop a bean by its reference"
  [bean-ref]
  (when-let [bean-name (get @beans bean-ref)]
    (jmx/unregister-mbean bean-name)
    (swap! beans dissoc bean-ref)))


(def finc
  (fnil inc 0))


(defn increment-counter
  "Increment a counter in the metrics MBean.
   The counter is identified by its name."
  [bean-ref attribute-name]
  (dosync
   (alter bean-ref update attribute-name finc)))


(defn set-attribute
  "Set an attribute in the MBean.
   The attribute is identified by its name."
  [bean-ref attribute-name value]
  (dosync
   (alter bean-ref assoc attribute-name value)))


(comment
 (def mb
   (create-bean {:name "performance" :type "pipeline_metrics"}))

 (increment-counter mb :http-requests-num)
 (set-attribute mb :http-requests-avg-time 1587)

 (drop-bean mb)

 (jmx/mbean-names "*:*")
 (jmx/read "collet.engine:name=latency,type=pipeline_metrics" :http-requests-num))
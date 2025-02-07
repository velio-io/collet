(ns collet.actions.jslt
  (:require
   [collet.action :as action])
  (:import
   [com.fasterxml.jackson.databind JsonNode ObjectMapper]
   [com.schibsted.spt.data.jslt Parser Expression]
   [java.util Map$Entry]))


(defn parse
  "Parse a JsonNode into a Clojure data structure"
  [^JsonNode node]
  (cond
    ;; If the node is a JSON object, convert it to a map.
    (.isObject node)
    (into {} (map (fn [^Map$Entry entry]
                    ;; Convert field names to keywords and recurse on the value.
                    [(keyword (.getKey entry))
                     (parse (.getValue entry))]))
          (iterator-seq (.fields node)))

    ;; If the node is a JSON array, convert it to a vector.
    (.isArray node)
    (vec (map parse (iterator-seq (.elements node))))

    ;; For textual, numeric, boolean, or null nodes, extract the appropriate value.
    (.isTextual node) (.asText node)
    (.isNumber node) (.numberValue node)
    (.isBoolean node) (.asBoolean node)
    (.isNull node) nil

    ;; Fallback: convert unknown types to string.
    :else (.toString node)))


(defn apply-jslt-template
  [{::keys [^ObjectMapper mapper ^Expression expr]
    :keys  [^String input as]}]
  (let [input-node ^JsonNode (.readTree mapper input)
        output     ^JsonNode (.apply expr input-node)]
    (if (= as :string)
      (.writeValueAsString mapper output)
      (parse output))))


(defmethod action/action-fn ::apply [_]
  apply-jslt-template)


(defmethod action/prep ::apply [action-spec]
  (let [template ^String (get-in action-spec [:params :template])]
    (-> action-spec
        (assoc-in [:params ::mapper] (ObjectMapper.))
        (assoc-in [:params ::expr] (Parser/compileString template)))))

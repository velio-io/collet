(ns collet.durable)


(defn value
  "Recursively convert a value into a caller-defined durable representation.

  `:extension?` identifies values handled by `:encode-extension`; all other
  supported values are ordinary recursive EDN. `:unsupported!` receives the
  failing path and value and is expected to throw. Set `:materialize-sequential?` for a
  representation that can materialize non-list lazy sequences as lists."
  ([item options]
   (value [] item options))

  ([path item options]
   (let [{:keys [extension?
                 encode-extension
                 unsupported!
                 materialize-sequential?]} options
         descend (fn [child-path child]
                   (value child-path child options))]
     (cond
       (and extension? (extension? item))
       (encode-extension path item)

       (or (nil? item)
           (boolean? item)
           (char? item)
           (string? item)
           (keyword? item)
           (symbol? item)
           (number? item))
       item

       (record? item)
       (unsupported! path item)

       (map? item)
       (reduce-kv
        (fn [result key child]
          (let [child-path  (conj path :key)
                durable-key (descend child-path key)]
            (if (contains? result durable-key)
              (unsupported! child-path key)
              (assoc result
                durable-key
                (descend (conj path key) child)))))
        {}
        item)

       (vector? item)
       (mapv (fn [index child]
               (descend (conj path index) child))
             (range)
             item)

       (list? item)
       (apply list
              (map-indexed (fn [index child]
                             (descend (conj path index) child))
                           item))

       (and materialize-sequential? (sequential? item))
       (apply list
              (map-indexed (fn [index child]
                             (descend (conj path index) child))
                           item))

       (set? item)
       (reduce (fn [result member]
                 (let [child-path     (conj path :member)
                       durable-member (descend child-path member)]
                   (if (contains? result durable-member)
                     (unsupported! child-path member)
                     (conj result durable-member))))
               #{}
               item)

       :else
       (unsupported! path item)))))

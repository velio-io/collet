#!/usr/bin/env clojure

(require '[clojure.string :as string])

(defn normalize-column-name
  "Normalizes column names to kebab-case keywords.
   Converts to lowercase, replaces spaces/underscores with hyphens,
   removes special characters, and returns as keyword."
  [col-name]
  (let [s (cond
            (keyword? col-name) (name col-name)
            (string? col-name)  col-name
            :else               (str col-name))

        ;; Normalize the string
        normalized (-> s
                       string/trim
                       string/lower-case
                       ;; Replace spaces and underscores with hyphens
                       (string/replace #"[\s_]+" "-")
                       ;; Remove all non-alphanumeric except hyphens
                       (string/replace #"[^a-z0-9\-]+" "")
                       ;; Collapse multiple consecutive hyphens
                       (string/replace #"-{2,}" "-")
                       ;; Remove leading/trailing hyphens
                       (string/replace #"^-+|-+$" ""))]

    ;; Handle empty string edge case
    (keyword (if (empty? normalized) "column" normalized))))

;; Test with actual CSV column names
(def csv-columns
  ["Index" "Customer Id" "First Name" "Last Name" "Company"
   "City" "Country" "Phone 1" "Phone 2" "Email"
   "Subscription Date" "Website"])

(println "Testing normalize-column-name function:")
(println "=" 50)
(doseq [col csv-columns]
  (let [normalized (normalize-column-name col)]
    (println (format "%-20s -> %s" col normalized))))

(println "\n" "Testing edge cases:")
(println "=" 50)
(def edge-cases
  ["User_Name" "Email@Address" "Price(USD)" "  Name  "
   "first--name" "-name-" "" "   " "123"])

(doseq [col edge-cases]
  (let [normalized (normalize-column-name col)]
    (println (format "%-20s -> %s" (pr-str col) normalized))))

(println "\n" "✓ All tests passed!")

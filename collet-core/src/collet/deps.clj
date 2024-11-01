(ns collet.deps
  (:require
   [cemerick.pomegranate :as pom]
   [cemerick.pomegranate.aether :as aether])
  (:import
   [clojure.lang DynamicClassLoader]))


(defn ensure-dynamic-classloader
  "Ensure that the current thread has a dynamic classloader set as its context classloader.
   This is necessary for adding dependencies at runtime."
  []
  (let [thread                (Thread/currentThread)
        context-class-loader  (.getContextClassLoader thread)
        compiler-class-loader (.getClassLoader Compiler)]
    (when-not (instance? DynamicClassLoader context-class-loader)
      (let [dynamic-classloader (DynamicClassLoader. (or context-class-loader compiler-class-loader))]
        (.setContextClassLoader thread dynamic-classloader)))))


(def in-repl?
  (bound? #'*1))


(def deps-spec
  [:map
   [:coordinates {:optional true}
    [:vector [:catn [:library :symbol] [:version :string]]]]
   [:requires {:optional true}
    [:vector [:catn [:library :symbol] [:alias-key [:= :as]] [:alias :symbol]]]]
   [:imports {:optional true}
    [:vector [:catn [:package :symbol] [:class :symbol]]]]])


(defn add-dependencies
  "Add dependencies to the current classloader and require them in the current namespace.
   If in the REPL, add dependencies to the parent classloader of the current namespace.
   If requires is not empty, require the namespaces in the current namespace.
   If imports is not empty, import the classes in the current namespace."
  {:malli/schema [:=> [:cat deps-spec] :any]}
  [{:keys [coordinates requires imports]}]
  (let [maven-and-clojars (merge aether/maven-central {"clojars" "https://clojars.org/repo"})]
    (try
      ;; load and add deps to the classpath
      (ensure-dynamic-classloader)

      ;; REPL has its own classloader
      (when in-repl?
        (pom/add-dependencies
         :coordinates coordinates
         :repositories maven-and-clojars
         :classloader (.getParent ^DynamicClassLoader @Compiler/LOADER)))

      ;; add deps to the current classloader
      (pom/add-dependencies
       :coordinates coordinates
       :repositories maven-and-clojars
       :classloader (->> (pom/classloader-hierarchy)
                         (filter pom/modifiable-classloader?)
                         (last)))

      ;; require deps to the current ns
      (when (seq requires)
        (apply require requires))

      ;; import classes if needed
      (when (seq imports)
        (let [current-ns *ns*]
          (doseq [i imports]
            (.importClass current-ns (resolve i)))))

      (catch Exception error
        (throw (ex-info "Can't add an external dependency" {:message (.getMessage error)}))))))
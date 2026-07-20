(ns collet.workspace
  "Resolve Collet's independently versioned Kmono package workspace."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [k16.kmono.core.graph :as kmono.graph]
   [k16.kmono.version :as kmono.version]
   [k16.kmono.version.alg.conventional-commits :as conventional]
   [k16.kmono.workspace :as kmono.workspace]))

(def bootstrap-version "0.2.8")

(def ^:private default-artifact
  {:public-namespaces []
   :publish? true
   :kind :library})

(defn- fail! [message data]
  (throw (ex-info message data)))

(defn- read-project [root]
  (-> (io/file root "deps.edn") slurp edn/read-string :collet/project))

(defn- validate-artifact! [{:keys [fqn artifact]}]
  (let [{:keys [description kind main outputs]} artifact]
    (when-not (and (string? description) (not (str/blank? description)))
      (fail! "Package artifact description is required" {:package fqn}))
    (when-not (#{:library :uberjar :distribution} kind)
      (fail! "Unknown package artifact kind" {:package fqn :kind kind}))
    (when (and (#{:uberjar :distribution} kind)
               (not (and main (get outputs :uberjar))))
      (fail! "Executable package requires :main and :outputs/:uberjar"
             {:package fqn}))
    (when (and (= :distribution kind)
               (not (and (get outputs :archive)
                         (get outputs :root)
                         (seq (get outputs :files)))))
      (fail! "Distribution package outputs are incomplete" {:package fqn})))
  true)

(defn- attach-artifacts [packages]
  (into {}
        (map (fn [[fqn package]]
               (let [artifact (merge default-artifact
                                     (get-in package [:deps-edn :collet/artifact]))
                     package (assoc package
                                    :artifact artifact
                                    :publish? (:publish? artifact))]
                 (validate-artifact! package)
                 [fqn package])))
        packages))

(defn package-fqn
  "Resolve a short module name or fully-qualified package symbol."
  [packages module]
  (when module
    (or (when (and (symbol? module)
                   (namespace module)
                   (contains? packages module))
          module)
        (let [requested (name module)
              matches (->> (keys packages)
                           (filter #(= requested (name %)))
                           sort
                           vec)]
          (when-not (= 1 (count matches))
            (fail! "Unknown or ambiguous workspace package"
                   {:module module :matches matches}))
          (first matches)))))

(defn- release-order [packages selected]
  (let [selected-set (set selected)
        graph (kmono.graph/filter-by
               #(contains? selected-set (:fqn %))
               packages)]
    (vec (mapcat identity (or (kmono.graph/parallel-topo-sort graph) [])))))

(defn select-release-packages
  "Select changed packages related to `module` through dependency edges."
  [packages module]
  (let [releases (into #{}
                       (comp (filter (fn [[_ package]] (:release? package)))
                             (map first))
                       packages)
        selected
        (if-let [fqn (package-fqn packages module)]
          (let [closure (into #{fqn}
                              (concat (kmono.graph/query-dependencies packages fqn)
                                      (kmono.graph/query-dependents packages fqn)))]
            (set (filter closure releases)))
          releases)]
    (release-order packages selected)))

(defn- bootstrap-packages [packages]
  (into {}
        (map (fn [[fqn package]]
               [fqn (assoc package
                           :current-version bootstrap-version
                           :version bootstrap-version
                           :reason :bootstrap
                           :tag (str fqn "@" bootstrap-version)
                           :release? true)]))
        packages))

(defn- plan-versioned-packages [root config packages]
  (let [resolved (kmono.version/resolve-package-versions root packages)
        missing (->> resolved
                     (keep (fn [[fqn package]]
                             (when-not (:version package) fqn)))
                     sort
                     vec)]
    (if (= (count missing) (count resolved))
      (bootstrap-packages resolved)
      (do
        (when (seq missing)
          (fail! "Package version tags are incomplete"
                 {:packages missing}))
        (let [changed (kmono.version/resolve-package-changes
                       root
                       {:ignore-changes (:ignore-changes config)}
                       resolved)
              direct-levels (into {}
                                  (map (fn [[fqn package]]
                                         [fqn (conventional/version-fn package)]))
                                  changed)
              unversioned (->> changed
                               (keep (fn [[fqn package]]
                                       (when (and (seq (:commits package))
                                                  (nil? (get direct-levels fqn)))
                                         fqn)))
                               sort
                               vec)]
          (when (seq unversioned)
            (fail! "Meaningful package changes require a version bump"
                   {:packages unversioned}))
          (let [candidates (kmono.version/inc-package-versions
                            conventional/version-fn changed)]
            (into {}
                  (map (fn [[fqn package]]
                         (let [current (get-in resolved [fqn :version])
                               candidate (:version package)
                               release? (not= current candidate)
                               reason (cond
                                        (get direct-levels fqn)
                                        (get direct-levels fqn)

                                        release?
                                        :dependency

                                        :else
                                        :unchanged)]
                           [fqn (assoc package
                                       :current-version current
                                       :reason reason
                                       :tag (str fqn "@" candidate)
                                       :release? release?)])))
                  candidates)))))))

(defn resolve-release-plan!
  "Resolve package versions, changes, release reasons, and optional selection."
  ([dir] (resolve-release-plan! dir {}))
  ([dir {:keys [module]}]
   (let [{:keys [root config packages] :as context}
         (kmono.workspace/resolve-workspace-context! dir)
         project (read-project root)
         packages (->> packages
                       attach-artifacts
                       (plan-versioned-packages root config))]
     (assoc context
            :project project
            :packages packages
            :selected (select-release-packages packages module)))))

(defn resolve-pending-release-plan!
  "Resolve package-version tags pointing at HEAD as resumable release state."
  ([dir] (resolve-pending-release-plan! dir {}))
  ([dir {:keys [module]}]
   (let [{:keys [root packages] :as context}
         (kmono.workspace/resolve-workspace-context! dir)
         project (read-project root)
         packages (attach-artifacts packages)
         {:keys [exit out err]}
         (shell/sh "git" "-C" root "tag" "--points-at" "HEAD")]
     (when-not (zero? exit)
       (fail! "Cannot resolve package tags at HEAD" {:error err}))
     (let [head-tags (remove str/blank? (str/split-lines out))
           packages
           (into {}
                 (map (fn [[fqn package]]
                        (let [matches (->> head-tags
                                           (keep (fn [tag]
                                                   (when-let [version
                                                              (kmono.version/match-package-version-tag
                                                               tag fqn)]
                                                     {:tag tag :version version})))
                                           vec)]
                          (when (> (count matches) 1)
                            (fail! "Multiple package version tags point at HEAD"
                                   {:package fqn :tags (mapv :tag matches)}))
                          (if-let [{:keys [tag version]} (first matches)]
                            [fqn (assoc package
                                        :current-version version
                                        :version version
                                        :reason :resume
                                        :tag tag
                                        :release? true)]
                            [fqn (assoc package :release? false)]))))
                 packages)]
       (assoc context
              :project project
              :packages packages
              :selected (select-release-packages packages module))))))

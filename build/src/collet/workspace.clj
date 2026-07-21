(ns collet.workspace
  "Resolve Collet's independently versioned Kmono package workspace."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.set :as set]
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

(defn resolve-build-context!
  "Resolve artifact metadata with explicit versions, without consulting Git.

  This is the build-context entry point for Git-less source copies such as a
  Docker build context. Every discovered package must have an exact override
  so a generated POM can never guess an internal dependency version."
  [dir versions]
  (let [{:keys [root packages] :as context}
        (kmono.workspace/resolve-workspace-context! dir)
        packages (attach-artifacts packages)
        expected (set (keys packages))
        supplied (set (keys versions))
        invalid (->> versions
                     (keep (fn [[fqn version]]
                             (when-not (and (string? version)
                                            (not (str/blank? version)))
                               fqn)))
                     set)]
    (when (or (not= expected supplied) (seq invalid))
      (fail! "Complete package version overrides are required"
             {:missing (sort (set/difference expected supplied))
              :unknown (sort (set/difference supplied expected))
              :invalid (sort invalid)}))
    (assoc context
           :project (read-project root)
           :packages
           (into {}
                 (map (fn [[fqn package]]
                        (let [version (get versions fqn)]
                          [fqn (assoc package
                                      :current-version version
                                      :version version
                                      :reason :override
                                      :tag (str fqn "@" version)
                                      :release? false)])))
                 packages))))

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

(defn package-order
  "Return every package once in dependency-first order, or fail on a cycle."
  [packages]
  (let [order (vec (mapcat identity
                           (or (kmono.graph/parallel-topo-sort packages) [])))]
    (when-not (= (set (keys packages)) (set order))
      (fail! "Workspace package graph contains a cycle"
             {:packages (sort (keys packages)) :ordered order}))
    order))

(defn dependency-closure
  "Return all transitive workspace dependencies of `fqn`."
  [packages fqn]
  (set (kmono.graph/query-dependencies packages fqn)))

(defn dependent-closure
  "Return all transitive workspace dependents of `fqn`."
  [packages fqn]
  (set (kmono.graph/query-dependents packages fqn)))

(defn- release-order [packages selected]
  (let [selected-set (set selected)
        graph (kmono.graph/filter-by
               #(contains? selected-set (:fqn %))
               packages)]
    (package-order graph)))

(defn select-release-packages
  "Select changed packages related to `module` through dependency edges."
  [packages module]
  (let [releases (into #{}
                       (comp (filter (fn [[_ package]] (:release? package)))
                             (map first))
                       packages)
        initial
        (if-let [fqn (package-fqn packages module)]
          (let [closure (into #{fqn}
                              (concat (dependency-closure packages fqn)
                                      (dependent-closure packages fqn)))]
            (set (filter closure releases)))
          releases)
        selected
        (loop [selected initial]
          (let [neighbors (into selected
                                (comp
                                 (mapcat (fn [fqn]
                                           (concat (get-in packages [fqn :depends-on])
                                                   (get-in packages [fqn :dependents]))))
                                 (filter releases))
                                selected)]
            (if (= selected neighbors)
              selected
              (recur neighbors))))]
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

(defn- changed-paths
  "Return every old and new path touched by a commit.

  Kmono remains responsible for finding package commits. This Git query is
  limited to ignore-path classification because Kmono 4.12.3 drops deleted
  paths and the old side of renames while applying `:ignore-changes`."
  [root sha]
  (let [{:keys [exit out err]}
        (shell/sh "git" "-C" root
                  "diff-tree" "--root" "--no-commit-id" "--name-status"
                  "-r" "-M" "-m" "-z" "--first-parent" sha)]
    (when-not (zero? exit)
      (fail! "Cannot inspect changed paths for package commit"
             {:commit sha :error (str/trim err)}))
    (loop [tokens (vec (butlast (str/split out #"\u0000" -1)))
           paths []]
      (if (empty? tokens)
        paths
        (let [status (first tokens)
              path-count (if (re-matches #"[RC][0-9]+" status) 2 1)
              remaining (subvec tokens 1)]
          (when (< (count remaining) path-count)
            (fail! "Git returned malformed changed-path data"
                   {:commit sha :status status}))
          (recur (subvec remaining path-count)
                 (into paths (subvec remaining 0 path-count))))))))

(defn- package-relative-path [package path]
  (let [package-path (str/replace (:relative-path package) #"/$" "")
        prefix (str package-path "/")]
    (cond
      (= package-path path) ""
      (str/starts-with? path prefix) (subs path (count prefix))
      :else nil)))

(defn- ignored-path? [patterns path]
  (boolean (some #(re-find (re-pattern %) path) patterns)))

(defn- filter-ignored-commits
  [root config original-packages changed-packages]
  (let [paths-by-sha (memoize #(changed-paths root %))]
    (into {}
          (map
           (fn [[fqn changed-package]]
             (let [original-package (get original-packages fqn)
                   patterns (or (:ignore-changes original-package)
                                (:ignore-changes config))
                   meaningful?
                   (fn [{:keys [sha]}]
                     (or (empty? patterns)
                         (->> (paths-by-sha sha)
                              (keep #(package-relative-path original-package %))
                              (some #(not (ignored-path? patterns %)))
                              boolean)))
                   commits (filterv meaningful? (:commits changed-package))]
               [fqn (assoc (merge original-package changed-package)
                           :commits commits)]))
          changed-packages))))

(defn- resolve-meaningful-package-changes [root config packages]
  (let [unfiltered (into {}
                         (map (fn [[fqn package]]
                                [fqn (dissoc package :ignore-changes)]))
                         packages)
        changed (kmono.version/resolve-package-changes root unfiltered)]
    (filter-ignored-commits root config packages changed)))

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
        (let [changed (resolve-meaningful-package-changes
                       root config resolved)
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
            (fail! (str "Meaningful package changes require a release-producing "
                        "conventional commit. Use fix:, feat:, !, "
                        "BREAKING CHANGE:, or fix the squash PR title")
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
         packages (kmono.version/resolve-package-versions
                   root (attach-artifacts packages))
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
                            [fqn (assoc package
                                        :current-version (:version package)
                                        :reason :unchanged
                                        :release? false)]))))
                 packages)
           tagged (into #{}
                        (comp (filter (fn [[_ package]] (:release? package)))
                              (map first))
                        packages)
           required-dependents (into #{}
                                     (mapcat #(kmono.graph/query-dependents
                                               packages %))
                                     tagged)
           missing-dependents (set/difference required-dependents tagged)
           missing-versions (->> packages
                                 (keep (fn [[fqn package]]
                                         (when-not (:version package) fqn)))
                                 set)]
       (when (or (seq missing-dependents) (seq missing-versions))
         (fail! "Pending package tags are incomplete"
                {:tagged (sort tagged)
                 :missing-dependents (sort missing-dependents)
                 :missing-versions (sort missing-versions)}))
       (assoc context
              :project project
              :packages packages
              :selected (select-release-packages packages module))))))

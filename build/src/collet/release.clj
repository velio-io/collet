(ns collet.release
  "Small guarded release wrapper around Kmono and Kaven."
  (:require
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [collet.build :as build]
   [k16.kaven.deploy :as kaven.deploy]
   [k16.kmono.core.graph :as kmono.graph]
   [k16.kmono.git.tags :as git.tags]
   [k16.kmono.version :as kmono.version]))

(defn- fail! [message data]
  (throw (ex-info message data)))

(defn- publish? [package]
  (get-in package [:deps-edn :collet/artifact :publish?]))

(defn validate-preflight!
  [{:keys [branch status head remote-head]}]
  (when-not (= "main" branch)
    (fail! "Releases require the main branch" {:branch branch}))
  (when-not (str/blank? status)
    (fail! "Releases require a clean worktree" {:status status}))
  (when-not (= head remote-head)
    (fail! "Local main must equal origin/main" {:head head :remote-head remote-head}))
  head)

(defn- ordered [packages]
  (mapv packages (mapcat identity (kmono.graph/parallel-topo-sort packages))))

(defn format-plan [packages]
  (let [rows (into [["PACKAGE" "VERSION" "TAG" "PUBLICATION"]]
                   (map (fn [package]
                          [(str (:fqn package)) (:version package)
                           (kmono.version/create-package-version-tag package)
                           (if (publish? package) "Maven" "tag only")]))
                   (ordered packages))
        widths (apply mapv (fn [& cells] (apply max (map count cells))) rows)]
    (str/join "\n"
              (map #(str/join "  " (map (fn [width value]
                                           (format (str "%-" width "s") value))
                                         widths %))
                   rows))))

(defn- command! [opts & command]
  (let [{:keys [exit out err]} (apply shell/sh (concat command (mapcat identity opts)))]
    (when-not (str/blank? out) (print out))
    (when-not (str/blank? err) (binding [*out* *err*] (print err)))
    (when-not (zero? exit)
      (fail! "Command failed" {:command (vec command) :exit exit :error err}))
    (str/trim out)))

(defn- git-output [root & args]
  (apply command! {} "git" "-C" root args))

(defn- fetch-tags! [root]
  (command! {} "git" "-C" root "fetch" "origin" "--tags"))

(defn- production-preflight! [root]
  (validate-preflight!
   {:branch (git-output root "branch" "--show-current")
    :status (git-output root "status" "--porcelain")
    :head (git-output root "rev-parse" "HEAD")
    :remote-head (git-output root "rev-parse" "origin/main")}))

(defn- tag-exists? [root tag]
  (zero? (:exit (shell/sh "git" "-C" root "rev-parse" "--verify" "--quiet" tag))))

(defn- ensure-target-tags-absent! [root packages]
  (doseq [package (vals packages)
          :let [tag (kmono.version/create-package-version-tag package)]
          :when (tag-exists? root tag)]
    (fail! "Release target tag already exists" {:tag tag})))

(defn- quality-gate! [root task]
  (command! {:dir root} "bb" task))

(defn- deploy! [package {:keys [jar-file pom-file]}]
  (when-not (and jar-file pom-file)
    (fail! "Publishable package build did not produce JAR and POM"
           {:package (:fqn package)}))
  (kaven.deploy/deploy
   {:jar-path jar-file
    :pom-path pom-file
    :repository {:id "clojars" :url "https://repo.clojars.org/"}}))

(defn- create-tags! [root packages revision]
  (git.tags/create-tags
   root {:ref revision
         :tags (mapv kmono.version/create-package-version-tag (vals packages))}))

(defn- push-tags! [root packages]
  (apply command! {} "git" "-C" root "push" "--atomic" "origin"
         (map kmono.version/create-package-version-tag (vals packages))))

(defn- candidates [root]
  (let [packages (build/load-packages {:dir root :changes? true})]
    {:packages packages :selected (build/release-packages packages)}))

(defn plan
  "Print the changed-package plan without building or publishing."
  [{:keys [root] :or {root "."}}]
  (let [{:keys [selected] :as result} (candidates root)]
    (println (format-plan selected))
    result))

(defn release
  "Test, build, deploy, and tag every Kmono release candidate."
  [{:keys [root] :or {root "."}}]
  (fetch-tags! root)
  (let [revision (production-preflight! root)
        {:keys [packages selected]} (candidates root)]
    (println (format-plan selected))
    (if (empty? selected)
      (do (println "No packages require release.") {:tags [] :deployed []})
      (do
        (ensure-target-tags-absent! root selected)
        (quality-gate! root "test")
        (quality-gate! root "verify")
        (when-not (= revision (production-preflight! root))
          (fail! "Release source revision changed" {:revision revision}))
        (let [artifacts (:artifacts
                         (build/build {:dir root :packages packages :selected selected}))]
          (when-not (= revision (production-preflight! root))
            (fail! "Release source revision changed" {:revision revision}))
          (fetch-tags! root)
          (ensure-target-tags-absent! root selected)
          (doseq [package (filter publish? (ordered selected))]
            (deploy! package (get artifacts (:fqn package))))
          (create-tags! root selected revision)
          (push-tags! root selected)
          {:revision revision
           :tags (mapv kmono.version/create-package-version-tag (vals selected))
           :deployed (mapv :fqn (filter publish? (ordered selected)))})))))

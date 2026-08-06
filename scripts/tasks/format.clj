(ns tasks.format
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [tasks.cli :as task-cli]
   [zprint.main]))


(def fix-command
  {:task     "fmt:fix"
   :summary  "Format Clojure and EDN files with zprint."
   :usage    "bb fmt:fix [--file <path>] [--root <path>] [--help]"
   :spec     {:file {:desc "Format a single file."}
              :root {:desc         "Root directory to search when --file is omitted."
                     :default      "."
                     :default-desc "."}
              :help {:desc  "Show task usage."
                     :alias :h}}
   :order    [:file :root :help]
   :examples ["  bb fmt:fix"
              "  bb fmt:fix --file collet-core/src/collet/core.clj"]})


(def check-command
  {:task     "fmt:check"
   :summary  "Check formatting of Clojure and EDN files with zprint."
   :usage    "bb fmt:check [--file <path>] [--root <path>] [--help]"
   :spec     {:file {:desc "Check a single file."}
              :root {:desc         "Root directory to search when --file is omitted."
                     :default      "."
                     :default-desc "."}
              :help {:desc  "Show task usage."
                     :alias :h}}
   :order    [:file :root :help]
   :examples ["  bb fmt:check"
              "  bb fmt:check --file collet-core/src/collet/core.clj"]})


(def ^:private excluded-directories
  #{".clj-kondo" ".cpcache" "target" "tmp"})


(defn- target-files [args]
  (let [file-arg (:file args)
        root-arg (or (:root args) ".")
        exclude? (fn [path]
                   (some excluded-directories
                         (str/split path #"[\\/]+")))]
    (if file-arg
      [file-arg]
      (->> (fs/glob root-arg "**.{clj,cljc,cljs,edn}")
           (map str)
           (remove exclude?)
           sort))))


(defn fix! [args]
  (let [args (task-cli/parse-opts! fix-command args)]
    (apply zprint.main/-main
           "{:search-config? true}"
           "--list-formatted-summary-write"
           (target-files args))))


(defn check! [args]
  (let [args (task-cli/parse-opts! check-command args)]
    (apply zprint.main/-main
           "{:search-config? true}"
           "--list-formatted-summary-check"
           (target-files args))))

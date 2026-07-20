(ns versioning
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [rewrite-clj.zip :as z]))

(def ^:private version-pattern
  #"^(\d+)\.(\d+)\.(\d+)(-SNAPSHOT)?$")

(defn parse-version [value]
  (if-let [[_ major minor patch snapshot] (re-matches version-pattern value)]
    {:major (parse-long major)
     :minor (parse-long minor)
     :patch (parse-long patch)
     :snapshot? (some? snapshot)}
    (throw (ex-info "Version must be MAJOR.MINOR.PATCH or MAJOR.MINOR.PATCH-SNAPSHOT"
                    {:version value}))))

(defn release-version [snapshot-version]
  (let [{:keys [major minor patch snapshot?]} (parse-version snapshot-version)]
    (when-not snapshot?
      (throw (ex-info "Release requires a snapshot workspace version"
                      {:version snapshot-version})))
    (format "%d.%d.%d" major minor patch)))

(defn next-snapshot-version [release-version level]
  (let [{:keys [major minor patch snapshot?]} (parse-version release-version)]
    (when snapshot?
      (throw (ex-info "Next snapshot calculation requires a release version"
                      {:version release-version})))
    (let [[next-major next-minor next-patch]
          (case level
            :patch [major minor (inc patch)]
            :minor [major (inc minor) 0]
            :major [(inc major) 0 0]
            (throw (ex-info "Release level must be :patch, :minor, or :major"
                            {:level level})))]
      (format "%d.%d.%d-SNAPSHOT" next-major next-minor next-patch))))

(defn- map-value-loc [map-loc key]
  (loop [loc (z/down map-loc)]
    (cond
      (nil? loc) nil
      (= key (z/sexpr loc)) (z/right loc)
      :else (recur (some-> loc z/right z/right)))))

(defn- value-at-path [root-loc path]
  (reduce (fn [loc key]
            (or (map-value-loc loc key)
                (throw (ex-info "EDN path does not exist" {:path path}))))
          root-loc
          path))

(defn- replace-value [text path value]
  (let [root-loc (z/of-string text)
        value-loc (value-at-path root-loc path)]
    (z/root-string (z/replace value-loc value))))

(defn- read-edn-file [path]
  (edn/read-string (slurp path)))

(defn- graph-path [root]
  (str (fs/path root "build" "modules.edn")))

(defn- module-configs [graph]
  (let [modules (:modules graph)]
    (if (seq (:module-order graph))
      (let [ordered (:module-order graph)
            remaining (remove (set ordered) (keys modules))]
        (mapv modules (concat ordered remaining)))
      (vec (vals modules)))))

(defn- dependency-files [root graph]
  (mapv (fn [module]
          (let [path (str (fs/path root (:dir module) "deps.edn"))]
            {:path path
             :text (slurp path)
             :deps (read-edn-file path)}))
        (module-configs graph)))

(defn- internal-pins [files internal-libs]
  (mapcat (fn [{:keys [path deps]}]
            (for [[lib config] (:deps deps)
                  :when (contains? internal-libs lib)]
              {:path path
               :lib lib
               :version (:mvn/version config)}))
          files))

(defn- validate-internal-pins! [pins old-version]
  (doseq [{:keys [path lib version]} pins]
    (when-not (= old-version version)
      (throw (ex-info "Internal dependency version is stale"
                      {:path path
                       :lib lib
                       :expected old-version
                       :actual version})))))

(defn derive-version-update-plan [root new-version]
  (parse-version new-version)
  (let [graph-path (graph-path root)
        graph-text (slurp graph-path)
        graph (edn/read-string graph-text)
        old-version (:version graph)
        _ (parse-version old-version)
        modules (module-configs graph)
        internal-libs (set (map :lib modules))
        files (dependency-files root graph)
        pins (internal-pins files internal-libs)
        _ (validate-internal-pins! pins old-version)
        changes
        (vec (concat
              (when-not (= old-version new-version)
                [{:path graph-path
                  :before graph-text
                  :after (replace-value graph-text [:version] new-version)}])
              (keep (fn [{:keys [path text deps]}]
                      (let [updated-text
                            (reduce (fn [current-text [lib config]]
                                      (if (contains? internal-libs lib)
                                        (replace-value current-text
                                                       [:deps lib :mvn/version]
                                                       new-version)
                                        current-text))
                                    text
                                    (:deps deps))]
                        (when-not (= text updated-text)
                          {:path path
                           :before text
                           :after updated-text})))
                    files)))]
    (with-meta changes {::pins pins
                        ::old-version old-version})))

(defn validate-version-update-plan! [changes]
  (validate-internal-pins! (::pins (meta changes))
                           (::old-version (meta changes)))
  changes)

(defn plan-version-update [root new-version]
  (validate-version-update-plan!
   (derive-version-update-plan root new-version)))

(defn- validate-written-versions! [root new-version]
  (let [graph (read-edn-file (graph-path root))
        modules (module-configs graph)
        internal-libs (set (map :lib modules))
        pins (internal-pins (dependency-files root graph) internal-libs)]
    (when-not (= new-version (:version graph))
      (throw (ex-info "Workspace version was not written"
                      {:expected new-version
                       :actual (:version graph)})))
    (validate-internal-pins! pins new-version)))

(defn set-version!
  ([new-version] (set-version! "." new-version))
  ([root new-version]
   (let [changes (plan-version-update root new-version)]
     (doseq [{:keys [path after]} changes]
       (spit path after))
     (validate-written-versions! root new-version)
     changes)))

(defn version-command [args]
  (if (= 1 (count args))
    (let [changes (set-version! (first args))]
      (doseq [{:keys [path]} changes]
        (println path))
      changes)
    (throw (ex-info "Usage: bb version <version>" {:args args}))))

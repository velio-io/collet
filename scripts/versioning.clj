(ns versioning
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [rewrite-clj.zip :as z])
  (:import (java.nio.file CopyOption Files StandardCopyOption)
           (java.nio.file.attribute FileAttribute)))

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
                            (reduce (fn [current-text [lib _]]
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

(defn- transaction-state-path [root]
  (fs/path root "target" ".collet" "version-transaction.edn"))

(defn- sibling-temporary-file [path]
  (let [path (fs/path path)
        parent (fs/parent path)
        prefix (str "." (fs/file-name path) ".collet-")]
    (fs/create-dirs parent)
    (Files/createTempFile parent prefix ".tmp" (make-array FileAttribute 0))))

(defn- move-replacing! [source destination]
  (try
    (Files/move (fs/path source)
                (fs/path destination)
                (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                        StandardCopyOption/REPLACE_EXISTING]))
    (catch Exception error
      (if (= "java.nio.file.AtomicMoveNotSupportedException"
             (.getName (class error)))
        (Files/move (fs/path source)
                    (fs/path destination)
                    (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
        (throw error)))))

(defn- write-state! [root state]
  (let [path (transaction-state-path root)
        temporary (sibling-temporary-file path)]
    (try
      (spit (str temporary) (pr-str state))
      (let [written (edn/read-string (slurp (str temporary)))]
        (when-not (= state written)
          (throw (ex-info "Version transaction state did not round-trip"
                          {:path (str path)}))))
      (move-replacing! temporary path)
      state
      (finally
        (Files/deleteIfExists temporary)))))

(defn- read-state [root]
  (let [path (transaction-state-path root)]
    (when (fs/regular-file? path)
      (edn/read-string (slurp (str path))))))

(defn- delete-state! [root]
  (Files/deleteIfExists (transaction-state-path root)))

(defn- prepare-change! [{:keys [path after] :as change}]
  (let [temporary (sibling-temporary-file path)]
    (spit (str temporary) after)
    (when-not (= after (slurp (str temporary)))
      (throw (ex-info "Prepared version source differs from its update plan"
                      {:path path :temporary (str temporary)})))
    ;; Every coordinated source is EDN. Parsing the prepared sibling catches a
    ;; truncated or otherwise invalid write before any live file is replaced.
    (edn/read-string (slurp (str temporary)))
    (assoc change :temporary (str temporary))))

(defn- ensure-current-content! [{:keys [path before]}]
  (let [actual (slurp path)]
    (when-not (= before actual)
      (throw (ex-info "Version source changed during transaction"
                      {:path path :expected before :actual actual})))))

(defn- replace-source-file! [temporary path]
  (move-replacing! temporary path))

(defn- restore-source-file! [{:keys [path before after]}]
  (let [actual (slurp path)]
    (cond
      (= before actual)
      :already-restored

      (= after actual)
      (let [temporary (sibling-temporary-file path)]
        (try
          (spit (str temporary) before)
          (when-not (= before (slurp (str temporary)))
            (throw (ex-info "Prepared rollback source differs from journal"
                            {:path path :temporary (str temporary)})))
          (move-replacing! temporary path)
          :restored
          (finally
            (Files/deleteIfExists temporary))))

      :else
      (throw (ex-info "Version recovery refused to overwrite unrelated edits"
                      {:path path :expected-before before
                       :transaction-after after :actual actual})))))

(defn- cleanup-temporaries! [changes]
  (doseq [{:keys [temporary]} changes
          :when temporary]
    (Files/deleteIfExists (fs/path temporary))))

(defn- rollback-changes! [root state]
  (let [failures
        (reduce (fn [errors change]
                  (try
                    (restore-source-file! change)
                    errors
                    (catch Throwable error
                      (conj errors {:path (:path change)
                                    :message (ex-message error)}))))
                []
                (reverse (:changes state)))
        rolled-back (assoc state
                           :status (if (seq failures)
                                     :rollback-incomplete
                                     :rolled-back)
                           :rollback-failures failures)]
    (write-state! root rolled-back)
    rolled-back))

(defn- recover-pending-transaction! [root]
  (when-let [state (read-state root)]
    (let [recovered (rollback-changes! root state)]
      (when (seq (:rollback-failures recovered))
        (throw (ex-info "Version transaction recovery requires manual reconciliation"
                        {:state-path (str (transaction-state-path root))
                         :failures (:rollback-failures recovered)})))
      (cleanup-temporaries! (:changes recovered))
      (delete-state! root))))

(defn- apply-version-transaction! [root new-version changes]
  (let [prepared (mapv prepare-change! changes)
        state (atom {:status :prepared
                     :root (str (fs/absolutize root))
                     :new-version new-version
                     :changes prepared
                     :replaced []})]
    (try
      ;; Check the complete optimistic snapshot only after every sibling file
      ;; has been written and validated, but before replacing any live source.
      (doseq [change prepared]
        (ensure-current-content! change))
      (write-state! root @state)
      (doseq [{:keys [path temporary] :as change} prepared]
        ;; Recheck immediately before each move so a concurrent edit that lands
        ;; during the transaction is preserved and forces rollback.
        (ensure-current-content! change)
        (replace-source-file! temporary path)
        (swap! state (fn [current]
                       (-> current
                           (assoc :status :applying)
                           (update :replaced conj path))))
        (write-state! root @state))
      (validate-written-versions! root new-version)
      (cleanup-temporaries! prepared)
      (delete-state! root)
      changes
      (catch Throwable failure
        (let [failed (assoc @state
                            :status :failed
                            :failure (or (ex-message failure)
                                         (str (class failure))))]
          (write-state! root failed)
          (let [rolled-back (rollback-changes! root failed)]
            (throw (ex-info (or (ex-message failure)
                                "Version transaction failed")
                            (merge (or (ex-data failure) {})
                                   {:state-path (str (transaction-state-path root))
                                    :rollback-failures
                                    (:rollback-failures rolled-back)})
                            failure))))))))

(defn set-version!
  ([new-version] (set-version! "." new-version))
  ([root new-version]
   (recover-pending-transaction! root)
   (let [changes (plan-version-update root new-version)]
     (if (seq changes)
       (apply-version-transaction! root new-version changes)
       changes))))

(defn version-command [args]
  (if (= 1 (count args))
    (let [changes (set-version! (first args))]
      (doseq [{:keys [path]} changes]
        (println path))
      changes)
    (throw (ex-info "Usage: bb version <version>" {:args args}))))

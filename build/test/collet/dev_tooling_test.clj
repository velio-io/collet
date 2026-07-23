(ns collet.dev-tooling-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.net InetAddress ServerSocket]
   [java.nio.file Files]
   [java.util.concurrent TimeUnit]))


(def ^:private nrepl-client-script
  (.getAbsolutePath (io/file "scripts/agent/nrepl-eval.bb")))

(defn- root-deps []
  (-> "deps.edn"
      slurp
      edn/read-string))


(defn- reload-dirs []
  (->> (.listFiles (io/file "."))
       (filter #(.isDirectory %))
       (filter #(str/starts-with? (.getName %) "collet-"))
       (map #(io/file % "src"))
       (filter #(.isDirectory %))
       (map #(.getPath %))
       sort
       vec
       (#(conj % "test-fixtures/src"))))


(defn- run-nrepl-client
  [& args]
  (apply shell/sh "bb" nrepl-client-script args))


(defn- run-nrepl-client-in
  [dir & args]
  (apply shell/sh
         (concat ["bb" nrepl-client-script]
                 args
                 [:dir dir])))


(defn- delete-tree
  [root]
  (when (.exists (io/file root))
    (doseq [file (reverse (file-seq (io/file root)))]
      (io/delete-file file true))))


(defn- await-nrepl-port
  [process dir]
  (let [port-file (io/file dir ".nrepl-port")]
    (loop [attempts 200]
      (let [raw-port (when (.exists port-file)
                       (str/trim (slurp port-file)))]
        (cond
          (and raw-port (re-matches #"\d+" raw-port))
          (Integer/parseInt raw-port)

          (not (.isAlive process))
          (throw
           (ex-info "Ephemeral nREPL exited before writing .nrepl-port"
                    {:exit   (.exitValue process)
                     :output (slurp (.getInputStream process))}))

          (zero? attempts)
          (throw (ex-info "Timed out starting ephemeral nREPL" {:dir dir}))

          :else
          (do
            (Thread/sleep 50)
            (recur (dec attempts))))))))


(defn- with-ephemeral-nrepl
  [f]
  (let [dir     (str (Files/createTempDirectory
                      "collet-dev-tooling-test-"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        process (-> (ProcessBuilder.
                     ^java.util.List
                     ["clojure"
                      "-Sdeps"
                      "{:deps {nrepl/nrepl {:mvn/version \"1.7.0\"}}}"
                      "-M" "-m" "nrepl.cmdline"
                      "--bind" "127.0.0.1"])
                    (.directory (io/file dir))
                    (.redirectErrorStream true)
                    (.start))]
    (try
      (f {:dir dir :port (await-nrepl-port process dir)})
      (finally
        (.destroy process)
        (when-not (.waitFor process 5 TimeUnit/SECONDS)
          (.destroyForcibly process)
          (.waitFor process 5 TimeUnit/SECONDS))
        (delete-tree dir)))))


(defn- unused-loopback-port
  []
  (with-open [socket (ServerSocket. 0 1
                                    (InetAddress/getByName "127.0.0.1"))]
    (.getLocalPort socket)))


(deftest nrepl-port-waiter-rejects-transient-empty-content
  (let [dir       (str (Files/createTempDirectory
                        "collet-port-waiter-test-"
                        (make-array java.nio.file.attribute.FileAttribute 0)))
        port-file (io/file dir ".nrepl-port")
        process   (-> (ProcessBuilder.
                       ^java.util.List ["sh" "-c" "sleep 5"])
                      (.start))
        writer    (future
                    (Thread/sleep 100)
                    (spit port-file "54321"))]
    (try
      (spit port-file "")
      (is (= 54321 (await-nrepl-port process dir)))
      (finally
        (deref writer 1000 nil)
        (.destroyForcibly process)
        (.waitFor process 5 TimeUnit/SECONDS)
        (delete-tree dir)))))


(deftest root-repl-aliases-are-reproducible
  (let [deps (root-deps)]
    (testing "development dependencies and sources"
      (is (= ["dev"] (get-in deps [:aliases :dev :extra-paths])))
      (is (= {:local/root "test-fixtures"}
             (get-in deps
                     [:aliases :dev :extra-deps
                      'io.velio/collet-test-fixtures])))
      (is (= "1.0.0"
             (get-in deps
                     [:aliases :dev :extra-deps
                      'io.github.tonsky/clj-reload :mvn/version]))))
    (testing "loopback-only nREPL startup"
      (is (= "1.7.0"
             (get-in deps
                     [:aliases :nrepl :extra-deps
                      'nrepl/nrepl :mvn/version])))
      (is (= ["-m" "nrepl.cmdline"
              "--interactive" "--color" "--bind" "127.0.0.1"]
             (get-in deps [:aliases :nrepl :main-opts]))))))


(deftest root-dev-namespace-exposes-project-scoped-reload
  (let [expected    (reload-dirs)
        user-source (slurp "dev/user.clj")
        code        (str "(assert (= " (pr-str expected) " user/reload-dirs)) "
                      "(assert (= '([]) (:arglists (meta #'user/reload)))) "
                      "(println \"dev-repl-contract-ok\")")
        {:keys [exit out err]} (shell/sh "clojure" "-M:dev" "-e" code)]
    (is (zero? exit) (str out err))
    (is (str/includes? out "dev-repl-contract-ok") out)
    (is (str/includes? user-source ":no-reload '#{user}") user-source)
    (is (not (re-find #"\[collet\." user-source)) user-source)))


(deftest fallback-nrepl-client-has-a-stable-cli
  (testing "help"
    (let [{:keys [exit out err]} (run-nrepl-client "--help")]
      (is (zero? exit) err)
      (is (str/includes? out "nrepl-eval.bb --code <CODE>") out)))
  (testing "port discovery"
    (let [{:keys [exit out err]} (run-nrepl-client "--discover")]
      (is (zero? exit) err)
      (is (str/includes? out ".nrepl-port files:") out)
      (is (str/includes? out "java TCP listeners:") out)))
  (testing "evaluation and port-file discovery"
    (with-ephemeral-nrepl
      (fn [{:keys [dir port]}]
        (let [{:keys [exit out err]}
              (run-nrepl-client "--port" (str port) "--code" "(+ 1 2)")]
          (is (zero? exit) err)
          (is (= "3" (str/trim out)) out))
        (let [{:keys [exit out err]}
              (run-nrepl-client-in dir "--code" "(* 6 7)")]
          (is (zero? exit) err)
          (is (= "42" (str/trim out)) out))
        (let [{:keys [exit err]} (shell/sh "git" "init" :dir dir)
              nested-dir         (io/file dir "nested")]
          (is (zero? exit) err)
          (is (.mkdir nested-dir))
          (let [{:keys [exit out err]}
                (run-nrepl-client-in
                 (str nested-dir)
                 "--code" "(+ 20 22)")]
            (is (zero? exit) err)
            (is (= "42" (str/trim out)) out)))
        (let [{:keys [exit err]}
              (run-nrepl-client
               "--port" (str port)
               "--code" "(throw (ex-info \"expected\" {}))")]
          (is (= 1 exit) err)
          (is (str/includes? err "ex:") err))
        (let [started-at (System/nanoTime)
              {:keys [exit err]}
              (run-nrepl-client
               "--port" (str port)
               "--timeout" "1"
               "--code"
               (str "(do (Thread/sleep 800) (println \"progress\") "
                    "(Thread/sleep 800) :done)"))
              elapsed-ms (/ (- (System/nanoTime) started-at) 1000000.0)]
          (is (= 4 exit) err)
          (is (str/includes? err "timed out") err)
          (is (< elapsed-ms 1500)
              (str "client exceeded its overall deadline: "
                   elapsed-ms "ms"))))))
  (testing "connection errors"
    (let [{:keys [exit err]}
          (run-nrepl-client
           "--port" (str (unused-loopback-port))
           "--code" "(+ 1 2)")]
      (is (= 3 exit) err)
      (is (str/includes? err "cannot connect") err)))
  (testing "usage errors"
    (is (= 64 (:exit (run-nrepl-client))))
    (is (= 64 (:exit (run-nrepl-client
                      "--port" "0"
                      "--code" "(+ 1 2)"))))
    (is (= 64 (:exit (run-nrepl-client
                      "--port" "1"
                      "--timeout" "0"
                      "--code" "(+ 1 2)"))))
    (is (= 64 (:exit (run-nrepl-client
                      "--port" "1"
                      "--timeout" "-1"
                      "--code" "(+ 1 2)"))))))

#!/usr/bin/env bb
;; Minimal nREPL eval client for Collet agents.
;;
;; Usage:
;;   nrepl-eval.bb --code <CODE> [--port <INT>] [--timeout <SECONDS>]
;;   nrepl-eval.bb --discover
;;   nrepl-eval.bb --help
;;
;; Primary tool is clj-nrepl-eval; this is the durable repository fallback
;; when it is not installed on PATH.
;;
;; Exit codes: 0 ok, 1 REPL :ex, 3 connect error, 4 timeout, 64 bad args.

(require '[babashka.cli :as cli]
         '[babashka.process :as proc]
         '[bencode.core :as bencode])

(import '[java.io BufferedOutputStream IOException PushbackInputStream]
        '[java.net ConnectException Socket SocketTimeoutException])


(def cli-spec
  {:spec     {:code     {:desc "Clojure form to eval"}
              :port     {:desc "nREPL port" :coerce :int}
              :timeout  {:desc "Seconds to wait for completion"
                         :coerce :int
                         :default 30}
              :discover {:desc "Print port candidates and exit"
                         :coerce :boolean}
              :help     {:desc "Show usage" :coerce :boolean :alias :h}}
   :restrict [:code :port :timeout :discover :help]})


(def usage
  (str "Usage:\n"
       "  nrepl-eval.bb --code <CODE> [--port <INT>] [--timeout <SECONDS>]\n"
       "  nrepl-eval.bb --discover\n"
       "  nrepl-eval.bb --help\n\n"
       "Examples:\n"
       "  bb scripts/agent/nrepl-eval.bb --port 56124 --code \"(+ 1 2)\"\n"
       "  bb scripts/agent/nrepl-eval.bb --discover\n\n"
       "Exit codes:\n"
       "  0 success\n"
       "  1 nREPL evaluation error\n"
       "  3 connection error\n"
       "  4 timeout\n"
       "  64 usage error\n\n"
       "Primary tool is clj-nrepl-eval; this is the durable fallback."))


(defn die
  [code & message]
  (binding [*out* *err*]
    (apply println message))
  (System/exit code))


(defn bytes->strings
  [value]
  (cond
    (bytes? value)      (String. ^bytes value "UTF-8")
    (sequential? value) (mapv bytes->strings value)
    (map? value)        (into {}
                              (for [[key item] value]
                                [(bytes->strings key)
                                 (bytes->strings item)]))
    :else               value))


(defn read-port-file
  [path]
  (when (and path (.exists (io/file path)))
    (try
      (let [raw (str/trim (slurp path))]
        (when (re-matches #"\d+" raw)
          (Integer/parseInt raw)))
      (catch Exception _
        nil))))


(defn git-toplevel
  [cwd]
  (try
    (let [{:keys [exit out]}
          (proc/sh {:dir cwd :err :string}
                   "git" "rev-parse" "--show-toplevel")]
      (when (zero? exit)
        (str/trim out)))
    (catch Exception _
      nil)))


(defn java-listeners
  []
  (try
    (let [{:keys [exit out]}
          (proc/sh
           {:err :string}
           "sh" "-c"
           (str "lsof -nP -iTCP -sTCP:LISTEN 2>/dev/null "
                "| awk '/^java/ { print $9 }'"))]
      (when (zero? exit)
        (->> (str/split-lines out)
             (map str/trim)
             (remove str/blank?)
             distinct
             sort)))
    (catch Exception _
      nil)))


(defn discover-candidates
  []
  (let [cwd       (System/getProperty "user.dir")
        cwd-file  (str cwd "/.nrepl-port")
        root      (git-toplevel cwd)
        root-file (when root (str root "/.nrepl-port"))
        cwd-port  (read-port-file cwd-file)
        root-port (when (and root-file (not= root-file cwd-file))
                    (read-port-file root-file))]
    {:cwd-file   cwd-file
     :cwd-port   cwd-port
     :root-file  root-file
     :root-port  root-port
     :java-listeners (java-listeners)}))


(defn pick-port
  []
  (let [{:keys [cwd-port root-port]} (discover-candidates)]
    (or cwd-port root-port)))


(defn remaining-timeout-ms
  [deadline-ns]
  (let [remaining-ns (- deadline-ns (System/nanoTime))]
    (when-not (pos? remaining-ns)
      (die 4 "nrepl-eval: timed out waiting for response"))
    (-> (+ remaining-ns 999999)
        (quot 1000000)
        (max 1)
        (min Integer/MAX_VALUE)
        int)))


(defn open-socket
  [port deadline-ns]
  (when (or (not (integer? port)) (< port 1) (> port 65535))
    (die 64 (str "nrepl-eval: port out of range (1-65535): " port)))
  (try
    (doto (Socket.)
      (.connect (java.net.InetSocketAddress. "127.0.0.1" (int port))
                (remaining-timeout-ms deadline-ns)))
    (catch ConnectException error
      (die 3 (str "nrepl-eval: cannot connect to 127.0.0.1:"
                  port " - " (.getMessage error))))
    (catch SocketTimeoutException _
      (die 3 (str "nrepl-eval: connect timeout to 127.0.0.1:" port)))
    (catch IOException error
      (die 3 (str "nrepl-eval: I/O error on 127.0.0.1:"
                  port " - " (.getMessage error))))))


(defn send!
  [out message]
  (bencode/write-bencode out message)
  (.flush ^java.io.OutputStream out))


(defn read-until-done
  [socket in deadline-ns]
  (loop [acc {:value [] :out [] :err [] :ex nil}]
    (.setSoTimeout socket (remaining-timeout-ms deadline-ns))
    (let [raw (try
                (bencode/read-bencode in)
                (catch SocketTimeoutException _
                  ::timeout))]
      (cond
        (= raw ::timeout)
        (die 4 "nrepl-eval: timed out waiting for response")

        (nil? raw)
        (die 1 "nrepl-eval: nREPL closed connection before :done")

        :else
        (let [message (bytes->strings raw)
              status  (set (get message "status"))
              acc'    (cond-> acc
                        (get message "value")
                        (update :value conj (get message "value"))

                        (get message "out")
                        (update :out conj (get message "out"))

                        (get message "err")
                        (update :err conj (get message "err"))

                        (get message "ex")
                        (assoc :ex (get message "ex")))]
          (if (contains? status "done")
            acc'
            (recur acc')))))))


(defn eval-code
  [port code timeout-seconds]
  (when (or (not (integer? timeout-seconds))
            (not (pos? timeout-seconds)))
    (die 64
         (str "nrepl-eval: timeout must be a positive number of seconds: "
              timeout-seconds)))
  (when (> timeout-seconds 2147483)
    (die 64
         (str "nrepl-eval: timeout exceeds the supported maximum: "
              timeout-seconds)))
  (let [deadline (+ (System/nanoTime)
                    (* 1000000000 (long timeout-seconds)))
        socket   (open-socket port deadline)
        out        (BufferedOutputStream. (.getOutputStream socket))
        in         (PushbackInputStream. (.getInputStream socket))]
    (send! out {"op" "eval" "code" code})
    (let [{:keys [value out err ex]} (read-until-done socket in deadline)]
      (when (seq out)
        (print (apply str out))
        (flush))
      (when (seq err)
        (binding [*out* *err*]
          (print (apply str err))
          (flush)))
      (when ex
        (binding [*out* *err*]
          (println (str "ex: " ex))))
      (doseq [item value]
        (println item))
      (System/exit (if ex 1 0)))))


(defn print-discovery
  []
  (let [{:keys [cwd-file cwd-port root-file root-port java-listeners]}
        (discover-candidates)]
    (println ".nrepl-port files:")
    (if (or cwd-port root-port)
      (do
        (when cwd-port
          (println " " cwd-file "->" cwd-port))
        (when root-port
          (println " " root-file "->" root-port)))
      (println "  none"))
    (println "java TCP listeners:")
    (if (seq java-listeners)
      (doseq [endpoint java-listeners]
        (println " " endpoint))
      (println "  none"))
    (System/exit 0)))


(defn -main
  [& argv]
  (let [{:keys [code port timeout discover help]}
        (try
          (cli/parse-opts argv cli-spec)
          (catch Exception error
            (binding [*out* *err*]
              (println "nrepl-eval:" (.getMessage error))
              (println usage))
            (System/exit 64)))]
    (cond
      help
      (do
        (println usage)
        (System/exit 0))

      discover
      (print-discovery)

      (nil? code)
      (do
        (binding [*out* *err*]
          (println "nrepl-eval: --code is required")
          (println usage))
        (System/exit 64))

      :else
      (let [selected-port (or port (pick-port))]
        (when (nil? selected-port)
          (die 64
               "nrepl-eval: specify --port or run from a worktree with .nrepl-port"))
        (eval-code selected-port code (or timeout 30))))))


(apply -main *command-line-args*)

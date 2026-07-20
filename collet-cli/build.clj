(ns build (:require [collet.build :as shared]))
(def module :collet-cli)
(defn clean [opts] (shared/clean module opts))
(defn pom [opts] (shared/pom module opts))
(defn jar [opts] (shared/jar module opts))
(defn uberjar [opts] (shared/uberjar module opts))
(defn dist [opts]
  (shared/uberjar module opts)
  (shared/distribution module opts))

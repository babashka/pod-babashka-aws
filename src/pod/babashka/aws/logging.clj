(ns pod.babashka.aws.logging
  (:require [clojure.string :as str]))

(defn set-jul-level!
  "Sets the log level of a java.util.logging Logger.

  Sets a java.util.logging Logger to the java.util.logging.Level with
  the name `level`.

  Sets the level of the Logger with name `logger-name` if
  given. Otherwise sets the level of the global Logger."
  ([level]
   (set-jul-level! "" level))
  ([logger-name level]
   (some-> (.getLogger (java.util.logging.LogManager/getLogManager) logger-name)
           (.setLevel (java.util.logging.Level/parse level)))))

(def jul-level
  {:trace "FINEST"
   :debug "FINE"
   :info  "INFO"
   :warn  "WARNING"
   :error "SEVERE"
   :fatal "SEVERE"})

(defn set-level! [s]
  (let [k (keyword (str/lower-case (name s)))
        level (get jul-level k (str s))]
    (set-jul-level! level)))

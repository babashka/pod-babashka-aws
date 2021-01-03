(ns pod.babashka.aws
  (:refer-clojure :exclude [read read-string hash])
  (:require [bencode.core :as bencode]
            [clojure.java.io :as io]
            [clojure.spec.alpha]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [cognitect.transit :as transit]
            [pod.babashka.aws.impl.aws :as aws])
  (:import [java.io PushbackInputStream])
  (:gen-class))

(set! *warn-on-reflection* true)

(def stdin (PushbackInputStream. System/in))
(def stdout System/out)

(def debug? false)

(defn debug [& strs]
  (when debug?
    (binding [*out* (io/writer System/err)]
      (apply prn strs))))

(defn write
  ([v] (write stdout v))
  ([stream v]
   (debug :writing v)
   (bencode/write-bencode stream v)
   (flush)))

(defn read-string [^"[B" v]
  (String. v))

(defn read [stream]
  (bencode/read-bencode stream))

(def lookup*
  {'pod.babashka.aws
   {'client aws/client
    'doc aws/doc
    'invoke aws/invoke
    'ops aws/ops
    }})

(defn lookup [var]
  (let [var-ns (symbol (namespace var))
        var-name (symbol (name var))]
    (get-in lookup* [var-ns var-name])))

(def describe-map
  (walk/postwalk
   (fn [v]
     (if (ident? v) (name v)
         v))
   `{:format :transit+json
     :namespaces [{:name pod.babashka.aws
                   :vars ~(mapv (fn [[k _]]
                                  {:name k})
                                (get lookup* 'pod.babashka.aws))}
                  ]}))

(defn read-transit [^String v]
  (transit/read
   (transit/reader
    (java.io.ByteArrayInputStream. (.getBytes v "utf-8"))
    :json)))

(defn write-transit [v]
  (let [baos (java.io.ByteArrayOutputStream.)]
    (transit/write (transit/writer baos :json) v)
    (.toString baos "utf-8")))

(defn -main [& _args]
  (loop []
    (let [message (try (read stdin)
                       (catch java.io.EOFException _
                         ::EOF))]
      (when-not (identical? ::EOF message)
        (let [op (get message "op")
              op (read-string op)
              op (keyword op)
              id (some-> (get message "id")
                         read-string)
              id (or id "unknown")]
          (case op
            :describe (do (write stdout describe-map)
                          (recur))
            :invoke (do (try
                          (let [var (-> (get message "var")
                                        read-string
                                        symbol)
                                args (get message "args")
                                args (read-string args)
                                args (read-transit args)]
                            (if-let [f (lookup var)]
                              (let [out-str (java.io.StringWriter.)
                                    value (binding [*out* out-str]
                                            (write-transit (apply f args)))
                                    out-str (str out-str)
                                    reply (cond-> {"value" value
                                                   "id" id
                                                   "status" ["done"]}
                                            (not (str/blank? out-str))
                                            (assoc "out" out-str))]
                                (write stdout reply))
                              (throw (ex-info (str "Var not found: " var) {}))))
                          (catch Throwable e
                            (debug e)
                            (let [reply {"ex-message" (ex-message e)
                                         "ex-data" (write-transit
                                                    (assoc (ex-data e)
                                                           :type (str (class e))))
                                         "id" id
                                         "status" ["done" "error"]}]
                              (write stdout reply))))
                        (recur))
            :shutdown (System/exit 0)
            (do
              (let [reply {"ex-message" "Unknown op"
                           "ex-data" (pr-str {:op op})
                           "id" id
                           "status" ["done" "error"]}]
                (write stdout reply))
              (recur))))))))

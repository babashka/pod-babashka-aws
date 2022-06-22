(ns pod.babashka.aws
  (:refer-clojure :exclude [read read-string hash])
  (:require [bencode.core :as bencode]
            [clojure.java.io :as io]
            [clojure.spec.alpha]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [cognitect.aws.config :as aws.config]
            [cognitect.transit :as transit]
            [pod.babashka.aws.impl.aws :as aws]
            [pod.babashka.aws.impl.aws.credentials :as credentials]
            [pod.babashka.aws.logging :as logging])
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

(logging/set-level! :warn)

(def lookup*
  {'pod.babashka.aws.credentials credentials/lookup-map
   'pod.babashka.aws.config
   {'parse aws.config/parse}
   'pod.babashka.aws
   {'-client aws/-client
    '-doc-str aws/-doc-str
    '-invoke aws/-invoke
    'ops aws/ops}
   'pod.babashka.aws.logging
   {'set-level! logging/set-level!}})

(defn lookup [var]
  (let [var-ns (symbol (namespace var))
        var-name (symbol (name var))]
    (get-in lookup* [var-ns var-name])))

(def invoke
  '(do (require (quote clojure.java.io) (quote clojure.walk))
       (defn invoke [client op]
         (let [op (clojure.walk/postwalk
                   (fn [x]
                     (cond
                       (instance? java.io.InputStream x)
                       (let [os (java.io.ByteArrayOutputStream.)]
                         (clojure.java.io/copy x os)
                         (.toByteArray os))
                       (instance? java.io.File x)
                       {:pod.babashka.aws/wrapped [:file (.getPath ^java.io.File x)]}
                       :else x))
                   op)
               response (-invoke client op)]
           (clojure.walk/postwalk
            (fn [x]
              (if-let [[t y] (:pod.babashka.aws/wrapped x)]
                (case t
                  :bytes (clojure.java.io/input-stream y))
                x))
            response)))))

(def throwable-key (str `throwable))

(def throwable-write-handler
  (transit/write-handler throwable-key Throwable->map))

(def class-key (str `class))

(def class-write-handler
  (transit/write-handler class-key (fn [^Class x] (.getName x))))

(def transit-writers-map
  {java.lang.Throwable throwable-write-handler
   java.lang.Class class-write-handler})

(def whm (transit/write-handler-map transit-writers-map))

(defn write-transit [v]
  (let [baos (java.io.ByteArrayOutputStream.)]
    (try (transit/write (transit/writer baos :json {:handlers whm}) v)
         (catch Exception e
           (binding [*out* *err*]
             (prn "ERROR: can't serialize to transit:" v))
           (throw e)))
    (.toString baos "utf-8")))

(defn read-transit [^String v]
  (transit/read
   (transit/reader
    (java.io.ByteArrayInputStream. (.getBytes v "utf-8"))
    :json)))

(def reg-transit-handlers
  (format "
(require 'babashka.pods)
(babashka.pods/add-transit-read-handler!
    \"%s\"
    identity)

(babashka.pods/add-transit-read-handler!
    \"%s\"
    (fn [s] {:class (symbol s)}))"
          throwable-key class-key))

(def describe-map
  (walk/postwalk
   (fn [v]
     (if (ident? v) (name v)
         v))
   `{:format :transit+json
     :namespaces
     [~credentials/describe-map
      {:name pod.babashka.aws.config
       :vars ~(mapv (fn [[k _]]
                     {:name k})
                   (get lookup* 'pod.babashka.aws.config))}
      {:name pod.babashka.aws
       :vars ~(conj (mapv (fn [[k _]]
                            {:name k})
                          (get lookup* 'pod.babashka.aws))
                    {:name "client"
                     :code
                     (pr-str
                      '(do
                         (require '[pod.babashka.aws.credentials :as credentials])
                         (defn client [{:keys [credentials-provider] :as config}]
                           (let [credentials-provider (or credentials-provider
                                                          (credentials/default-credentials-provider))
                                 credentials-provider (if (satisfies? credentials/CredentialsProvider credentials-provider)
                                                        (credentials/-identity-credentials-provider (credentials/fetch credentials-provider))
                                                        credentials-provider)]
                             (-client (assoc config :credentials-provider credentials-provider))))))}
                    {:name "doc"
                     :code (pr-str '(defn doc [client op]
                                      (println (-doc-str client op))))}
                    {:name "invoke"
                     :code
                     (pr-str invoke)}
                    {:name "-reg-transit-handlers"
                     :code reg-transit-handlers})}
      {:name pod.babashka.aws.logging
       :vars ~(mapv (fn [[k _]]
                      {:name k})
                    (get lookup* 'pod.babashka.aws.logging))}]}))

(def musl?
  "Captured at compile time, to know if we are running inside a
  statically compiled executable with musl."
  (and (= "true" (System/getenv "BABASHKA_STATIC"))
       (= "true" (System/getenv "BABASHKA_MUSL"))))

(defmacro run [expr]
  (if musl?
    ;; When running in musl-compiled static executable we lift execution of bb
    ;; inside a thread, so we have a larger than default stack size, set by an
    ;; argument to the linker. See https://github.com/oracle/graal/issues/3398
    `(let [v# (volatile! nil)
           f# (fn []
                (vreset! v# ~expr))]
       (doto (Thread. nil f# "main")
         (.start)
         (.join))
       @v#)
    `(do ~expr)))

(defn -main [& _args]
  (run
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
                                              (let [v (apply f args)]
                                                (write-transit v)))
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
                (recur)))))))))

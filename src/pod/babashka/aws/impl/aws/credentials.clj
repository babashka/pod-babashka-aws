(ns pod.babashka.aws.impl.aws.credentials
  (:require
   [clojure.edn]
   [clojure.java.io :as io]
   [cognitect.aws.client.api :as aws]
   [cognitect.aws.credentials :as creds]
   [pod.babashka.aws.impl.aws]))

;;; Pod Backend

 (def *providers (atom {}))

(defn create-provider [provider]
  (let [provider-id (java.util.UUID/randomUUID)]
    (swap! *providers assoc provider-id provider)
    {:provider-id provider-id}))

(defmacro with-system-properties [props & body]
  `(let [props# (System/getProperties)]
     (try
       (doseq [[k# v#] ~props]
         (System/setProperty k# v#))
       ~@body
       (finally
         (System/setProperties props#)))))

(defn get-provider [config]
  (get @*providers (get config :provider-id)))

(defn -basic-credentials-provider [conf]
  (create-provider (creds/basic-credentials-provider conf)))

(defn -environment-credentials-provider []
  (create-provider (creds/environment-credentials-provider)))

(defn -system-property-credentials-provider [jvm-props]
  (with-system-properties jvm-props
    (create-provider (creds/system-property-credentials-provider))))

;; REVIEW do we want to support `credential_process
(defn -profile-credentials-provider
  ([jvm-props]
   (with-system-properties jvm-props
     (create-provider (creds/profile-credentials-provider))))

  ([jvm-props profile-name]
   (with-system-properties jvm-props
     (create-provider (creds/profile-credentials-provider profile-name))))

  ([jvm-props profile-name ^java.io.File f]
   (throw (ex-info "profile-credentials-provider with 2 arguments not supported yet" {}))))

(def http-client pod.babashka.aws.impl.aws/http-client)

(defn -default-credentials-provider [jvm-props]
   (with-system-properties jvm-props
     (create-provider (creds/default-credentials-provider @http-client))))

(extend-protocol creds/CredentialsProvider
  clojure.lang.PersistentArrayMap
  (fetch [m]
    (creds/fetch (get-provider m))))

;;; Pod Client

(defn -fetch [provider]
  (when-let [provider (get-provider provider)]
    (creds/fetch provider)))

(def lookup-map
  {'-fetch -fetch
   '-basic-credentials-provider -basic-credentials-provider
   '-system-property-credentials-provider -system-property-credentials-provider
   '-profile-credentials-provider -profile-credentials-provider
   '-default-credentials-provider -default-credentials-provider})

(def describe-map
  `{:name pod.babashka.aws.credentials
    :vars
    ~(conj (mapv (fn [[k _]]
                   {:name k})
                 lookup-map)
           {:name "fetch"
            :code (pr-str
                   '(defprotocol CredentialsProvider
                      (fetch [_])))}
           {:name "map->Provider"
            :code (pr-str
                   '(defrecord Provider [provider-id]
                      CredentialsProvider
                      (fetch [provider]
                        (-fetch provider))))}
           {:name "profile-credentials-provider"
            :code (pr-str
                   '(defn profile-credentials-provider [& args]
                      (map->Provider (apply -profile-credentials-provider (cons (System/getProperties) args)))))}

           {:name "basic-credentials-provider"
            :code (pr-str
                   '(defn basic-credentials-provider [conf]
                      (map->Provider (-basic-credentials-provider conf))))}

           {:name "system-property-credentials-provider"
            :code (pr-str
                   '(defn system-property-credentials-provider []
                      (map->Provider (-system-property-credentials-provider (System/getProperties)))))}

           {:name "default-credentials-provider"
            :code (pr-str
                   '(defn default-credentials-provider [& _]
                      (map->Provider (-default-credentials-provider (System/getProperties)))))})})

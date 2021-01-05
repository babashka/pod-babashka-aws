(ns pod.babashka.aws.impl.aws.credentials
  (:require
   [clojure.edn]
   [clojure.java.io :as io]
   [cognitect.aws.client.api :as aws]
   [cognitect.aws.credentials :as creds]))

;;; Pod Backend

 (def *providers (atom {}))

(defn create-provider [provider]
  (let [provider-id (java.util.UUID/randomUUID)]
    (swap! *providers assoc provider-id provider)
    {::provider-id provider-id}))

(defn get-provider [config]
  (get @*providers (get config ::provider-id)))

(defn -basic-credentials-provider [conf]
  (create-provider (creds/basic-credentials-provider conf)))


;;; Pod Client

(defn -fetch [provider]
  (creds/fetch (get-provider provider)))

(def lookup-map
  {'-fetch -fetch
   '-basic-credentials-provider -basic-credentials-provider})

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

           {:name "basic-credentials-provider"
            :code (pr-str
                   '(defn basic-credentials-provider [conf]
                      (let [provider (-basic-credentials-provider conf)]
                        (reify CredentialsProvider
                          (fetch [_]
                            (-fetch provider))))))})})

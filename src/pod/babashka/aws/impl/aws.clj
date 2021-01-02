(ns pod.babashka.aws.impl.aws
  (:require
   [cognitect.aws.client.api :as aws]
   ;; these are dynamically loaded at runtime
   [cognitect.aws.http.cognitect]
   [cognitect.aws.protocols.json]
   [cognitect.aws.protocols.common]
   [cognitect.aws.protocols.rest]
   [cognitect.aws.protocols.rest-xml]
   [cognitect.aws.protocols.query]))

(def http-client (delay (cognitect.aws.http.cognitect/create)))

#_(def *credential-providers (atom {}))

#_(defn get-credential-provider [config]
  (get @*credential-providers (get config ::credential-provider-id)))

(def *clients (atom {}))

(defn get-client [config]
  (get @*clients (get config ::client-id)))

(defn client [{:keys [api credentials-provider] :as config}]
  (let [config (assoc config
                      :http-client @http-client)
        client (aws/client config)
        client-id (java.util.UUID/randomUUID)]
    (swap! *clients assoc client-id client)
    {::client-id client-id}))

(defn ops [client]
  (aws/ops (get-client client)))

(defn doc [client op]
  (aws/doc (get-client client) op))

(defn invoke [client op]
  (aws/invoke (get-client client) op))

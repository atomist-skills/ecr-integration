;; Copyright © 2021 Atomist, Inc.
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns atomist.ecr
  (:require
   [goog.string :as gstring]
   [http.client :as client]
   [atomist.time]
   [cljs.pprint :refer [pprint]]
   [atomist.cljs-log :as log]
   [atomist.async :refer-macros [go-safe <?]]
   [cljs.core.async :refer [go chan <! >!]]
   [clojure.string :as str]
   [atomist.docker :as docker]
   [cljs-node-io.core :as io]
   ["@aws-sdk/client-ecr" :as ecr-service]
   ["aws-sdk" :as aws-sdk]
   [atomist.json :as json]))

(enable-console-print!)

(defn account-host 
  [account-id region] 
  (gstring/format "%s.dkr.ecr.%s.amazonaws.com" account-id region))

(defn ecr-auth
  [{:keys [region access-key-id secret-access-key]}]
  (go-safe
   (log/infof "Authenticating GCR in region %s" region)
   (try
     (let [token-chan (chan)
           client (new (.-ECRClient ecr-service) #js {:region region
                                                      :credentials (.. aws-sdk -config -credentials)})]
       ;; write to in-memory file-system for GCF and remove when complete
       (io/spit "creds.json" (-> {:region region
                                  :accessKeyId access-key-id
                                  :secretAccessKey secret-access-key}
                                 (json/->str)))
       (.loadFromPath (.. aws-sdk -config) "creds.json")
     ;; Send AWS GetAuthorizationTokenCommand
       (.catch
        (.then
         (.send client (new (.-GetAuthorizationTokenCommand ecr-service) #js {}))
         (fn [data] (go (>! token-chan (-> data
                                           (. -authorizationData)
                                           (aget 0)
                                           (. -authorizationToken))))))
        (fn [err] (go (>! token-chan (ex-info "failed to create token" {:err err})))))
       {:access-token (<? token-chan)})
     (catch :default ex
       (println "inner error " ex)
       (throw ex))
     (finally
       (io/delete-file "creds.json")))))

(comment
  (go-safe
   (try
     (def x (<? (ecr-auth {:access-key-id "xxxxxx"
                           :secret-access-key "xxxxxxx"
                           :region "us-east-1"})))
     (println "success: " x)
     (catch :default ex
       (println "error:  " ex)))))

(defn get-labelled-manifests
  "log error or return labels"
  [{:keys [account-id region access-key-id secret-access-key]} repository tag-or-digest]
  (log/infof "get-image-info:  %s@%s/%s" region access-key-id tag-or-digest)
  (go-safe
   (let [auth-context (<? (ecr-auth {:region region
                                     :secret-access-key secret-access-key
                                     :access-key-id access-key-id}))]
     (<? (docker/get-labelled-manifests
          (account-host account-id region)
          (:access-token auth-context) repository tag-or-digest)))))



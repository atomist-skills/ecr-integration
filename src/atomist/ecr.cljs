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
   [atomist.time]
   [atomist.cljs-log :as log]
   [atomist.async :refer-macros [go-safe <?]]
   [cljs.core.async :refer [close! go chan <! >!]]
   [atomist.docker :as docker]
   [cljs-node-io.core :as io]
   ["@aws-sdk/client-ecr" :as ecr-service]
   ["aws-sdk" :as aws-sdk]
   ["tmp" :as tmp]
   [atomist.json :as json]))

(enable-console-print!)

(defn assume-role-request 
  "not adding SourceIdentity because we won't ask the partner to specific a source identity when they configure their trust policy"
  []
  {;; 
   :ExternalId ""
   ;; we need to agree on this to avoid the confused deputy attack
   :RoleArn ""
   ;; visible to cross-account customers
   :RoleSessionName "atomist"})

(defn account-host
  [account-id region]
  (gstring/format "%s.dkr.ecr.%s.amazonaws.com" account-id region))

(defn- empty-tmp-dir
  "we can only write AWS SDK config to a tmp dir on GCF"
  []
  (let [c (chan)]
    (.dir tmp
          (clj->js {:keep false :prefix (str "atm-" (. js/process -pid))})
          (fn [err path]
            (go
              (if err
                (>! c (ex-info "could not create tmp dir" {:err err}))
                (>! c path))
              (close! c))))
    c))

(defn with-close [f]
  (let [c (chan)]
    (go
      (<! c)
      (try
        (io/delete-file f)
        (catch :default ex
          (log/warn "unable to delete " f))))
    c))

(defn ecr-auth
  [{:keys [region access-key-id secret-access-key]}]
  (go-safe
   (log/infof "Authenticating GCR in region %s" region)
   (let [f (io/file (<? (empty-tmp-dir)) "config.json")]
     (io/spit f (-> {:region region
                     :accessKeyId access-key-id
                     :secretAccessKey secret-access-key
                     :httpOptions {:timeout 5000 :connectTimeout 5000}
                     :maxRetries 3}
                    (json/->str)))
     (.loadFromPath (.. aws-sdk -config) (.getPath f))
     (let [token-chan (chan)
           client (new (.-ECRClient ecr-service) (. aws-sdk -config))]
       ;; write to in-memory file-system for GCF and remove when complete

     ;; Send AWS GetAuthorizationTokenCommand
       (.catch
        (.then
         (.send client (new (.-GetAuthorizationTokenCommand ecr-service) #js {}))
         (fn [data] (go
                      (>! (with-close f) :close)
                      (>! token-chan (-> data
                                         (. -authorizationData)
                                         (aget 0)
                                         (. -authorizationToken))))))
        (fn [err] (go
                    (log/warn "err " err)
                    (>! (with-close f) :close)
                    (>! token-chan (ex-info "failed to create token" {:err err})))))
       {:access-token (<? token-chan)}))))

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


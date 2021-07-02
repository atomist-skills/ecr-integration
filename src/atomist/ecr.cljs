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
   [cljs.pprint :refer [pprint]]
   ["@aws-sdk/client-ecr" :as ecr-service]
   ["@aws-sdk/client-sts" :as sts-service]
   ["aws-sdk" :as aws-sdk]
   ["tmp" :as tmp]
   [atomist.json :as json]))

(enable-console-print!)

(def third-party-account-id (.. js/process -env -ECR_ACCOUNT_ID))
(def third-party-arn "arn:aws:iam::111664719423:role/atomist-ecr-integration")
(def third-party-external-id "atomist")
(def atomist-account-id (.. js/process -env -ASSUME_ROLE_ACCOUNT_ID))
(def atomist-account-key (.. js/process -env -ASSUME_ROLE_ACCESS_KEY_ID))
(def atomist-secret-key (.. js/process -env -ASSUME_ROLE_SECRET_ACCESS_KEY))
(enable-console-print!)

(defn list-repositories
 "assumes that the AWS sdk is initialized (assumeRole may have already switched roles to third party ECR)" 
  [ecr-client]
  (let [c (chan)]
    (.catch
      (.then
        (.send ecr-client (new (.-DescribeRepositoriesCommand ecr-service) #js {:registryId third-party-account-id}))
        (fn [data]
          (go (>! c (-> (.-repositories data) (js->clj :keywordize-keys true))))))
      (fn [err]
        (println "failed to ecr-client " err)
        (go (>! c (ex-info "failed to DescribeRepositoriesCommand" {:err err})))))
    c))

(defn assume-role []
  (go-safe
   (let [f (io/file "atomist-config.json")]
     (io/spit f (->
                 {:region "us-east-1"
                  :accessKeyId atomist-account-key
                  :secretAccessKey atomist-secret-key
                  :httpOptions {:timeout 3 :connectTimeout 5000}
                  :maxRetries 3}
                 (json/->str)))
     (.loadFromPath (.. aws-sdk -config) (.getPath f))
     (let [token-chan (chan)
           client (new (.-STSClient sts-service) (. aws-sdk -config))]
       (.catch
        (.then
         (.send client (new (.-AssumeRoleCommand sts-service) #js {:ExternalId "atomist"
                                                                   :RoleArn third-party-arn
                                                                   :RoleSessionName "atomist"}))
         (fn [data]
           (go
             (println "data " data)
             (>! token-chan (<! (list-repositories
                                 (new (.-ECR ecr-service)
                                        #js {:credentials #js {:accessKeyId (.. data -Credentials -AccessKeyId)
                                                               :secretAccessKey (.. data -Credentials -SecretAccessKey)
                                                               :sessionToken (.. data -Credentials -SessionToken)}})))))))
        (fn [err]
          (go
            (println "err " err)
            (log/error "error:  " err) ()
            (>! token-chan (ex-info "failed to create token" {:err err})))))
        (pprint (<? token-chan))))))

(comment
  ;; we are querying across accounts here
  (assume-role)
  )

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


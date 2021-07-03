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
   [goog.object :as o]
   ["@aws-sdk/client-eventbridge" :as eventbridge]
   ["@aws-sdk/client-ecr" :as ecr-service]
   ["@aws-sdk/client-sts" :as sts-service]
   ["aws-sdk" :as aws-sdk]
   [atomist.json :as json]))

(set! *warn-on-infer* false)

(def third-party-account-id (.. js/process -env -ECR_ACCOUNT_ID))
(def third-party-arn "arn:aws:iam::111664719423:role/atomist-ecr-integration")
(def third-party-external-id "atomist")
(def third-party-secret-key (.. js/process -env -ECR_SECRET_ACCESS_KEY))
(def third-party-access-key-id (.. js/process -env -ECR_ACCESS_KEY_ID))
(def atomist-account-id (.. js/process -env -ASSUME_ROLE_ACCOUNT_ID))
(def atomist-access-key-id (.. js/process -env -ASSUME_ROLE_ACCESS_KEY_ID))
(def atomist-secret-key (.. js/process -env -ASSUME_ROLE_SECRET_ACCESS_KEY))
(def params-with-arn {:region "us-east-1"
                      :access-key-id atomist-access-key-id
                      :secret-access-key atomist-secret-key
                      :arn third-party-arn
                      :external-id third-party-external-id})
(enable-console-print!)

(defn from-promise
  "turn a promise into a channel
    - channel will emit Promise value and then close
        - truthy Promise values go through js->clj, which works for primitives and js Objects
        - non-truthy Promise values are sent as the keyword :done
        - Promise errors are sent as a map with a {:failure key}"
  ([promise]
   (from-promise
    promise
    (fn [result] (if result (js->clj result :keywordize-keys true) :done))
    (fn [error] {:failure error})))
  ([promise value-handler failure-handler]
   (let [c (chan)
         {:keys [async]} (meta value-handler)]
     (.catch
      (.then promise (fn [result]
                       (go (>! c (if async 
                                   (<! (value-handler result))
                                   (value-handler result)))
                           (close! c))))
      (fn [error]
        (log/error "promise error:  " error)
        (go (>! c (failure-handler error))
            (close! c))))
     c)))

(defn wrap-error-in-exception [message err]
  (ex-info message {:err err}))

(defn list-repositories
 "assumes that the AWS sdk is initialized (assumeRole may have already switched roles to third party ECR)" 
  [ecr-client]
  (from-promise 
    (.send ecr-client (new (.-DescribeRepositoriesCommand ecr-service) #js {:registryId third-party-account-id}))
    (fn [data]
      (-> (.-repositories data) (js->clj :keywordize-keys true)))
    (partial wrap-error-in-exception "failed to run DescribeRepositoriesCommand")))

(defn get-authorization-token-command
  [ecr-client]
  (from-promise
   (.send ecr-client (new (.-GetAuthorizationTokenCommand ecr-service) #js {}))
   (fn [data]
     (-> data
         (. -authorizationData)
         (aget 0)
         (. -authorizationToken)))
   (partial wrap-error-in-exception "failed to create token")))

(defn call-aws-sdk-service
  "call aws-sdk v3 operations 
     - may use STS to assume role if there is an arn present.  Otherwise, default to use creds without STS"
  [{:keys [arn external-id access-key-id secret-access-key region]}
   service-constructor
   operation]
  (let [f (io/file "atomist-config.json")]
    (io/spit f (->
                {:region region
                 :accessKeyId access-key-id
                 :secretAccessKey secret-access-key
                 :httpOptions {:timeout 3 :connectTimeout 5000}
                 :maxRetries 3}
                (json/->str)))
    (.loadFromPath (.. aws-sdk -config) (.getPath f))
    (io/delete-file f)
    (let [client (new (.-STS sts-service) (. aws-sdk -config))]
      (if (and arn external-id)
        (from-promise
         (.send client
                (new (.-AssumeRoleCommand sts-service)
                     #js {:ExternalId external-id
                          :RoleArn arn
                          :RoleSessionName "atomist"
                          :credentials #js {:accessKeyId access-key-id
                                            :secretAccessKey secret-access-key}}))
         (with-meta
           (fn [data]
             (operation
              (new service-constructor
                   #js {:credentials #js {:accessKeyId (.. data -Credentials -AccessKeyId)
                                          :secretAccessKey (.. data -Credentials -SecretAccessKey)
                                          :sessionToken (.. data -Credentials -SessionToken)}})))
           {:async true})
         (partial wrap-error-in-exception "failed to create token"))
        (operation (new service-constructor
                        #js {:credentials #js {:accessKeyId access-key-id
                                               :secretAccessKey secret-access-key}}))))))

(comment
  ;; we are querying across accounts here (using role arn and sts)
  (go (pprint (<! (call-aws-sdk-service
                   {:region "us-east-1"
                    :access-key-id atomist-access-key-id
                    :secret-access-key atomist-secret-key
                    :arn third-party-arn
                    :external-id "atomist"}
                   (.-ECR ecr-service)
                   list-repositories))))
  ;; use creds to get an auth token
  (go (pprint (<! (call-aws-sdk-service
                   {:region "us-east-1"
                    :access-key-id third-party-access-key-id
                    :secret-access-key third-party-secret-key}
                   (.-ECR ecr-service)
                   get-authorization-token-command))))
  ;; use role arn and sts to get an auth token
  (go (pprint (<! (call-aws-sdk-service
                   {:region "us-east-1"
                    :access-key-id atomist-access-key-id
                    :secret-access-key atomist-secret-key
                    :arn third-party-arn
                    :external-id third-party-external-id }
                   (.-ECR ecr-service)
                   get-authorization-token-command)))))

(defn account-host
  [account-id region]
  (gstring/format "%s.dkr.ecr.%s.amazonaws.com" account-id region))

(defn ecr-auth
  "get an ecr authorization token"
  [{:keys [region] :as params}]
  (go-safe
   (log/infof "Authenticating GCR in region %s" region)
   {:access-token
    (<? (call-aws-sdk-service
         params
         (.-ECR ecr-service)
         get-authorization-token-command))}))

(defn get-labelled-manifests
  "log error or return labels"
  [{:keys [account-id region access-key-id] :as params} repository tag-or-digest]
  (log/infof "get-image-info:  %s@%s/%s" region access-key-id tag-or-digest)
  (go-safe
   (let [auth-context (<? (ecr-auth params))]
     (<? (docker/get-labelled-manifests
          (account-host account-id region)
          (:access-token auth-context) repository tag-or-digest)))))

(defn event-bridge-command 
  [operation-constructor params]
  (call-aws-sdk-service
   params-with-arn
   (.-EventBridge eventbridge)
   (fn [event-bridge-client]
     (from-promise
      (.send event-bridge-client
             (new operation-constructor (clj->js params)))))))

(defn pprint-channel-data [c]
  (go
    (pprint
      (<! c))))

(comment
  (pprint-channel-data
    (event-bridge-command
      (.-ListRulesCommand eventbridge) {:NamePrefix "atomist"})))

(def setup-event-bridge
  [{[:CreateConnectionCommand
     :UpdateConnectionCommand
     :DescribeConnectionCommand] {:Name ""
                                  :Description ""
                                  :AuthParameters {:BasicAuthParameters {:Password "" :Username ""}}
                                  :AuthorizationType "BASIC"}
    [:CreateApiDestinationCommand
     :UpdateApiDestinationCommand
     :DescribeApiDestinationCommand] {:Name ""
                                      :Description ""
                                      :InvocationEndpoint ""
                                      :HttpMethod "POST"
                                      :ConnectionArn ""}
    [:PutRuleCommand
     :ListRulesCommand] {:Name ""
                         :Description ""
                         :EventPattern (io/slurp (io/file "resources/event-pattern.json"))
                         :State "ENABLED"}
    [:PutTargetsCommand
     :ListTargetsByRuleCommand] {:Rule ""
                                 :Targets [{;; for the Api Destination
                                            :Arn ""
                                            ;; do we need a RoleArn?  Will it have to have been created already?
                                            }]}}])

(comment
  ;; first test with the third party creds
  (go
    (pprint
     (<!
      (get-labelled-manifests
       {:account-id third-party-account-id
        :region "us-east-1"
        :access-key-id third-party-access-key-id
        :secret-access-key third-party-secret-key}
       "pin-test"
       "sha256:5a703f57de904d1b189df40206458a6e3d9e9526b7bfd94dab8519e1c51b7a0c"))))

  ;; next, test with the atomist creds, and a third party trusted arn
  (go
    (pprint
     (<!
      (get-labelled-manifests
       {:account-id third-party-account-id
        :region "us-east-1"
        :access-key-id atomist-access-key-id
        :secret-access-key atomist-secret-key
        :external-id "atomist"
        :arn third-party-arn}
       "pin-test"
       "sha256:5a703f57de904d1b189df40206458a6e3d9e9526b7bfd94dab8519e1c51b7a0c"))))
  )

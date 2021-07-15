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

(ns atomist.main
  (:require [atomist.api :as api]
            [atomist.async :refer-macros [<? go-safe]]
            [atomist.cljs-log :as log]
            [atomist.ecr :as ecr]
            [atomist.json :as json]
            [goog.string :as gstring]
            [atomist.docker :as docker]))

(defn transact-environment-digest [handler]
  (fn [{:as request :atomist/keys [access-token]}]
    (api/trace "transact-environment-digest")
    (go-safe
     (try
       (let [[{id :db/id
               digest :image.recorded/digest
               :deployment.stream/keys [name appname]
               [tag] :docker.image/tags
               {host :docker.repository/host
                repository-name :docker.repository/repository} :deployment.image/repository}]
             (-> request :subscription :result first)]
         (log/infof "attempt to link %s@%s in environent %s/%s"
                    repository-name digest name appname)
         (if-let [manifests (not-empty (<? (ecr/get-labelled-manifests 
                                             request
                                             repository-name
                                             digest)))]
           (do
             (doseq [manifest manifests]
               (<? (api/transact
                    request
                    (docker/->image-layers-entities "hub.docker.com" repository-name manifest tag))))
             (<? (api/transact request
                               [{:schema/entity-type :docker/image
                                 :schema/entity "$image"
                                 :docker.image/digest digest}
                                {:db/id id
                                 :schema/entity-type :deployment/stream
                                 :deployment.stream/image "$image"
                                 :image.recorded/status :image.recorded.status/verified}]))
             (<? (handler (assoc request
                                 :atomist/status
                                 {:code 0
                                  :reason (gstring/format
                                           "transact missing digest %s@%s in environment for %s:%s"
                                           repository-name digest
                                           name appname)}))))
           (do
             (<? (api/transact request
                               [{:db/id id
                                 :schema/entity-type :deployment/stream
                                 :image.recorded/status :image.recorded.status/missing}]))
             (<? (handler (assoc request
                                 :atomist/status
                                 {:code 1
                                  :reason (gstring/format
                                           "digest %s@%s not found for environment for %s:%s"
                                           repository-name digest
                                           name appname)}))))))
       (catch :default ex
         (log/errorf ex "failed to transact")
         (assoc request
                :atomist/status
                {:code 1
                 :reason (gstring/format "failed to transact environment with unlinked private image:  %s" (str ex))}))))))

(defn transact-webhook [handler]
  (fn [{:as request :keys [account-id region]}]
    (api/trace "transact-webhook")
    (go-safe
     (try
       (let [{:as data
              :keys [source]
              {:keys [result repository-name image-digest action-type image-tag]} :detail}
             (-> request
                 :webhook
                 :body
                 (json/->obj))]
         (log/infof "ecr event data %s" data)
         (<? (api/transact request [(api/check-tx "webhook" "Webhook event received" :completed :success)]))

         (when (and repository-name image-tag)
           (doseq [manifest (<? (ecr/get-labelled-manifests
                                 request
                                 repository-name
                                 image-tag))]
             (log/info "transact manifest " manifest)
             (<? (api/transact
                  request
                  (docker/->image-layers-entities (ecr/account-host account-id region) repository-name manifest image-tag)))))
         (<? (handler (assoc request
                             :atomist/status
                             {:code 0
                              :reason (gstring/format "transact webhook for %s/%s" repository-name image-tag)}))))
       (catch :default ex
         (log/errorf ex "failed to transact")
         (assoc request
                :atomist/status
                {:code 1
                 :reason (gstring/format "failed to transact:  %s" (str ex))}))))))

(defn transact-config [handler]
  (fn [request]
    (go-safe
     (let [params (->> request :subscription :result (map first) (into {}))]
       (log/infof "transact config %s" (dissoc params ""))
       (<? (api/transact request [{:schema/entity-type :docker/registry
                                   :docker.registry/server-url (ecr/account-host
                                                                (get params "account-id")
                                                                (get params "region"))
                                   ; :docker.registry.ecr/access-key-id (get params "access-key-id")
                                   ; :docker.registry.ecr/secret-key-id (get params "secret-access-key")
                                   
                                   :docker.registry/type :docker.registry.type/ECR
                                   :atomist.skill.capability/name "DockerRegistry"
                                   :atomist.skill.capability/namespace "atomist"}]))
       (<? (handler (assoc request :params params)))))))


(defn check-auth
  [{:keys [region access-key-id secret-access-key]}]
  (go-safe
   (let [auth-context (<? (ecr/ecr-auth {:region region
                                         :secret-access-key secret-access-key
                                         :access-key-id access-key-id}))]
     {:authenticated? (boolean (:access-token auth-context))})))
(defn check-config
  [handler]
  (fn [request]
    (go-safe
   (<? (api/transact request [(api/check-tx "webhook" "Waiting for webhook events" :queued)
                              (api/check-tx "authentication" "Checking credentials" :in_progress)]))
     (let [auth (<? (check-auth request))]
       (log/infof "Auth check result: %s" auth)
       (<? (api/transact request (cond-> []

                                   (not (:authenticated? auth))
                                   (conj (api/check-tx "authentication" "Authentication failed" :completed :failure))

                                   (:authenticated? auth)
                                   (conj (api/check-tx "authentication" "Authentication successful" :completed :success)))))
       (<? (handler (assoc request
                           :atomist/status
                           {:code 0
                            :reason "Updated DockerRegistry capability & and validated credentials"})))))))

(defn ingest-latest-tag
  "ingest latest tag in from-line and link this docker image to the from line on this commit's Dockerfile"
  [{:as request} repository from-line]
  (go-safe
   (let [host (:docker.repository/host repository)
         repository (:docker.repository/repository repository)
         tag (:docker.file.from/tag from-line)]
     (log/infof "Fetching latest images for tag %s:%s" repository tag)
     (if (<? (docker/private-repo? host repository tag))
       (when-let [manifests (not-empty (<? (ecr/get-labelled-manifests request repository tag)))]
         (log/infof "Found %s manifests for %s:%s" (count manifests) repository tag)
         (doseq [manifest manifests
                 :let [new-digest (:digest manifest)
                       manifest-list? (-> manifest meta :manifest-list boolean)]]
           (log/infof "Digest for tag %s:%s platform %s -> %s" repository tag (:platform manifest) new-digest)
           (<? (api/transact request (concat
                                      (docker/->image-layers-entities host repository manifest tag)
                                      [(merge {:schema/entity-type :docker.file/line
                                               :db/id (:db/id from-line)}
                                              (if manifest-list?
                                                {:docker.file.from/manifest-list "$manifest-list"}
                                                {:docker.file.from/image "$docker-image"}))])))))
       (log/infof "Repository %s/%s:%s skipped because it isn't private" host repository tag)))))

(defn transact-latest-tag
  "whenever we see a Dockerfile on a branch and it has a FROM that points at an unpinned ECR tag
   then we make sure the latest tag is ingested, and link the image to the docker file line"
  [handler]
  (fn [request]
    (go-safe
     (try
       (doseq [result (-> request :subscription :result)
               :let [repository (first result)
                     from-line (last result)]]
         (<? (ingest-latest-tag request repository from-line)))
       (<? (handler (assoc request :atomist/status
                           {:code 0
                            :reason (gstring/format "Ingested latest tags")})))
       (catch :default ex
         (log/errorf ex "Failed to transact-from-image")
         (assoc request
                :atomist/status
                {:code 1
                 :reason (gstring/format "Unexpected error ingesting latest tag")}))))))

(defn ^:export handler
  [data sendreponse]
  (api/make-request
   data
   sendreponse
   (-> (api/finished)
       (api/mw-dispatch {:config-change.edn (-> (api/finished)
                                                (check-config)
                                                (transact-config))

                         :new-missing-private-image-in-environment.edn (-> (api/finished)
                                                                           (transact-environment-digest))

                         :docker-file-with-unpinned-from.edn (-> (api/finished)
                                                                 (transact-latest-tag))

                         :default (-> (api/finished)
                                      (transact-webhook))})
       (api/add-skill-config)
       (api/log-event)
       (api/status))))


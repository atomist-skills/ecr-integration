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
            [cljs.pprint :refer [pprint]]
            [cljs.tools.reader.edn :as edn]
            [goog.string :as gstring]
            [atomist.docker :as docker]))

(enable-console-print!)

(defn add-creds-if-present [handler]
  (fn [request]
    (api/trace (gstring/format "add-creds-if-present %s" (:creds request)))
    (go-safe
     (<? (handler (assoc request :atomist/docker-hub-creds (:creds request)))))))

(defn transact-webhook [handler]
  (fn [{:as request :keys [docker-id] :atomist/keys [docker-hub-creds]}]
    (api/trace "transact-webhook")
    (go-safe
     (try
       (let [{:as data :keys [callback_url] {:keys [tag]} :push_data {:keys [repo_name]} :repository}
             (-> request
                 :webhook
                 :body
                 (json/->obj))]
         (log/infof "docker hub event data %s" data)
         ;; docker hub notifications should be verified before being sent as the webhook can not be verified

         (when (and repo_name tag)
           (doseq [manifest (<? (dockerhub/get-labelled-manifests
                                 repo_name
                                 tag
                                 docker-id
                                 docker-hub-creds))]

             (<? (api/transact
                  request
                  (docker/->image-layers-entities "hub.docker.com" repo_name manifest tag)))))
         (<? (handler (assoc request
                             :atomist/status
                             {:code 0
                              :reason (gstring/format "transact webhook for %s/%s" repo_name tag)}))))
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
                                   :docker.registry/server-url (gstring/format
                                                                "registry.hub.docker.com/%s"
                                                                (get params "namespace" "library"))
                                   :docker.registry/secret (get params "creds")
                                   :docker.registry/type :docker.registry.type/DOCKER_HUB
                                   :atomist.skill.configuration.capability.provider/name "DockerRegistry"
                                   :atomist.skill.configuration.capability.provider/namespace "atomist"}]))
       (<? (handler (assoc request
                           :atomist/status
                           {:code 0
                            :reason "Updated DockerHub capability"})))))))

(defn refresh-tag
  "Find new images based on current tag, ingest if digest has changed"
  [request repository digests tag]
  (go-safe
   (let [digests (set digests)]
     (log/infof "Fetching latest images for tag %s:%s" repository tag)
     (when-let [manifests (not-empty (<? (dockerhub/get-labelled-manifests repository tag)))]
       (log/infof "Found %s manifests for %s:%s" (count manifests) repository tag)
       (doseq [manifest manifests
               :let [new-digest (:digest manifest)]]
         (log/debugf "Checking new digest %s" new-digest)
         (if (not (digests new-digest))
           (do
             (log/infof "New digest for tag %s:%s platform %s -> %s" repository tag (:platform manifest) new-digest)
             (<? (api/transact request (docker/->image-layers-entities "hub.docker.com" repository manifest tag))))
           (log/infof "No new digests for tag %s:%s found" repository tag)))))))

(defn refresh-tags
  [request [image tag]]
  (go-safe
   (let [repository (-> image :docker.image/repository :docker.repository/repository)]
     (log/infof "Fetching latest images for tag %s:%s" repository tag)
     (when-let [manifests (not-empty (<? (dockerhub/get-labelled-manifests repository tag)))]
       (log/infof "Found %s manifests for %s:%s" (count manifests) repository tag)
       (doseq [manifest manifests
               :let [new-digest (:digest manifest)]]
         (log/infof "Digest for tag %s:%s platform %s -> %s" repository tag (:platform manifest) new-digest)
         (<? (api/transact request (docker/->image-layers-entities "hub.docker.com" repository manifest tag))))))))

(defn refresh-images [handler]
  (fn [request]
    (go-safe
     (try
       (let [images (-> request :subscription :result)]
         (doseq [image images]
           (<? (refresh-tags request image)))
         (<? (handler (assoc request
                             :atomist/status
                             {:code 0
                              :reason (gstring/format "Refreshed images for %s images" (count images))}))))
       (catch :default ex
         (log/errorf ex "Error refreshing images")
         (<? (handler (assoc request
                             :atomist/status
                             {:code 1
                              :reason (gstring/format "Error refreshing images")}))))))))

(defn transact-from-tagged-image
  [handler]
  (fn [{:keys [docker-id] :as request :atomist/keys [docker-hub-creds]}]
    (go-safe
     (try
       (let [parent-image (-> request :subscription :result first first)
             parent-image-name (docker/->nice-image-name parent-image)
             {:docker.file.from/keys [tag repository]} (-> request :subscription :result first second)
             docker-file (-> request :subscription :result first last)
             {:docker.repository/keys [repository]} repository
             parent-image-entity {:schema/entity-type :docker/image
                                  :schema/entity "$parent-image"
                                  :docker.image/digest (:docker.image/digest parent-image)
                                  :docker.image/docker-file "$docker-file"}
             docker-file-entity {:schema/entity-type :docker/file
                                 :schema/entity "$docker-file"
                                 :docker.file/path (:docker.file/path docker-file)
                                 :docker.file/sha (:docker.file/sha docker-file)}
             manifests (not-empty (<? (dockerhub/get-labelled-manifests repository tag docker-id docker-hub-creds)))
             image-str (str repository ":" tag)]

         (if manifests
           (do
             (if-let [matching-manifest (docker/matching-image parent-image manifests)]
               (do
                 (log/infof "%s - FROM %s found, ingesting..." parent-image-name image-str)
                 (<? (api/transact request (concat
                                            (docker/->image-layers-entities "hub.docker.com"
                                                                            repository
                                                                            matching-manifest
                                                                            tag)
                                            ;; note: we link because it lines up
                                            (concat [(assoc parent-image-entity :docker.image/from "$docker-image")
                                                     docker-file-entity]
                                                    ;; set parent platform if we can
                                                    (when-let [platform (:platform matching-manifest)]
                                                      (docker/->platform platform "$parent-image"))))))
                 (<? (handler (assoc request :atomist/status
                                     {:code 0
                                      :reason (gstring/format "%s - FROM link ingested %s" parent-image-name image-str)}))))
               (<? (handler (assoc request :atomist/status
                                   {:code 1
                                    :reason (gstring/format "%s - FROM images (%s) found %s, but layers don't match" parent-image-name (count manifests) image-str)})))))
           (<? (handler (assoc request :atomist/status
                               {:code 1
                                :reason (gstring/format "%s - FROM image not found %s" parent-image-name image-str)})))))

       (catch :default ex
         (log/errorf ex "Failed to transact-from-image")
         (assoc request
                :atomist/status
                {:code 1
                 :reason (gstring/format "Unexpected error transacting FROM image")}))))))

(defn transact-from-digest-image
  [handler]
  (fn [{:keys [docker-id] :as request :atomist/keys [docker-hub-creds]}]
    (go-safe
     (try
       (let [parent-image (-> request :subscription :result first first)
             parent-image-name (docker/->nice-image-name parent-image)
             {:docker.file.from/keys [digest repository]} (-> request :subscription :result first second)
             docker-file (-> request :subscription :result first last)
             {:docker.repository/keys [repository]} repository
             parent-image-entity {:schema/entity-type :docker/image
                                  :schema/entity "$parent-image"
                                  :docker.image/digest (:docker.image/digest parent-image)
                                  :docker.image/docker-file "$docker-file"}
             docker-file-entity {:schema/entity-type :docker/file
                                 :schema/entity "$docker-file"
                                 :docker.file/path (:docker.file/path docker-file)
                                 :docker.file/sha (:docker.file/sha docker-file)}
             manifests (not-empty (<? (dockerhub/get-labelled-manifests repository digest docker-id docker-hub-creds)))
             image-str (str repository ":" digest)]

         (if manifests
           (do
             (if-let [matching-manifest (docker/matching-image parent-image manifests)]
               (do
                 (log/infof "%s - FROM %s found, ingesting..." parent-image-name image-str)
                 (<? (api/transact request (concat
                                            (docker/->image-layers-entities "hub.docker.com"
                                                                            repository
                                                                            matching-manifest)
                                            ;; note: we link because it lines up
                                            (concat [(assoc parent-image-entity :docker.image/from "$docker-image")
                                                     docker-file-entity]
                                                    ;; set parent platform if we can
                                                    (when-let [platform (:platform matching-manifest)]
                                                      (docker/->platform platform "$parent-image"))))))
                 (<? (handler (assoc request :atomist/status
                                     {:code 0
                                      :reason (gstring/format "%s - FROM link ingested %s" parent-image-name image-str)}))))
               (<? (handler (assoc request :atomist/status
                                   {:code 1
                                    :reason (gstring/format "%s - FROM images (%s) found %s, but layers don't match" parent-image-name (count manifests) image-str)})))))
           (<? (handler (assoc request :atomist/status
                               {:code 1
                                :reason (gstring/format "%s - FROM image not found %s" parent-image-name image-str)})))))

       (catch :default ex
         (log/errorf ex "Failed to transact-from-image")
         (assoc request
                :atomist/status
                {:code 1
                 :reason (gstring/format "Unexpected error transacting FROM image")}))))))

(defn ^:export handler
  [data sendreponse]
  (api/make-request
   data
   sendreponse
   (-> (api/finished)
       (api/mw-dispatch {:config-change.edn (-> (api/finished)
                                                (transact-config))

                         :new-docker-image-from-tag.edn (-> (api/finished)
                                                            (transact-from-tagged-image)
                                                            (add-creds-if-present)
                                                            (api/add-resource-providers)
                                                            (api/add-skill-config))

                         :new-docker-image-from-digest.edn (-> (api/finished)
                                                               (transact-from-digest-image)
                                                               (add-creds-if-present)
                                                               (api/add-resource-providers)
                                                               (api/add-skill-config))

                         :docker-refresh-tags-schedule.edn (-> (api/finished)
                                                               (refresh-images)
                                                               (add-creds-if-present))

                         :default (-> (api/finished)
                                      (transact-webhook)
                                      (add-creds-if-present))})
       (api/add-skill-config)
       (api/log-event)
       (api/status))))


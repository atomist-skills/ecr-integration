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
   [clojure.string :as str]
   [atomist.docker :as docker]))

(enable-console-print!)

(def auth-url "https://auth.docker.io/token")
(def domain "registry-1.docker.io")

(defn dockerhub-auth
  [{:keys [repository username api-key]}]
  (go-safe
   (when (and username api-key)
     (log/infof "Authenticating using username and api-key")
     (let [response (<? (client/post auth-url
                                     {:form-params {:service "registry.docker.io"
                                                    :client_id "Atomist"
                                                    :grant_type "password"
                                                    :username username
                                                    :password api-key
                                                    :scope (gstring/format "repository:%s:pull" repository)}}))]
       (if (= 200 (:status response))
         {:access-token (-> response :body :access_token)}
         (throw (ex-info (gstring/format "unable to auth %s" auth-url) response)))))))

(defn dockerhub-anonymous-auth
  [{:keys [repository username api-key]}]
  (go-safe
   (when (not (and username api-key))
     (log/infof "Attempting anonymous auth for %s" repository)
     (let [repository (if (str/includes? repository "/") repository (str "library/" repository))
           response (<? (client/get (gstring/format "https://auth.docker.io/token?service=registry.docker.io&scope=repository:%s:pull" repository)))]
       (if (= 200 (:status response))
         {:access-token (-> response :body :token)
          :repository repository}
         (throw (ex-info (gstring/format "unable to auth %s" auth-url) response)))))))

(defn run-tasks [m & ts]
  (go-safe
   (loop [context m tasks ts]
     (if-let [task (first tasks)]
       (recur (merge context (<? (task context))) (rest tasks))
       context))))

(defn get-labelled-manifests
  "log error or return labels"
  ([repository tag-or-digest]
   (get-labelled-manifests repository tag-or-digest nil nil))
  ([repository tag-or-digest username api-key]
   (log/infof "get-image-info:  %s@%s/%s" (or username "anonymous") repository tag-or-digest)
   (go-safe
    (let [auth-context (<? (run-tasks
                            {:repository repository
                             :tag tag-or-digest
                             :username username
                             :api-key api-key}
                            dockerhub-auth
                            dockerhub-anonymous-auth))]
      (<? (docker/get-labelled-manifests domain (:access-token auth-context) (or (:repository auth-context) repository) tag-or-digest))))))



(ns snapchamber.routes.api
  (:use compojure.core)
  (:require [liberator.core
             :refer [defresource
                     request-method-in]]
            [noir.util.crypt :refer [md5]]
            [snapchamber.util :as util]
            [taoensso.timbre :as timbre]
            [snapchamber.db :as db]))

;; handle POST
(defn save-snap
  "Generate an id for the new snap and save it to database,
   returning the new snap id"
  [context]
  (let [params (get-in context [:request :params])
        image-data (params :imageData)
        snap-id (util/short-id)]
    (do
      (db/create-snap snap-id image-data)
      (db/stats-snap-created)
      (timbre/info (str "Snap created: " snap-id))
      {:snap-id snap-id})))


;; needed to specify the representation of response
(def rep-map
  {:representation {:media-type "application/json"}})


(defn post-malformed?
  [params]
  (let [image-data (:imageData params)]
    (cond
      ;; is the imageData string empty?
      (or (not (= java.lang.String (type image-data)))
          (empty? image-data))
      [true, (merge rep-map
                    {:snap-error "imageData required, and must be a string"})]

      ;; must be at least 24 characters long
      (< (.length image-data) 24)
      [true, (merge rep-map
                    {:snap-error "imageData too small"})]

      ;; must start with correct sequence
      (not (= "data:image/jpeg;base64" (subs image-data 0 22)))
      [true, (merge rep-map
                    {:snap-error "imageData should be base64 jpeg"})]

      ;; else it must be ok
      :else
      [false, rep-map])))


(defn snap-request-malformed? [context]
  (let [method (get-in context [:request :request-method])
        params (get-in context [:request :params])]
    (if (= method :get)
      [false, rep-map] ;; presume we're fine with GET
      (if (= method :post)
        (post-malformed? params)))))


(defn snap-exists? [context]
  (let [method (get-in context [:request :request-method])
        params (get-in context [:request :params])]
    (cond
      (= method :get)
      (db/snap-exists? (get-in context [:request :route-params :snap-id]))
      (= method :post)
      (db/image-hash-exists? (md5 (:imageData params)))
      :else
      false)))


;; handle GET
(defn retrieve-snap [snap-id]
  (let [snap (db/get-snap! snap-id)]
    (do
      (db/stats-snap-viewed)
      (timbre/info (str "Snap viewed: " snap-id))
      {:snapId snap-id
       :imageData (:imageData snap)})))


(defresource snap [snap-id]
  :service-available? true
  :available-media-types ["application/json"]
  :method-allowed? (request-method-in :get :post)

  :allowed?
  (fn [context]
    (let [method (get-in context [:request :request-method])
          params (get-in context [:request :params])]
      (if (= method :post)
        (let [h (md5 (:imageData params))]
          ;; if the same image already exists, request is not allowed
          (not (db/image-hash-exists? h)))
        true)))

  :exists?
  snap-exists?

  :handle-ok
  (fn [_] (retrieve-snap snap-id))

  :malformed?
  snap-request-malformed?

  :handle-malformed
  (fn [context]
    {:error (:snap-error context)})

  :post! save-snap
  :handle-created (fn [context] {:snapId (context :snap-id)}))


(defroutes api-routes
  (POST "/api/snap" [] snap)
  (GET "/api/snap/:snap-id" [snap-id] (snap snap-id)))

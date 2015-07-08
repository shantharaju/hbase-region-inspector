(ns hbase-region-inspector.core
  (:require [clojure.string :as str]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.util.response :refer [response content-type resource-response]]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults site-defaults]]
            [ring.middleware.json :refer [wrap-json-response]]
            [compojure.core :refer [defroutes GET PUT routes wrap-routes]]
            [compojure.route :as route]
            [hiccup.core :as h]
            [selmer.parser :refer [render-file]]
            [hbase-region-inspector.hbase :as hbase]
            [hbase-region-inspector.util :as util])
  (:gen-class))

;;; ZooKeeper quorum we point to
(defonce zookeeper (atom "localhost"))

;;; Whether we should allow region relocation or not
(defonce read-only? (atom false))

;;; Whether we should include meta region or not
(defonce with-meta? (atom false))

;;; Cache the result of previous inspection
(defonce cached (atom {:updated-at nil :regions []}))

;;; Inspection interval
(def update-interval 10000)

(defn format-val
  "String formatter for region properties"
  [type val & props]
  (let [mb #(format "%d MB" (long %))
        kb #(format "%d KB" (long %))
        rate #(if (> % 10) (long %) (format "%.2f" (double %)))
        count-rate #(if %2
                      (format "%s (%s/sec)" %1 (rate %2))
                      (str %1))
        props (or (first props) {})]
    (case type
      :start-key                ["Start key" (hbase/byte-buffer->str val)]
      :end-key                  ["End key" (hbase/byte-buffer->str val)]
      :stores                   ["Storefiles" val]
      :store-file-size-mb       ["Data size"
                                 (if-let [uncmp (:store-uncompressed-size-mb props)]
                                   (format "%s (%s)" (mb val) (mb uncmp))
                                   (mb val))]
      :store-file-index-size-mb ["Index" (mb val)]
      :memstore-size-mb         ["Memstore" (mb val)]
      :requests                 ["Requests" (count-rate val (:requests-rate props))]
      :read-requests            ["Reads" (count-rate val (:read-requests-rate props))]
      :write-requests           ["Writes" (count-rate val (:write-requests-rate props))]
      :root-index-size-kb       ["Root index" (kb val)]
      :bloom-size-kb            ["Bloom filter" (kb val)]
      :total-index-size-kb      ["Total index" (kb val)]
      :compaction               ["Compaction" (apply format "%d / %d" val)]
      :used-heap-mb             ["Used heap" (str val " MB")]
      :max-heap-mb              ["Max heap" (str val " MB")]
      [(util/keyword->str (str type))
       (if (instance? Number val) (rate val) val)])))

(defn build-region-popover
  "Builds a small HTML snippet for each region to be used in bootstrap popover"
  [props]
  (let [{:keys [table encoded-name]} props]
    (h/html
      [:h3 table " " [:small encoded-name]]
      [:table {:class "table table-condensed table-striped"}
       [:tbody
        (map #(let [[k v] (format-val % (% props) props)]
                [:tr [:th {:class "col-xs-2"} k] [:td v]])
             (filter
               #(% props)
               [:server
                :start-key :end-key
                :store-file-size-mb
                :stores
                :memstore-size-mb
                :requests
                :read-requests
                :write-requests
                :compaction]))]])))

(defn build-server-popover
  "Builds a small HTML snippet for each server to be used in bootstrap popover"
  [props]
  (let [{:keys [name]} props]
    (h/html
      [:h3 name]
      [:table {:class "table table-condensed table-striped"}
       [:tbody
        (map #(let [[k v] (format-val % (% props) props)]
                [:tr [:th {:class "col-xs-2"} k] [:td v]])
             (filter
               #(% props)
               [:requests-rate
                :regions
                :store-file-size-mb
                :store-files
                :used-heap-mb
                :max-heap-mb]))]])))

(defn collect-info
  "Collects information from hbase"
  [zk]
  (hbase/admin-let
    [admin zk]
    (hbase/collect-info admin)))

(defn region-location?
  "Finds the host region server of the region"
  [admin encoded-name]
  (let [all-regions (hbase/collect-region-info admin)]
    (when-let [the-region
               (first (filter #(= (:encoded-name %) encoded-name)
                              all-regions))]
      (:server the-region))))

(defn byte-buffers->str
  "Returns an updated map with start-key and end-key as strings"
  [region]
  (reduce (fn [region key]
            (assoc region key (hbase/byte-buffer->str (key region))))
          region
          [:start-key :end-key]))

(defn regions-by-servers
  "Generates output for /server_regions.json. Regions grouped by their servers."
  [{:keys [regions servers metric sort tables with-meta?]
    :or   {tables nil with-meta? false}}]
  (let [;; Exclude meta regions
        all-regions (if with-meta? regions (remove :meta? regions))

        ;; Sort the tables in descending order by the sum of the given metric
        all-tables (keys (sort-by
                           #(reduce - 0 (map metric (last %)))
                           (group-by :table all-regions)))

        ;; Tables to show
        visible-tables (set (if (seq tables) tables all-tables))

        ;; Filter regions by table name
        visible-regions (map byte-buffers->str
                             (filter #(visible-tables (:table %)) all-regions))

        ;; Group by server, sort the pairs, build a list of maps with :name and :regions
        grouped (map #(zipmap [:name :regions] %)
                     (sort-by first util/compare-server-names
                              (group-by :server visible-regions)))
        ;; Function to sort the regions in the descending order
        score-fn #(vector (- (metric %))
                          (.indexOf all-tables (:table %)))
        sort-fn (if (= sort :metric)
                  (fn [regions] (sort-by score-fn regions))
                  (fn [regions] (sort-by (comp vec reverse score-fn) regions)))
        ;; Sort the regions in each server
        grouped (map #(update % :regions sort-fn) grouped)
        ;; Find the local sum of the metric of each region
        grouped (map #(assoc % :sum (reduce + (filter pos? (map metric (:regions %)))))
                     grouped)
        ;; Find the max of the sums
        group-max (if (not-empty grouped)
                    (apply max (map :sum grouped))
                    nil)]
    ;; Build the result list
    {:servers (map #(assoc %
                           :max group-max
                           :html (:html (servers (:name %)))) grouped)
     :tables (or all-tables [])}))

(defn regions-by-tables
  "Generates output for /table_regions.json. Regions grouped by their tables."
  [{:keys [regions metric sort tables with-meta?]
    :or   {tables nil with-meta? false}}]
  (let [;; Exclude hbase:meta table
        all-regions (if with-meta? regions (remove :meta? regions))

        ;; Sort the tables in their names
        all-tables (->> all-regions (map :table) (apply sorted-set) vec)

        ;; Regions to show
        visible-regions (if (seq tables)
                          (let [table-set (set tables)]
                            (filter #(table-set (:table %)) all-regions))
                          all-regions)

        ;; Sort the regions
        sorted-regions (if (= sort :metric)
                         (reverse (sort-by metric visible-regions))
                         (sort-by :start-key hbase/bytes-comp visible-regions))
        ;; ByteBuffer -> strings
        visible-regions (map byte-buffers->str sorted-regions)
        ;; Group regions by table name
        grouped (group-by :table visible-regions)
        ;; Calculate the sum for each group
        grouped-sum (into {}
                          (for [[table regions] grouped]
                            [table (reduce #(+ %1 (metric %2)) 0 regions)]))
        ;; List of maps with table-level sums
        list-with-sum (map #(assoc (zipmap [:name :regions] %)
                                   :sum (grouped-sum (first %)))
                           grouped)
        ;; Sort the list by table name
        sorted (sort-by :name list-with-sum)
        ;; Sorted with human-readable sum
        sorted (map #(assoc % :sumh (last (format-val metric (:sum %)))) sorted)]
    {:all-tables all-tables
     :tables     sorted}))

(defn update-regions!
  "Collects region info from HBase and store it in @cached"
  []
  (util/debug "Updating regions")
  (let [old-regions (into {} (for [region (:regions @cached)]
                               [(:name region) region]))
        {new-regions :regions
         servers     :servers} (util/elapsed-time "collect-info"
                                                  (collect-info @zookeeper))
        prev-time (:updated-at @cached)
        now (System/currentTimeMillis)
        interval (- now (or prev-time now))
        diff-fn (if (zero? interval)
                  (fn [& _] 0)
                  (fn [region metric]
                    (let [name (:name region)
                          new-val (metric region)
                          old-val (or (get-in old-regions [name metric]) new-val)]
                      (/ (- new-val old-val) interval 0.001))))
        new-regions (pmap #(assoc
                             %
                             :requests-rate (diff-fn % :requests)
                             :write-requests-rate (diff-fn % :write-requests)
                             :read-requests-rate (diff-fn % :read-requests)
                             :compaction ((juxt :compacted-kvs :total-compacting-kvs) %))
                          new-regions)
        new-regions (pmap #(assoc % :html (build-region-popover %)) new-regions)
        servers (into {} (for [[k v] servers]
                           [k (assoc v :html (build-server-popover v))]))]
    (reset! cached {:updated-at now
                    :regions new-regions
                    :servers servers})))

(defn start-periodic-updater!
  "Starts a thread that periodically runs update-regions!"
  []
  (future
    (loop []
      (Thread/sleep update-interval)
      (try
        (#'update-regions!)
        (catch Exception e
          (util/error e)))
      (recur))))

;;; Compojure route for web app
(defroutes app-routes
  (GET "/" {remote :remote-addr}
       (util/debug (format "/ [%s]" remote))
       (render-file "public/index.html" {:zookeeper @zookeeper
                                         :updated-at (:updated-at @cached)}))
       ;; (content-type (resource-response "index.html" {:root "public"})
       ;;               "text/html"))
  (route/resources "/" {:root "public"}))

;;; Compojure route for API
(defroutes api-routes
  (GET "/server_regions.json"
       {{:keys [sort metric]
         :or {sort "metric" metric "store-file-size-mb"}
         :as params} :params
        remote :remote-addr}
       (util/debug (format "server_regions.json [%s]" remote))
       (let [tables (get params "tables[]" [])
             tables (if (instance? String tables) [tables] tables)]
         (response
           (regions-by-servers (merge (select-keys @cached [:regions :servers])
                                      {:metric     (keyword metric)
                                       :sort       (keyword sort)
                                       :tables     tables
                                       :with-meta? @with-meta?})))))
  (GET "/table_regions.json"
       {{:keys [sort metric]
         :or {sort "metric" metric "store-file-size-mb"}
         :as params} :params
        remote :remote-addr}
       (util/debug (format "table_regions.json [%s]" remote))
       (let [tables (get params "tables[]" [])
             tables (if (instance? String tables) [tables] tables)]
         (response
           (regions-by-tables {:regions    (:regions @cached)
                               :metric     (keyword metric)
                               :sort       (keyword sort)
                               :tables     tables
                               :with-meta? @with-meta?}))))
  (PUT "/move_region" {{:keys [src dest region]} :params remote :remote-addr}
       (util/debug (format "move_region [%s]" remote))
       (when @read-only?
         (throw (Exception. "Read-only mode. Not allowed.")))
       (hbase/admin-let
         [admin @zookeeper]
         (.move admin (.getBytes region) (.getBytes dest))
         (loop [tries 20
                message (format "Moving %s from %s to %s" region src dest)]
           (util/info message)
           (when (> tries 0)
             (Thread/sleep 250)
             (let [loc (region-location? admin region)]
               (if (nil? loc)
                 (recur (dec tries) "Region is not online")
                 (if (not= loc dest)
                   (recur (dec tries) "Region not yet moved")
                   (util/info "Region is moved")))))))
       (update-regions!)
       (response {})))

(defn wrap-exception [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        {:status 500
         :body (.getMessage e)}))))

(def app
  "Combined route of app routes and api routes"
  (routes (-> app-routes
              (wrap-routes wrap-defaults site-defaults))
          (-> api-routes
              (wrap-routes wrap-defaults api-defaults)
              wrap-json-response
              wrap-exception)
          (route/not-found "404")))

(defn- bootstrap [zk port bg]
  ;; Make sure that we can connect to the given ZooKeeper quorum before
  ;; starting background process
  (reset! zookeeper zk)
  (update-regions!)
  ;; Start background process
  (when bg
    (util/info "Start periodic update process")
    (start-periodic-updater!))
  ;; Start web server
  (util/info (format "Starting web server: http://%s:%d"
                     (util/local-ip-address) port))
  (run-jetty app {:port port}))

(defn exit [message]
  (println message)
  (println "usage: hbase-region-inspector [--read-only --with-meta] QUORUM[/ZKPORT] PORT")
  (System/exit 1))

(defn -main [& args]
  (let [[opts args] ((juxt filter remove) #(.startsWith % "-") args)
        opts (set (map #(keyword (str/replace % #"^-*" "")) opts))]
    (reset! read-only? (contains? opts :read-only))
    (reset! with-meta? (contains? opts :with-meta))
    (when (not= 2 (count args)) (exit "invalid number of arguments"))
    (try
      (let [[zk port] args
            port (Integer/parseInt port)]
        (bootstrap zk port true))
      (catch NumberFormatException e (exit "invalid port")))))


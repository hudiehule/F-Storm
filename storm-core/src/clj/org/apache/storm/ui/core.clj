;; Licensed to the Apache Software Foundation (ASF) under one
;; or more contributor license agreements.  See the NOTICE file
;; distributed with this work for additional information
;; regarding copyright ownership.  The ASF licenses this file
;; to you under the Apache License, Version 2.0 (the
;; "License"); you may not use this file except in compliance
;; with the License.  You may obtain a copy of the License at
;;
;; http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns org.apache.storm.ui.core
  (:use compojure.core)
  (:use [clojure.java.shell :only [sh]])
  (:use ring.middleware.reload
        ring.middleware.multipart-params
        ring.middleware.multipart-params.temp-file)
  (:use [ring.middleware.json :only [wrap-json-params]])
  (:use [hiccup core page-helpers])
  (:use [org.apache.storm config util log stats zookeeper converter])
  (:use [org.apache.storm.ui helpers])
  (:use [org.apache.storm.daemon [common :only [ACKER-COMPONENT-ID ACKER-INIT-STREAM-ID ACKER-ACK-STREAM-ID
                                              ACKER-FAIL-STREAM-ID mk-authorization-handler
                                              start-metrics-reporters]]])
  (:import [org.apache.storm.utils Utils]
           [org.apache.storm.generated NimbusSummary])
  (:use [clojure.string :only [blank? lower-case trim split]])
  (:import [org.apache.storm.generated ExecutorSpecificStats
            ExecutorStats ExecutorSummary ExecutorInfo TopologyInfo SpoutStats BoltStats
            ErrorInfo ClusterSummary SupervisorSummary TopologySummary
            Nimbus$Client StormTopology GlobalStreamId RebalanceOptions
            KillOptions GetInfoOptions NumErrorsChoice DebugOptions TopologyPageInfo
            TopologyStats CommonAggregateStats ComponentAggregateStats
            ComponentType BoltAggregateStats SpoutAggregateStats
            ExecutorAggregateStats SpecificAggregateStats ComponentPageInfo
            LogConfig LogLevel LogLevelAction])
  (:import [org.apache.storm.security.auth AuthUtils ReqContext])
  (:import [org.apache.storm.generated AuthorizationException ProfileRequest ProfileAction NodeInfo])
  (:import [org.apache.storm.security.auth AuthUtils])
  (:import [org.apache.storm.utils VersionInfo])
  (:import [org.apache.storm Config])
  (:import [java.io File])
  (:require [compojure.route :as route]
            [compojure.handler :as handler]
            [ring.util.response :as resp]
            [org.apache.storm [thrift :as thrift]])
  (:require [metrics.meters :refer [defmeter mark!]])
  (:import [org.apache.commons.lang StringEscapeUtils])
  (:import [org.apache.logging.log4j Level])
  (:gen-class))

(def ^:dynamic *STORM-CONF* (read-storm-config))
(def ^:dynamic *UI-ACL-HANDLER* (mk-authorization-handler (*STORM-CONF* NIMBUS-AUTHORIZER) *STORM-CONF*))
(def ^:dynamic *UI-IMPERSONATION-HANDLER* (mk-authorization-handler (*STORM-CONF* NIMBUS-IMPERSONATION-AUTHORIZER) *STORM-CONF*))
(def http-creds-handler (AuthUtils/GetUiHttpCredentialsPlugin *STORM-CONF*))
(def STORM-VERSION (VersionInfo/getVersion))

(defmeter ui:num-cluster-configuration-http-requests)
(defmeter ui:num-cluster-summary-http-requests)
(defmeter ui:num-nimbus-summary-http-requests)
(defmeter ui:num-supervisor-summary-http-requests)
(defmeter ui:num-all-topologies-summary-http-requests)
(defmeter ui:num-topology-page-http-requests)
(defmeter ui:num-build-visualization-http-requests)
(defmeter ui:num-mk-visualization-data-http-requests)
(defmeter ui:num-component-page-http-requests)
(defmeter ui:num-log-config-http-requests)
(defmeter ui:num-activate-topology-http-requests)
(defmeter ui:num-deactivate-topology-http-requests)
(defmeter ui:num-debug-topology-http-requests)
(defmeter ui:num-component-op-response-http-requests)
(defmeter ui:num-topology-op-response-http-requests)
(defmeter ui:num-topology-op-response-http-requests)
(defmeter ui:num-topology-op-response-http-requests)
(defmeter ui:num-main-page-http-requests)

(defn assert-authorized-user
  ([op]
    (assert-authorized-user op nil))
  ([op topology-conf]
    (let [context (ReqContext/context)]
      (if (.isImpersonating context)
        (if *UI-IMPERSONATION-HANDLER*
            (if-not (.permit *UI-IMPERSONATION-HANDLER* context op topology-conf)
              (let [principal (.principal context)
                    real-principal (.realPrincipal context)
                    user (if principal (.getName principal) "unknown")
                    real-user (if real-principal (.getName real-principal) "unknown")
                    remote-address (.remoteAddress context)]
                (throw (AuthorizationException.
                         (str "user '" real-user "' is not authorized to impersonate user '" user "' from host '" remote-address "'. Please
                         see SECURITY.MD to learn how to configure impersonation ACL.")))))
          (log-warn " principal " (.realPrincipal context) " is trying to impersonate " (.principal context) " but "
            NIMBUS-IMPERSONATION-AUTHORIZER " has no authorizer configured. This is a potential security hole.
            Please see SECURITY.MD to learn how to configure an impersonation authorizer.")))

      (if *UI-ACL-HANDLER*
       (if-not (.permit *UI-ACL-HANDLER* context op topology-conf)
         (let [principal (.principal context)
               user (if principal (.getName principal) "unknown")]
           (throw (AuthorizationException.
                   (str "UI request '" op "' for '" user "' user is not authorized")))))))))

(defn executor-summary-type
  [topology ^ExecutorSummary s]
  (component-type topology (.getComponent_id s)))

(defn spout-summary?
  [topology s]
  (= :spout (executor-summary-type topology s)))

(defn bolt-summary?
  [topology s]
  (= :bolt (executor-summary-type topology s)))

(defn group-by-comp
  [summs]
  (let [ret (group-by #(.getComponent_id ^ExecutorSummary %) summs)]
    (into (sorted-map) ret )))

(defn logviewer-link [host fname secure?]
  (if (and secure? (*STORM-CONF* LOGVIEWER-HTTPS-PORT))
    (url-format "https://%s:%s/log?file=%s"
      host
      (*STORM-CONF* LOGVIEWER-HTTPS-PORT)
      fname)
    (url-format "http://%s:%s/log?file=%s"
      host
      (*STORM-CONF* LOGVIEWER-PORT)
      fname)))

(defn event-log-link
  [topology-id component-id host port secure?]
  (logviewer-link host (event-logs-filename topology-id port) secure?))

(defn worker-log-link [host port topology-id secure?]
  (if (or (empty? host) (let [port_str (str port "")] (or (empty? port_str) (= "0" port_str))))
    ""
    (let [fname (logs-filename topology-id port)]
      (logviewer-link host fname secure?))))

(defn nimbus-log-link [host]
  (url-format "http://%s:%s/daemonlog?file=nimbus.log" host (*STORM-CONF* LOGVIEWER-PORT)))

(defn supervisor-log-link [host]
  (url-format "http://%s:%s/daemonlog?file=supervisor.log" host (*STORM-CONF* LOGVIEWER-PORT)))

(defn get-error-data
  [error]
  (if error
    (error-subset (.getError ^ErrorInfo error))
    ""))

(defn get-error-port
  [error]
  (if error
    (.getPort ^ErrorInfo error)
    ""))

(defn get-error-host
  [error]
  (if error
    (.getHost ^ErrorInfo error)
    ""))

(defn get-error-time
  [error]
  (if error
    (.getError_time_secs ^ErrorInfo error)))

(defn worker-dump-link [host port topology-id]
  (url-format "http://%s:%s/dumps/%s/%s"
              (url-encode host)
              (*STORM-CONF* LOGVIEWER-PORT)
              (url-encode topology-id)
              (str (url-encode host) ":" (url-encode port))))

(defn stats-times
  [stats-map]
  (sort-by #(Integer/parseInt %)
           (-> stats-map
               clojurify-structure
               (dissoc ":all-time")
               keys)))

(defn window-hint
  [window]
  (if (= window ":all-time")
    "All time"
    (pretty-uptime-sec window)))

(defn sanitize-stream-name
  [name]
  (let [sym-regex #"(?![A-Za-z_\-:\.])."]
    (str
     (if (re-find #"^[A-Za-z]" name)
       (clojure.string/replace name sym-regex "_")
       (clojure.string/replace (str \s name) sym-regex "_"))
     (hash name))))

(defn sanitize-transferred
  [transferred]
  (into {}
        (for [[time, stream-map] transferred]
          [time, (into {}
                       (for [[stream, trans] stream-map]
                         [(sanitize-stream-name stream), trans]))])))

(defn visualization-data
  [spout-bolt spout-comp-summs bolt-comp-summs window storm-id]
  (let [components (for [[id spec] spout-bolt]
            [id
             (let [inputs (.getInputs (.getCommon spec))
                   bolt-summs (get bolt-comp-summs id)
                   spout-summs (get spout-comp-summs id)
                   bolt-cap (if bolt-summs
                              (compute-bolt-capacity bolt-summs)
                              0)]
               {:type (if bolt-summs "bolt" "spout")
                :capacity bolt-cap
                :latency (if bolt-summs
                           (get-in
                             (bolt-streams-stats bolt-summs true)
                             [:process-latencies window])
                           (get-in
                             (spout-streams-stats spout-summs true)
                             [:complete-latencies window]))
                :transferred (or
                               (get-in
                                 (spout-streams-stats spout-summs true)
                                 [:transferred window])
                               (get-in
                                 (bolt-streams-stats bolt-summs true)
                                 [:transferred window]))
                :stats (let [mapfn (fn [dat]
                                     (map (fn [^ExecutorSummary summ]
                                            {:host (.getHost summ)
                                             :port (.getPort summ)
                                             :uptime_secs (.getUptime_secs summ)
                                             :transferred (if-let [stats (.getStats summ)]
                                                            (sanitize-transferred (.getTransferred stats)))})
                                          dat))]
                         (if bolt-summs
                           (mapfn bolt-summs)
                           (mapfn spout-summs)))
                :link (url-format "/component.html?id=%s&topology_id=%s" id storm-id)
                :inputs (for [[global-stream-id group] inputs]
                          {:component (.getComponentId global-stream-id)
                           :stream (.getStreamId global-stream-id)
                           :sani-stream (sanitize-stream-name (.getStreamId global-stream-id))
                           :grouping (clojure.core/name (thrift/grouping-type group))})})])]
    (into {} (doall components))))

(defn stream-boxes [datmap]
  (let [filter-fn (mk-include-sys-fn true)
        streams
        (vec (doall (distinct
                     (apply concat
                            (for [[k v] datmap]
                              (for [m (get v :inputs)]
                                {:stream (get m :stream)
                                 :sani-stream (get m :sani-stream)
                                 :checked (not
                                            (Utils/isSystemId
                                              (get m :stream)))}))))))]
    (map (fn [row]
           {:row row}) (partition 4 4 nil streams))))

(defn- get-topology-info
  ([^Nimbus$Client nimbus id]
    (.getTopologyInfo nimbus id))
  ([^Nimbus$Client nimbus id options]
    (.getTopologyInfoWithOpts nimbus id options)))

(defn mk-visualization-data
  [id window include-sys?]
  (thrift/with-configured-nimbus-connection
    nimbus
    (let [window (if window window ":all-time")
          topology (.getTopology ^Nimbus$Client nimbus id)
          spouts (.getSpouts topology)
          bolts (.getBolts topology)
          summ (->> (doto
                      (GetInfoOptions.)
                      (.setNum_err_choice NumErrorsChoice/NONE))
                    (.getTopologyInfoWithOpts ^Nimbus$Client nimbus id))
          execs (.getExecutors summ)
          spout-summs (filter (partial spout-summary? topology) execs)
          bolt-summs (filter (partial bolt-summary? topology) execs)
          spout-comp-summs (group-by-comp spout-summs)
          bolt-comp-summs (group-by-comp bolt-summs)
          bolt-comp-summs (filter-key (mk-include-sys-fn include-sys?)
                                      bolt-comp-summs)]
      (visualization-data
       (merge (hashmap-to-persistent spouts)
              (hashmap-to-persistent bolts))
       spout-comp-summs bolt-comp-summs window id))))

(defn validate-tplg-submit-params [params]
  (let [tplg-jar-file (params :topologyJar)
        tplg-config (if (not-nil? (params :topologyConfig)) (from-json (params :topologyConfig)))]
    (cond
     (nil? tplg-jar-file) {:valid false :error "missing topology jar file"}
     (nil? tplg-config) {:valid false :error "missing topology config"}
     (nil? (tplg-config "topologyMainClass")) {:valid false :error "topologyMainClass missing in topologyConfig"}
     :else {:valid true})))

(defn run-tplg-submit-cmd [tplg-jar-file tplg-config user]
  (let [tplg-main-class (if (not-nil? tplg-config) (trim (tplg-config "topologyMainClass")))
        tplg-main-class-args (if (not-nil? tplg-config) (tplg-config "topologyMainClassArgs"))
        storm-home (System/getProperty "storm.home")
        storm-conf-dir (str storm-home file-path-separator "conf")
        storm-log-dir (if (not-nil? (*STORM-CONF* "storm.log.dir")) (*STORM-CONF* "storm.log.dir")
                          (str storm-home file-path-separator "logs"))
        storm-libs (str storm-home file-path-separator "lib" file-path-separator "*")
        java-cmd (str (System/getProperty "java.home") file-path-separator "bin" file-path-separator "java")
        storm-cmd (str storm-home file-path-separator "bin" file-path-separator "storm")
        tplg-cmd-response (apply sh
                            (flatten
                              [storm-cmd "jar" tplg-jar-file tplg-main-class
                                (if (not-nil? tplg-main-class-args) tplg-main-class-args [])
                                (if (not= user "unknown") (str "-c storm.doAsUser=" user) [])]))]
    (log-message "tplg-cmd-response " tplg-cmd-response)
    (cond
     (= (tplg-cmd-response :exit) 0) {"status" "success"}
     (and (not= (tplg-cmd-response :exit) 0)
          (not-nil? (re-find #"already exists on cluster" (tplg-cmd-response :err)))) {"status" "failed" "error" "Topology with the same name exists in cluster"}
          (not= (tplg-cmd-response :exit) 0) {"status" "failed" "error" (clojure.string/trim-newline (tplg-cmd-response :err))}
          :else {"status" "success" "response" "topology deployed"}
          )))

(defn cluster-configuration []
  (thrift/with-configured-nimbus-connection nimbus
    (.getNimbusConf ^Nimbus$Client nimbus)))

(defn topology-history-info
  ([user]
    (thrift/with-configured-nimbus-connection nimbus
      (topology-history-info (.getTopologyHistory ^Nimbus$Client nimbus user) user)))
  ([history user]
    {"topo-history"
     (into [] (.getTopo_ids history))}))

(defn cluster-summary
  ([user]
     (thrift/with-configured-nimbus-connection nimbus
        (cluster-summary (.getClusterInfo ^Nimbus$Client nimbus) user)))
  ([^ClusterSummary summ user]
     (let [sups (.getSupervisors summ)
           used-slots (reduce + (map #(.getNum_used_workers ^SupervisorSummary %) sups))
           total-slots (reduce + (map #(.getNum_workers ^SupervisorSummary %) sups))
           free-slots (- total-slots used-slots)
           topologies (.getTopologies_size summ)
           total-tasks (->> (.getTopologies summ)
                            (map #(.getNum_tasks ^TopologySummary %))
                            (reduce +))
           total-executors (->> (.getTopologies summ)
                                (map #(.getNum_executors ^TopologySummary %))
                                (reduce +))]
       {"user" user
        "stormVersion" STORM-VERSION
        "supervisors" (count sups)
        "topologies" topologies
        "slotsTotal" total-slots
        "slotsUsed"  used-slots
        "slotsFree" free-slots
        "executorsTotal" total-executors
        "tasksTotal" total-tasks })))

(defn convert-to-nimbus-summary[nimbus-seed]
  (let [[host port] (.split nimbus-seed ":")]
    {
      "host" host
      "port" port
      "nimbusLogLink" (nimbus-log-link host)
      "status" "Offline"
      "version" "Not applicable"
      "nimbusUpTime" "Not applicable"
      "nimbusUptimeSeconds" "Not applicable"}
    ))

(defn nimbus-summary
  ([]
    (thrift/with-configured-nimbus-connection nimbus
      (nimbus-summary
        (.getNimbuses (.getClusterInfo ^Nimbus$Client nimbus)))))
  ([nimbuses]
    (let [nimbus-seeds (set (map #(str %1 ":" (*STORM-CONF* NIMBUS-THRIFT-PORT)) (set (*STORM-CONF* NIMBUS-SEEDS))))
          alive-nimbuses (set (map #(str (.getHost %1) ":" (.getPort %1)) nimbuses))
          offline-nimbuses (clojure.set/difference nimbus-seeds alive-nimbuses)
          offline-nimbuses-summary (map #(convert-to-nimbus-summary %1) offline-nimbuses)]
      {"nimbuses"
       (concat offline-nimbuses-summary
       (for [^NimbusSummary n nimbuses
             :let [uptime (.getUptime_secs n)]]
         {
          "host" (.getHost n)
          "port" (.getPort n)
          "nimbusLogLink" (nimbus-log-link (.getHost n))
          "status" (if (.isIsLeader n) "Leader" "Not a Leader")
          "version" (.getVersion n)
          "nimbusUpTime" (pretty-uptime-sec uptime)
          "nimbusUpTimeSeconds" uptime}))})))

(defn supervisor-summary
  ([]
   (thrift/with-configured-nimbus-connection nimbus
                (supervisor-summary
                  (.getSupervisors (.getClusterInfo ^Nimbus$Client nimbus)))))
  ([summs]
   {"supervisors"
    (for [^SupervisorSummary s summs]
      {"id" (.getSupervisor_id s)
       "host" (.getHost s)
       "uptime" (pretty-uptime-sec (.getUptime_secs s))
       "uptimeSeconds" (.getUptime_secs s)
       "slotsTotal" (.getNum_workers s)
       "slotsUsed" (.getNum_used_workers s)
       "totalMem" (get (.getTotal_resources s) Config/SUPERVISOR_MEMORY_CAPACITY_MB)
       "totalCpu" (get (.getTotal_resources s) Config/SUPERVISOR_CPU_CAPACITY)
       "usedMem" (.getUsed_mem s)
       "usedCpu" (.getUsed_cpu s)
       "logLink" (supervisor-log-link (.getHost s))
       "version" (.getVersion s)})
    "schedulerDisplayResource" (*STORM-CONF* Config/SCHEDULER_DISPLAY_RESOURCE)}))

(defn all-topologies-summary
  ([]
   (thrift/with-configured-nimbus-connection
     nimbus
     (all-topologies-summary
       (.getTopologies (.getClusterInfo ^Nimbus$Client nimbus)))))
  ([summs]
   {"topologies"
    (for [^TopologySummary t summs]
      {
       "id" (.getId t)
       "encodedId" (url-encode (.getId t))
       "owner" (.getOwner t)
       "name" (.getName t)
       "status" (.getStatus t)
       "uptime" (pretty-uptime-sec (.getUptime_secs t))
       "uptimeSeconds" (.getUptime_secs t)
       "tasksTotal" (.getNum_tasks t)
       "workersTotal" (.getNum_workers t)
       "executorsTotal" (.getNum_executors t)
       "replicationCount" (.getReplication_count t)
       "schedulerInfo" (.getSched_status t)
       "requestedMemOnHeap" (.getRequested_memonheap t)
       "requestedMemOffHeap" (.getRequested_memoffheap t)
       "requestedTotalMem" (+ (.getRequested_memonheap t) (.getRequested_memoffheap t))
       "requestedCpu" (.getRequested_cpu t)
       "assignedMemOnHeap" (.getAssigned_memonheap t)
       "assignedMemOffHeap" (.getAssigned_memoffheap t)
       "assignedTotalMem" (+ (.getAssigned_memonheap t) (.getAssigned_memoffheap t))
       "assignedCpu" (.getAssigned_cpu t)})
    "schedulerDisplayResource" (*STORM-CONF* Config/SCHEDULER_DISPLAY_RESOURCE)}))

(defn topology-stats [window stats]
  (let [times (stats-times (:emitted stats))
        display-map (into {} (for [t times] [t pretty-uptime-sec]))
        display-map (assoc display-map ":all-time" (fn [_] "All time"))]
    (for [w (concat times [":all-time"])
          :let [disp ((display-map w) w)]]
      {"windowPretty" disp
       "window" w
       "emitted" (get-in stats [:emitted w])
       "transferred" (get-in stats [:transferred w])
       "completeLatency" (float-str (get-in stats [:complete-latencies w]))
       "acked" (get-in stats [:acked w])
       "failed" (get-in stats [:failed w])})))

(defn build-visualization [id window include-sys?]
  (thrift/with-configured-nimbus-connection nimbus
    (let [window (if window window ":all-time")
          topology-info (->> (doto
                               (GetInfoOptions.)
                               (.setNum_err_choice NumErrorsChoice/ONE))
                             (.getTopologyInfoWithOpts ^Nimbus$Client nimbus
                                                       id))
          storm-topology (.getTopology ^Nimbus$Client nimbus id)
          spout-executor-summaries (filter (partial spout-summary? storm-topology) (.getExecutors topology-info))
          bolt-executor-summaries (filter (partial bolt-summary? storm-topology) (.getExecutors topology-info))
          spout-comp-id->executor-summaries (group-by-comp spout-executor-summaries)
          bolt-comp-id->executor-summaries (group-by-comp bolt-executor-summaries)
          bolt-comp-id->executor-summaries (filter-key (mk-include-sys-fn include-sys?) bolt-comp-id->executor-summaries)
          id->spout-spec (.getSpouts storm-topology)
          id->bolt (.getBolts storm-topology)
          visualizer-data (visualization-data (merge (hashmap-to-persistent id->spout-spec)
                                                     (hashmap-to-persistent id->bolt))
                                              spout-comp-id->executor-summaries
                                              bolt-comp-id->executor-summaries
                                              window
                                              id)]
       {"visualizationTable" (stream-boxes visualizer-data)})))

(defn- get-error-json
  [topo-id error-info secure?]
  (let [host (get-error-host error-info)
        port (get-error-port error-info)]
    {"lastError" (get-error-data error-info)
     "errorTime" (get-error-time error-info)
     "errorHost" host
     "errorPort" port
     "errorLapsedSecs" (if-let [t (get-error-time error-info)] (time-delta t))
     "errorWorkerLogLink" (worker-log-link host port topo-id secure?)}))

(defn- common-agg-stats-json
  "Returns a JSON representation of a common aggregated statistics."
  [^CommonAggregateStats common-stats]
  {"executors" (.getNum_executors common-stats)
   "tasks" (.getNum_tasks common-stats)
   "emitted" (.getEmitted common-stats)
   "transferred" (.getTransferred common-stats)
   "acked" (.getAcked common-stats)
   "failed" (.getFailed common-stats)})

(defmulti comp-agg-stats-json
  "Returns a JSON representation of aggregated statistics."
  (fn [_ _ [id ^ComponentAggregateStats s]] (.getType s)))

(defmethod comp-agg-stats-json ComponentType/SPOUT
  [topo-id secure? [id ^ComponentAggregateStats s]]
  (let [^SpoutAggregateStats ss (.. s getSpecific_stats getSpout)
        cs (.getCommon_stats s)]
    (merge
      (common-agg-stats-json cs)
      (get-error-json topo-id (.getLast_error s) secure?)
      {"spoutId" id
       "encodedSpoutId" (url-encode id)
       "completeLatency" (float-str (.getComplete_latency_ms ss))})))

(defmethod comp-agg-stats-json ComponentType/BOLT
  [topo-id secure? [id ^ComponentAggregateStats s]]
  (let [^BoltAggregateStats ss (.. s get_specific_stats get_bolt)
        cs (.getCommon_stats s)]
    (merge
      (common-agg-stats-json cs)
      (get-error-json topo-id (.getLast_error s) secure?)
      {"boltId" id
       "encodedBoltId" (url-encode id)
       "capacity" (float-str (.getCapacity ss))
       "executeLatency" (float-str (.getExecute_latency_ms ss))
       "executed" (.getExecuted ss)
       "processLatency" (float-str (.getProcess_latency_ms ss))})))

(defn- unpack-topology-page-info
  "Unpacks the serialized object to data structures"
  [^TopologyPageInfo topo-info window secure?]
  (let [id (.getId topo-info)
        ^TopologyStats topo-stats (.getTopology_stats topo-info)
        stat->window->number
          {:emitted (.getWindow_to_emitted topo-stats)
           :transferred (.getWindow_to_transferred topo-stats)
           :complete-latencies (.getWindow_to_complete_latencies_ms topo-stats)
           :acked (.getWindow_to_acked topo-stats)
           :failed (.getWindow_to_failed topo-stats)}
        topo-stats (topology-stats window stat->window->number)
        [debugEnabled
         samplingPct] (if-let [debug-opts (.getDebug_options topo-info)]
                        [(.isEnable debug-opts)
                         (.getSamplingpct debug-opts)])
        uptime (.getUptime_secs topo-info)]
    {"id" id
     "encodedId" (url-encode id)
     "owner" (.getOwner topo-info)
     "name" (.getName topo-info)
     "status" (.getStatus topo-info)
     "uptime" (pretty-uptime-sec uptime)
     "uptimeSeconds" uptime
     "tasksTotal" (.getNum_tasks topo-info)
     "workersTotal" (.getNum_workers topo-info)
     "executorsTotal" (.getNum_executors topo-info)
     "schedulerInfo" (.getSched_status topo-info)
     "requestedMemOnHeap" (.getRequested_memonheap topo-info)
     "requestedMemOffHeap" (.getRequested_memoffheap topo-info)
     "requestedCpu" (.getRequested_cpu topo-info)
     "assignedMemOnHeap" (.getAssigned_memonheap topo-info)
     "assignedMemOffHeap" (.getAssigned_memoffheap topo-info)
     "assignedTotalMem" (+ (.getAssigned_memonheap topo-info) (.getAssigned_memoffheap topo-info))
     "assignedCpu" (.getAssigned_cpu topo-info)
     "topologyStats" topo-stats
     "spouts" (map (partial comp-agg-stats-json id secure?)
                   (.getId_to_spout_agg_stats topo-info))
     "bolts" (map (partial comp-agg-stats-json id secure?)
                  (.getId_to_bolt_agg_stats topo-info))
     "configuration" (.getTopology_conf topo-info)
     "debug" (or debugEnabled false)
     "samplingPct" (or samplingPct 10)
     "replicationCount" (.getReplication_count topo-info)}))

(defn exec-host-port
  [executors]
  (for [^ExecutorSummary e executors]
    {"host" (.getHost e)
     "port" (.getPort e)}))

(defn worker-host-port
  "Get the set of all worker host/ports"
  [id]
  (thrift/with-configured-nimbus-connection nimbus
    (distinct (exec-host-port (.getExecutors (get-topology-info nimbus id))))))

(defn topology-page [id window include-sys? user secure?]
  (thrift/with-configured-nimbus-connection nimbus
    (let [window (if window window ":all-time")
          window-hint (window-hint window)
          topo-page-info (.getTopologyPageInfo ^Nimbus$Client nimbus
                                               id
                                               window
                                               include-sys?)
          topology-conf (from-json (.getTopology_conf topo-page-info))
          msg-timeout (topology-conf TOPOLOGY-MESSAGE-TIMEOUT-SECS)]
      (merge
       (unpack-topology-page-info topo-page-info window secure?)
       {"user" user
        "window" window
        "windowHint" window-hint
        "msgTimeout" msg-timeout
        "configuration" topology-conf
        "visualizationTable" []
        "schedulerDisplayResource" (*STORM-CONF* Config/SCHEDULER_DISPLAY_RESOURCE)}))))

(defn component-errors
  [errors-list topology-id secure?]
  (let [errors (->> errors-list
                    (sort-by #(.getError_time_secs ^ErrorInfo %))
                    reverse)]
    {"componentErrors"
     (for [^ErrorInfo e errors]
       {"errorTime" (get-error-time e)
        "errorHost" (.getHost e)
        "errorPort"  (.getPort e)
        "errorWorkerLogLink"  (worker-log-link (.getHost e)
                                               (.getPort e)
                                               topology-id
                                               secure?)
        "errorLapsedSecs" (if-let [t (get-error-time e)] (time-delta t))
        "error" (.getError e)})}))

(defmulti unpack-comp-agg-stat
  (fn [[_ ^ComponentAggregateStats s]] (.getType s)))

(defmethod unpack-comp-agg-stat ComponentType/BOLT
  [[window ^ComponentAggregateStats s]]
  (let [^CommonAggregateStats comm-s (.getCommon_stats s)
        ^SpecificAggregateStats spec-s (.getSpecific_stats s)
        ^BoltAggregateStats bolt-s (.getBolt spec-s)]
    {"window" window
     "windowPretty" (window-hint window)
     "emitted" (.getEmitted comm-s)
     "transferred" (.getTransferred comm-s)
     "acked" (.getAcked comm-s)
     "failed" (.getFailed comm-s)
     "executeLatency" (float-str (.getExecute_latency_ms bolt-s))
     "processLatency"  (float-str (.getProcess_latency_ms bolt-s))
     "executed" (.getExecuted bolt-s)
     "capacity" (float-str (.getCapacity bolt-s))}))

(defmethod unpack-comp-agg-stat ComponentType/SPOUT
  [[window ^ComponentAggregateStats s]]
  (let [^CommonAggregateStats comm-s (.getCommon_stats s)
        ^SpecificAggregateStats spec-s (.getSpecific_stats s)
        ^SpoutAggregateStats spout-s (.getSpout spec-s)]
    {"window" window
     "windowPretty" (window-hint window)
     "emitted" (.getEmitted comm-s)
     "transferred" (.getTransferred comm-s)
     "acked" (.getAcked comm-s)
     "failed" (.getFailed comm-s)
     "completeLatency" (float-str (.getComplete_latency_ms spout-s))}))

(defn- unpack-bolt-input-stat
  [[^GlobalStreamId s ^ComponentAggregateStats stats]]
  (let [^SpecificAggregateStats sas (.getSpecific_stats stats)
        ^BoltAggregateStats bas (.getBolt sas)
        ^CommonAggregateStats cas (.getCommon_stats stats)
        comp-id (.getComponentId s)]
    {"component" comp-id
     "encodedComponentId" (url-encode comp-id)
     "stream" (.getStreamId s)
     "executeLatency" (float-str (.getExecute_latency_ms bas))
     "processLatency" (float-str (.getProcess_latency_ms bas))
     "executed" (nil-to-zero (.getExecuted bas))
     "acked" (nil-to-zero (.getAcked cas))
     "failed" (nil-to-zero (.getFailed cas))}))

(defmulti unpack-comp-output-stat
  (fn [[_ ^ComponentAggregateStats s]] (.getType s)))

(defmethod unpack-comp-output-stat ComponentType/BOLT
  [[stream-id ^ComponentAggregateStats stats]]
  (let [^CommonAggregateStats cas (.getCommon_stats stats)]
    {"stream" stream-id
     "emitted" (nil-to-zero (.getEmitted cas))
     "transferred" (nil-to-zero (.getTransferred cas))}))

(defmethod unpack-comp-output-stat ComponentType/SPOUT
  [[stream-id ^ComponentAggregateStats stats]]
  (let [^CommonAggregateStats cas (.getCommon_stats stats)
        ^SpecificAggregateStats spec-s (.getSpecific_stats stats)
        ^SpoutAggregateStats spout-s (.getSpout spec-s)]
    {"stream" stream-id
     "emitted" (nil-to-zero (.getEmitted cas))
     "transferred" (nil-to-zero (.getTransferred cas))
     "completeLatency" (float-str (.getComplete_latency_ms spout-s))
     "acked" (nil-to-zero (.getAcked cas))
     "failed" (nil-to-zero (.getFailed cas))}))

(defmulti unpack-comp-exec-stat
  (fn [_ _ ^ComponentAggregateStats cas] (.getType (.getStats ^ExecutorAggregateStats cas))))

(defmethod unpack-comp-exec-stat ComponentType/BOLT
  [topology-id secure? ^ExecutorAggregateStats eas]
  (let [^ExecutorSummary summ (.getExec_summary eas)
        ^ExecutorInfo info (.getExecutor_info summ)
        ^ComponentAggregateStats stats (.getStats eas)
        ^SpecificAggregateStats ss (.getSpecific_stats stats)
        ^BoltAggregateStats bas (.getBolt ss)
        ^CommonAggregateStats cas (.getCommon_stats stats)
        host (.getHost summ)
        port (.getPort summ)
        exec-id (pretty-executor-info info)
        uptime (.getUptime_secs summ)]
    {"id" exec-id
     "encodedId" (url-encode exec-id)
     "uptime" (pretty-uptime-sec uptime)
     "uptimeSeconds" uptime
     "host" host
     "port" port
     "emitted" (nil-to-zero (.getEmitted cas))
     "transferred" (nil-to-zero (.getTransferred cas))
     "capacity" (float-str (nil-to-zero (.getCapacity bas)))
     "executeLatency" (float-str (.getExecute_latency_ms bas))
     "executed" (nil-to-zero (.getExecuted bas))
     "processLatency" (float-str (.getProcess_latency_ms bas))
     "acked" (nil-to-zero (.getAcked cas))
     "failed" (nil-to-zero (.getFailed cas))
     "workerLogLink" (worker-log-link host port topology-id secure?)}))

(defmethod unpack-comp-exec-stat ComponentType/SPOUT
  [topology-id secure? ^ExecutorAggregateStats eas]
  (let [^ExecutorSummary summ (.getExec_summary eas)
        ^ExecutorInfo info (.getExecutor_info summ)
        ^ComponentAggregateStats stats (.getStats eas)
        ^SpecificAggregateStats ss (.getSpecific_stats stats)
        ^SpoutAggregateStats sas (.getSpout ss)
        ^CommonAggregateStats cas (.getCommon_stats stats)
        host (.getHost summ)
        port (.getPort summ)
        exec-id (pretty-executor-info info)
        uptime (.getUptime_secs summ)]
    {"id" exec-id
     "encodedId" (url-encode exec-id)
     "uptime" (pretty-uptime-sec uptime)
     "uptimeSeconds" uptime
     "host" host
     "port" port
     "emitted" (nil-to-zero (.getEmitted cas))
     "transferred" (nil-to-zero (.getTransferred cas))
     "completeLatency" (float-str (.getComplete_latency_ms sas))
     "acked" (nil-to-zero (.getAcked cas))
     "failed" (nil-to-zero (.getFailed cas))
     "workerLogLink" (worker-log-link host port topology-id secure?)}))

(defmulti unpack-component-page-info
  "Unpacks component-specific info to clojure data structures"
  (fn [^ComponentPageInfo info & _]
    (.getComponent_type info)))

(defmethod unpack-component-page-info ComponentType/BOLT
  [^ComponentPageInfo info topology-id window include-sys? secure?]
  (merge
    {"boltStats" (map unpack-comp-agg-stat (.getWindow_to_stats info))
     "inputStats" (map unpack-bolt-input-stat (.getGsid_to_input_stats info))
     "outputStats" (map unpack-comp-output-stat (.getSid_to_output_stats info))
     "executorStats" (map (partial unpack-comp-exec-stat topology-id secure?)
                          (.getExec_stats info))}
    (-> info .get_errors (component-errors topology-id secure?))))

(defmethod unpack-component-page-info ComponentType/SPOUT
  [^ComponentPageInfo info topology-id window include-sys? secure?]
  (merge
    {"spoutSummary" (map unpack-comp-agg-stat (.getWindow_to_stats info))
     "outputStats" (map unpack-comp-output-stat (.getSid_to_output_stats info))
     "executorStats" (map (partial unpack-comp-exec-stat topology-id secure?)
                          (.getExec_stats info))}
    (-> info .get_errors (component-errors topology-id secure?))))

(defn get-active-profile-actions
  [nimbus topology-id component]
  (let [profile-actions  (.getComponentPendingProfileActions nimbus
                                               topology-id
                                               component
                                 ProfileAction/JPROFILE_STOP)
        latest-profile-actions (map clojurify-profile-request profile-actions)
        active-actions (map (fn [profile-action]
                              {"host" (:host profile-action)
                               "port" (str (:port profile-action))
                               "dumplink" (worker-dump-link (:host profile-action) (str (:port profile-action)) topology-id)
                               "timestamp" (str (- (:timestamp profile-action) (System/currentTimeMillis)))})
                            latest-profile-actions)]
    (log-message "Latest-active actions are: " (pr-str active-actions))
    active-actions))

(defn component-page
  [topology-id component window include-sys? user secure?]
  (thrift/with-configured-nimbus-connection nimbus
    (let [window (or window ":all-time")
          window-hint (window-hint window)
          comp-page-info (.getComponentPageInfo ^Nimbus$Client nimbus
                                                topology-id
                                                component
                                                window
                                                include-sys?)
          topology-conf (from-json (.getTopologyConf ^Nimbus$Client nimbus
                                                     topology-id))
          msg-timeout (topology-conf TOPOLOGY-MESSAGE-TIMEOUT-SECS)
          [debugEnabled
           samplingPct] (if-let [debug-opts (.getDebug_options comp-page-info)]
                          [(.isEnable debug-opts)
                           (.getSamplingpct debug-opts)])]
      (assoc
       (unpack-component-page-info comp-page-info
                                   topology-id
                                   window
                                   include-sys?
                                   secure?)
       "user" user
       "id" component
       "encodedId" (url-encode component)
       "name" (.getTopology_name comp-page-info)
       "executors" (.getNum_executors comp-page-info)
       "tasks" (.getNum_tasks comp-page-info)
       "topologyId" topology-id
       "topologyStatus" (.getTopology_status comp-page-info)
       "encodedTopologyId" (url-encode topology-id)
       "window" window
       "componentType" (-> comp-page-info .getComponent_type str lower-case)
       "windowHint" window-hint
       "debug" (or debugEnabled false)
       "samplingPct" (or samplingPct 10)
       "eventLogLink" (event-log-link topology-id
                                      component
                                      (.getEventlog_host comp-page-info)
                                      (.getEventlog_port comp-page-info)
                                      secure?)
       "profilingAndDebuggingCapable" (not on-windows?)
       "profileActionEnabled" (*STORM-CONF* WORKER-PROFILER-ENABLED)
       "profilerActive" (if (*STORM-CONF* WORKER-PROFILER-ENABLED)
                          (get-active-profile-actions nimbus topology-id component)
                          [])))))
    
(defn- level-to-dict [level]
  (if level
    (let [timeout (.getReset_log_level_timeout_secs level)
          timeout-epoch (.getReset_log_level_timeout_epoch level)
          target-level (.getTarget_log_level level)
          reset-level (.getReset_log_level level)]
          {"target_level" (.toString (Level/toLevel target-level))
           "reset_level" (.toString (Level/toLevel reset-level))
           "timeout" timeout
           "timeout_epoch" timeout-epoch})))

(defn log-config [topology-id]
  (thrift/with-configured-nimbus-connection
    nimbus
    (let [log-config (.getLogConfig ^Nimbus$Client nimbus topology-id)
          named-logger-levels (into {}
                                (for [[key val] (.getNamed_logger_level log-config)]
                                  [(str key) (level-to-dict val)]))]
      {"namedLoggerLevels" named-logger-levels})))

(defn topology-config [topology-id]
  (thrift/with-configured-nimbus-connection nimbus
    (from-json (.getTopologyConf ^Nimbus$Client nimbus topology-id))))

(defn topology-op-response [topology-id op]
  {"topologyOperation" op,
   "topologyId" topology-id,
   "status" "success"
   })

(defn component-op-response [topology-id component-id op]
  {"topologyOperation" op,
   "topologyId" topology-id,
   "componentId" component-id,
   "status" "success"
   })

(defn check-include-sys?
  [sys?]
  (if (or (nil? sys?) (= "false" sys?)) false true))

(def http-creds-handler (AuthUtils/GetUiHttpCredentialsPlugin *STORM-CONF*))

(defn populate-context!
  "Populate the Storm RequestContext from an servlet-request. This should be called in each handler"
  [servlet-request]
    (when http-creds-handler
      (.populateContext http-creds-handler (ReqContext/context) servlet-request)))

(defn get-user-name
  [servlet-request]
  (.getUserName http-creds-handler servlet-request))

(defn json-profiling-disabled
  "Return a JSON response communicating that profiling is disabled and
  therefore unavailable."
  [callback]
  (json-response {"status" "disabled",
                  "message" "Profiling is not enabled on this server"}
                 callback
                 :status 501))

(defroutes main-routes
  (GET "/api/v1/cluster/configuration" [& m]
    (mark! ui:num-cluster-configuration-http-requests)
    (json-response (cluster-configuration)
                   (:callback m) :serialize-fn identity))
  (GET "/api/v1/cluster/summary" [:as {:keys [cookies servlet-request]} & m]
    (mark! ui:num-cluster-summary-http-requests)
    (populate-context! servlet-request)
    (assert-authorized-user "getClusterInfo")
    (let [user (get-user-name servlet-request)]
      (json-response (assoc (cluster-summary user)
                          "bugtracker-url" (*STORM-CONF* UI-PROJECT-BUGTRACKER-URL)
                          "central-log-url" (*STORM-CONF* UI-CENTRAL-LOGGING-URL)) (:callback m))))
  (GET "/api/v1/nimbus/summary" [:as {:keys [cookies servlet-request]} & m]
    (mark! ui:num-nimbus-summary-http-requests)
    (populate-context! servlet-request)
    (assert-authorized-user "getClusterInfo")
    (json-response (nimbus-summary) (:callback m)))
  (GET "/api/v1/history/summary" [:as {:keys [cookies servlet-request]} & m]
    (let [user (.getUserName http-creds-handler servlet-request)]
      (json-response (topology-history-info user) (:callback m))))
  (GET "/api/v1/supervisor/summary" [:as {:keys [cookies servlet-request]} & m]
    (mark! ui:num-supervisor-summary-http-requests)
    (populate-context! servlet-request)
    (assert-authorized-user "getClusterInfo")
    (json-response (assoc (supervisor-summary)
                     "logviewerPort" (*STORM-CONF* LOGVIEWER-PORT)) (:callback m)))
  (GET "/api/v1/topology/summary" [:as {:keys [cookies servlet-request]} & m]
    (mark! ui:num-all-topologies-summary-http-requests)
    (populate-context! servlet-request)
    (assert-authorized-user "getClusterInfo")
    (json-response (all-topologies-summary) (:callback m)))
  (GET  "/api/v1/topology-workers/:id" [:as {:keys [cookies servlet-request]} id & m]
    (let [id (url-decode id)]
      (json-response {"hostPortList" (worker-host-port id)
                      "logviewerPort" (*STORM-CONF* LOGVIEWER-PORT)} (:callback m))))
  (GET "/api/v1/topology/:id" [:as {:keys [cookies servlet-request scheme]} id & m]
    (mark! ui:num-topology-page-http-requests)
    (populate-context! servlet-request)
    (assert-authorized-user "getTopology" (topology-config id))
    (let [user (get-user-name servlet-request)]
      (json-response (topology-page id (:window m) (check-include-sys? (:sys m)) user (= scheme :https)) (:callback m))))
  (GET "/api/v1/topology/:id/visualization-init" [:as {:keys [cookies servlet-request]} id & m]
    (mark! ui:num-build-visualization-http-requests)
    (populate-context! servlet-request)
    (assert-authorized-user "getTopology" (topology-config id))
    (json-response (build-visualization id (:window m) (check-include-sys? (:sys m))) (:callback m)))
  (GET "/api/v1/topology/:id/visualization" [:as {:keys [cookies servlet-request]} id & m]
    (mark! ui:num-mk-visualization-data-http-requests)
    (populate-context! servlet-request)
    (assert-authorized-user "getTopology" (topology-config id))
    (json-response (mk-visualization-data id (:window m) (check-include-sys? (:sys m))) (:callback m)))
  (GET "/api/v1/topology/:id/component/:component" [:as {:keys [cookies servlet-request scheme]} id component & m]
    (mark! ui:num-component-page-http-requests)
    (populate-context! servlet-request)
    (assert-authorized-user "getTopology" (topology-config id))
    (let [user (get-user-name servlet-request)]
      (json-response
          (component-page id component (:window m) (check-include-sys? (:sys m)) user (= scheme :https))
          (:callback m))))
  (GET "/api/v1/topology/:id/logconfig" [:as {:keys [cookies servlet-request]} id & m]
    (mark! ui:num-log-config-http-requests)
    (populate-context! servlet-request)
    (assert-authorized-user "getTopology" (topology-config id))
       (json-response (log-config id) (:callback m)))
  (POST "/api/v1/topology/:id/activate" [:as {:keys [cookies servlet-request]} id & m]
    (mark! ui:num-activate-topology-http-requests)
    (populate-context! servlet-request)
    (assert-authorized-user "activate" (topology-config id))
    (thrift/with-configured-nimbus-connection nimbus
       (let [tplg (->> (doto
                        (GetInfoOptions.)
                        (.setNum_err_choice NumErrorsChoice/NONE))
                      (.getTopologyInfoWithOpts ^Nimbus$Client nimbus id))
            name (.getName tplg)]
        (.activate nimbus name)
        (log-message "Activating topology '" name "'")))
    (json-response (topology-op-response id "activate") (m "callback")))
  (POST "/api/v1/topology/:id/deactivate" [:as {:keys [cookies servlet-request]} id & m]
    (mark! ui:num-deactivate-topology-http-requests)
    (populate-context! servlet-request)
    (assert-authorized-user "deactivate" (topology-config id))
    (thrift/with-configured-nimbus-connection nimbus
        (let [tplg (->> (doto
                        (GetInfoOptions.)
                        (.setNum_err_choice NumErrorsChoice/NONE))
                      (.getTopologyInfoWithOpts ^Nimbus$Client nimbus id))
            name (.getName tplg)]
        (.deactivate nimbus name)
        (log-message "Deactivating topology '" name "'")))
    (json-response (topology-op-response id "deactivate") (m "callback")))
  (POST "/api/v1/topology/:id/debug/:action/:spct" [:as {:keys [cookies servlet-request]} id action spct & m]
    (mark! ui:num-debug-topology-http-requests)
    (populate-context! servlet-request)
    (assert-authorized-user "debug" (topology-config id))
    (thrift/with-configured-nimbus-connection nimbus
        (let [tplg (->> (doto
                        (GetInfoOptions.)
                        (.setNum_err_choice NumErrorsChoice/NONE))
                   (.getTopologyInfoWithOpts ^Nimbus$Client nimbus id))
            name (.getName tplg)
            enable? (= "enable" action)]
        (.debug nimbus name "" enable? (Integer/parseInt spct))
        (log-message "Debug topology [" name "] action [" action "] sampling pct [" spct "]")))
     (json-response (topology-op-response id (str "debug/" action)) (m "callback")))
  (POST "/api/v1/topology/:id/component/:component/debug/:action/:spct" [:as {:keys [cookies servlet-request]} id component action spct & m]
    (mark! ui:num-component-op-response-http-requests)
    (populate-context! servlet-request)
    (assert-authorized-user "debug" (topology-config id))
    (thrift/with-configured-nimbus-connection nimbus
      (let [tplg (->> (doto
                        (GetInfoOptions.)
                        (.setNum_err_choice NumErrorsChoice/NONE))
                   (.getTopologyInfoWithOpts ^Nimbus$Client nimbus id))
            name (.getName tplg)
            enable? (= "enable" action)]
        (.debug nimbus name component enable? (Integer/parseInt spct))
        (log-message "Debug topology [" name "] component [" component "] action [" action "] sampling pct [" spct "]")))
    (json-response (component-op-response id component (str "/debug/" action)) (m "callback")))
  (POST "/api/v1/topology/:id/rebalance/:wait-time" [:as {:keys [cookies servlet-request]} id wait-time & m]
    (mark! ui:num-topology-op-response-http-requests)
    (populate-context! servlet-request)
    (assert-authorized-user "rebalance" (topology-config id))
    (thrift/with-configured-nimbus-connection nimbus
      (let [tplg (->> (doto
                        (GetInfoOptions.)
                        (.setNum_err_choice NumErrorsChoice/NONE))
                      (.getTopologyInfoWithOpts ^Nimbus$Client nimbus id))
            name (.getName tplg)
            rebalance-options (m "rebalanceOptions")
            options (RebalanceOptions.)]
        (.setWait_secs options (Integer/parseInt wait-time))
        (if (and (not-nil? rebalance-options) (contains? rebalance-options "numWorkers"))
          (.setNum_workers options (Integer/parseInt (.toString (rebalance-options "numWorkers")))))
        (if (and (not-nil? rebalance-options) (contains? rebalance-options "executors"))
          (doseq [keyval (rebalance-options "executors")]
            (.putTo_num_executors options (key keyval) (Integer/parseInt (.toString (val keyval))))))
        (.rebalance nimbus name options)
        (log-message "Rebalancing topology '" name "' with wait time: " wait-time " secs")))
    (json-response (topology-op-response id "rebalance") (m "callback")))
  (POST "/api/v1/topology/:id/kill/:wait-time" [:as {:keys [cookies servlet-request]} id wait-time & m]
    (mark! ui:num-topology-op-response-http-requests)
    (populate-context! servlet-request)
    (assert-authorized-user "killTopology" (topology-config id))
    (thrift/with-configured-nimbus-connection nimbus
      (let [tplg (->> (doto
                        (GetInfoOptions.)
                        (.setNum_err_choice NumErrorsChoice/NONE))
                      (.getTopologyInfoWithOpts ^Nimbus$Client nimbus id))
            name (.getName tplg)
            options (KillOptions.)]
        (.setWait_secs options (Integer/parseInt wait-time))
        (.killTopologyWithOpts nimbus name options)
        (log-message "Killing topology '" name "' with wait time: " wait-time " secs")))
    (json-response (topology-op-response id "kill") (m "callback")))
  (POST "/api/v1/topology/:id/logconfig" [:as {:keys [cookies servlet-request]} id namedLoggerLevels & m]
    (mark! ui:num-topology-op-response-http-requests)
    (populate-context! servlet-request)
    (assert-authorized-user "setLogConfig" (topology-config id))
    (thrift/with-configured-nimbus-connection
      nimbus
      (let [new-log-config (LogConfig.)]
        (doseq [[key level] namedLoggerLevels]
            (let [logger-name (str key)
                  target-level (.get level "target_level")
                  timeout (or (.get level "timeout") 0)
                  named-logger-level (LogLevel.)]
              ;; if target-level is nil, do not set it, user wants to clear
              (log-message "The target level for " logger-name " is " target-level)
              (if (nil? target-level)
                (do
                  (.setAction named-logger-level LogLevelAction/REMOVE)
                  (.unsetTarget_log_level named-logger-level))
                (do
                  (.setAction named-logger-level LogLevelAction/UPDATE)
                  ;; the toLevel here ensures the string we get is valid
                  (.setTarget_log_level named-logger-level (.name (Level/toLevel target-level)))
                  (.setReset_log_level_timeout_secs named-logger-level timeout)))
              (log-message "Adding this " logger-name " " named-logger-level " to " new-log-config)
              (.putTo_named_logger_level new-log-config logger-name named-logger-level)))
        (log-message "Setting topology " id " log config " new-log-config)
        (.setLogConfig nimbus id new-log-config)
        (json-response (log-config id) (m "callback")))))

  (GET "/api/v1/topology/:id/profiling/start/:host-port/:timeout"
       [:as {:keys [servlet-request]} id host-port timeout & m]
       (if (get *STORM-CONF* WORKER-PROFILER-ENABLED)
         (do
           (populate-context! servlet-request)
           (thrift/with-configured-nimbus-connection nimbus
             (assert-authorized-user "setWorkerProfiler" (topology-config id))
             (let [[host, port] (split host-port #":")
                   nodeinfo (NodeInfo. host (set [(Long. port)]))
                   timestamp (+ (System/currentTimeMillis) (* 60000 (Long. timeout)))
                   request (ProfileRequest. nodeinfo
                                            ProfileAction/JPROFILE_STOP)]
               (.setTime_stamp request timestamp)
               (.setWorkerProfiler nimbus id request)
               (json-response {"status" "ok"
                               "id" host-port
                               "timeout" timeout
                               "dumplink" (worker-dump-link
                                           host
                                           port
                                           id)}
                              (m "callback")))))
         (json-profiling-disabled (m "callback"))))

  (GET "/api/v1/topology/:id/profiling/stop/:host-port"
       [:as {:keys [servlet-request]} id host-port & m]
       (if (get *STORM-CONF* WORKER-PROFILER-ENABLED)
         (do
           (populate-context! servlet-request)
           (thrift/with-configured-nimbus-connection nimbus
             (assert-authorized-user "setWorkerProfiler" (topology-config id))
             (let [[host, port] (split host-port #":")
                   nodeinfo (NodeInfo. host (set [(Long. port)]))
                   timestamp 0
                   request (ProfileRequest. nodeinfo
                                            ProfileAction/JPROFILE_STOP)]
               (.setTime_stamp request timestamp)
               (.setWorkerProfiler nimbus id request)
               (json-response {"status" "ok"
                               "id" host-port}
                              (m "callback")))))
         (json-profiling-disabled (m "callback"))))

  (GET "/api/v1/topology/:id/profiling/dumpprofile/:host-port"
       [:as {:keys [servlet-request]} id host-port & m]
       (if (get *STORM-CONF* WORKER-PROFILER-ENABLED)
         (do
           (populate-context! servlet-request)
           (thrift/with-configured-nimbus-connection nimbus
             (assert-authorized-user "setWorkerProfiler" (topology-config id))
             (let [[host, port] (split host-port #":")
                   nodeinfo (NodeInfo. host (set [(Long. port)]))
                   timestamp (System/currentTimeMillis)
                   request (ProfileRequest. nodeinfo
                                            ProfileAction/JPROFILE_DUMP)]
               (.setTime_stamp request timestamp)
               (.setWorkerProfiler nimbus id request)
               (json-response {"status" "ok"
                               "id" host-port}
                              (m "callback")))))
         (json-profiling-disabled (m "callback"))))

  (GET "/api/v1/topology/:id/profiling/dumpjstack/:host-port"
       [:as {:keys [servlet-request]} id host-port & m]
       (populate-context! servlet-request)
       (thrift/with-configured-nimbus-connection nimbus
         (assert-authorized-user "setWorkerProfiler" (topology-config id))
         (let [[host, port] (split host-port #":")
               nodeinfo (NodeInfo. host (set [(Long. port)]))
               timestamp (System/currentTimeMillis)
               request (ProfileRequest. nodeinfo
                                        ProfileAction/JSTACK_DUMP)]
           (.setTime_stamp request timestamp)
           (.setWorkerProfiler nimbus id request)
           (json-response {"status" "ok"
                           "id" host-port}
                          (m "callback")))))

  (GET "/api/v1/topology/:id/profiling/restartworker/:host-port"
       [:as {:keys [servlet-request]} id host-port & m]
       (populate-context! servlet-request)
       (thrift/with-configured-nimbus-connection nimbus
         (assert-authorized-user "setWorkerProfiler" (topology-config id))
         (let [[host, port] (split host-port #":")
               nodeinfo (NodeInfo. host (set [(Long. port)]))
               timestamp (System/currentTimeMillis)
               request (ProfileRequest. nodeinfo
                                        ProfileAction/JVM_RESTART)]
           (.setTime_stamp request timestamp)
           (.setWorkerProfiler nimbus id request)
           (json-response {"status" "ok"
                           "id" host-port}
                          (m "callback")))))
       
  (GET "/api/v1/topology/:id/profiling/dumpheap/:host-port"
       [:as {:keys [servlet-request]} id host-port & m]
       (populate-context! servlet-request)
       (thrift/with-configured-nimbus-connection nimbus
         (assert-authorized-user "setWorkerProfiler" (topology-config id))
         (let [[host, port] (split host-port #":")
               nodeinfo (NodeInfo. host (set [(Long. port)]))
               timestamp (System/currentTimeMillis)
               request (ProfileRequest. nodeinfo
                                        ProfileAction/JMAP_DUMP)]
           (.setTime_stamp request timestamp)
           (.setWorkerProfiler nimbus id request)
           (json-response {"status" "ok"
                           "id" host-port}
                          (m "callback")))))
  
  (GET "/" [:as {cookies :cookies}]
    (mark! ui:num-main-page-http-requests)
    (resp/redirect "/index.html"))
  (route/resources "/")
  (route/not-found "Page not found"))

(defn catch-errors
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception ex
        (json-response (exception->json ex) ((:query-params request) "callback") :status 500)))))

(def app
  (handler/site (-> main-routes
                    (wrap-json-params)
                    (wrap-multipart-params)
                    (wrap-reload '[org.apache.storm.ui.core])
                    requests-middleware
                    catch-errors)))

(defn start-server!
  []
  (try
    (let [conf *STORM-CONF*
          header-buffer-size (int (.get conf UI-HEADER-BUFFER-BYTES))
          filters-confs [{:filter-class (conf UI-FILTER)
                          :filter-params (conf UI-FILTER-PARAMS)}]
          https-port (if (not-nil? (conf UI-HTTPS-PORT)) (conf UI-HTTPS-PORT) 0)
          https-ks-path (conf UI-HTTPS-KEYSTORE-PATH)
          https-ks-password (conf UI-HTTPS-KEYSTORE-PASSWORD)
          https-ks-type (conf UI-HTTPS-KEYSTORE-TYPE)
          https-key-password (conf UI-HTTPS-KEY-PASSWORD)
          https-ts-path (conf UI-HTTPS-TRUSTSTORE-PATH)
          https-ts-password (conf UI-HTTPS-TRUSTSTORE-PASSWORD)
          https-ts-type (conf UI-HTTPS-TRUSTSTORE-TYPE)
          https-want-client-auth (conf UI-HTTPS-WANT-CLIENT-AUTH)
          https-need-client-auth (conf UI-HTTPS-NEED-CLIENT-AUTH)
          http-x-frame-options (conf UI-HTTP-X-FRAME-OPTIONS)]
      (start-metrics-reporters conf)
      (validate-x-frame-options! http-x-frame-options)
      (storm-run-jetty {:port (conf UI-PORT)
                        :host (conf UI-HOST)
                        :https-port https-port
                        :configurator (fn [server]
                                        (config-ssl server
                                                    https-port
                                                    https-ks-path
                                                    https-ks-password
                                                    https-ks-type
                                                    https-key-password
                                                    https-ts-path
                                                    https-ts-password
                                                    https-ts-type
                                                    https-need-client-auth
                                                    https-want-client-auth)
                                        (doseq [connector (.getConnectors server)]
                                          (.setRequestHeaderSize connector header-buffer-size))
                                        (config-filter server app filters-confs http-x-frame-options))}))
   (catch Exception ex
     (log-error ex))))

(defn -main
  []
  (log-message "Starting ui server for storm version '" STORM-VERSION "'")
  (start-server!))

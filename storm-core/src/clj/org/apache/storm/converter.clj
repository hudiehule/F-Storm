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
(ns org.apache.storm.converter
  (:import [org.apache.storm.generated SupervisorInfo NodeInfo Assignment WorkerResources
            StormBase TopologyStatus ClusterWorkerHeartbeat ExecutorInfo ErrorInfo Credentials RebalanceOptions KillOptions
            TopologyActionOptions DebugOptions ProfileRequest])
  (:use [org.apache.storm util stats log])
  (:require [org.apache.storm.daemon [common :as common]]))

(defn thriftify-supervisor-info [supervisor-info]
  (doto (SupervisorInfo.)
    (.setTime_secs (long (:time-secs supervisor-info)))
    (.setHostname (:hostname supervisor-info))
    (.setAssignment_id (:assignment-id supervisor-info))
    (.setUsed_ports (map long (:used-ports supervisor-info)))
    (.setMeta (map long (:meta supervisor-info)))
    (.setScheduler_meta (:scheduler-meta supervisor-info))
    (.setUptime_secs (long (:uptime-secs supervisor-info)))
    (.setVersion (:version supervisor-info))
    (.setResources_map (:resources-map supervisor-info))
    ))

(defn clojurify-supervisor-info [^SupervisorInfo supervisor-info]
  (if supervisor-info
    (org.apache.storm.daemon.common.SupervisorInfo.
      (.getTime_secs supervisor-info)
      (.getHostname supervisor-info)
      (.getAssignment_id supervisor-info)
      (if (.getUsed_ports supervisor-info) (into [] (.getUsed_ports supervisor-info)))
      (if (.getMeta supervisor-info) (into [] (.getMeta supervisor-info)))
      (if (.getScheduler_meta supervisor-info) (into {} (.getScheduler_meta supervisor-info)))
      (.getUptime_secs supervisor-info)
      (.getVersion supervisor-info)
      (if-let [res-map (.getResources_map supervisor-info)] (into {} res-map)))))

(defn thriftify-assignment [assignment]
  (let [thrift-assignment (doto (Assignment.)
                            (.setMaster_code_dir (:master-code-dir assignment))
                            (.setNode_host (:node->host assignment))
                            (.setExecutor_node_port (into {}
                                                           (map (fn [[k v]]
                                                                  [(map long k)
                                                                   (NodeInfo. (first v) (set (map long (rest v))))])
                                                                (:executor->node+port assignment))))
                            (.setExecutor_start_time_secs
                              (into {}
                                    (map (fn [[k v]]
                                           [(map long k) (long v)])
                                         (:executor->start-time-secs assignment)))))]
    (if (:worker->resources assignment)
      (.setWorker_resources thrift-assignment (into {} (map
                                                          (fn [[node+port resources]]
                                                            [(NodeInfo. (first node+port) (set (map long (rest node+port))))
                                                             (doto (WorkerResources.)
                                                               (.setMem_on_heap (first resources))
                                                               (.setMem_off_heap (second resources))
                                                               (.setCpu (last resources)))])
                                                          (:worker->resources assignment)))))
    thrift-assignment))

(defn clojurify-executor->node_port [executor->node_port]
  (into {}
    (map-val
      (fn [nodeInfo]
        (concat [(.getNode nodeInfo)] (.getPort nodeInfo))) ;nodeInfo should be converted to [node,port1,port2..]
      (map-key
        (fn [list-of-executors]
          (into [] list-of-executors)) ; list of executors must be coverted to clojure vector to ensure it is sortable.
        executor->node_port))))

(defn clojurify-worker->resources [worker->resources]
  "convert worker info to be [node, port]
   convert resources to be [mem_on_heap mem_off_heap cpu]"
  (into {} (map
             (fn [[nodeInfo resources]]
               [(concat [(.getNode nodeInfo)] (.getPort nodeInfo))
                [(.getMem_on_heap resources) (.getMem_off_heap resources) (.getCpu resources)]])
             worker->resources)))

(defn clojurify-assignment [^Assignment assignment]
  (if assignment
    (org.apache.storm.daemon.common.Assignment.
      (.getMaster_code_dir assignment)
      (into {} (.getNode_host assignment))
      (clojurify-executor->node_port (into {} (.getExecutor_node_port assignment)))
      (map-key (fn [executor] (into [] executor))
        (into {} (.getExecutor_start_time_secs assignment)))
      (clojurify-worker->resources (into {} (.getWorker_resources assignment))))))

(defn convert-to-symbol-from-status [status]
  (condp = status
    TopologyStatus/ACTIVE {:type :active}
    TopologyStatus/INACTIVE {:type :inactive}
    TopologyStatus/REBALANCING {:type :rebalancing}
    TopologyStatus/KILLED {:type :killed}
    nil))

(defn- convert-to-status-from-symbol [status]
  (if status
    (condp = (:type status)
      :active TopologyStatus/ACTIVE
      :inactive TopologyStatus/INACTIVE
      :rebalancing TopologyStatus/REBALANCING
      :killed TopologyStatus/KILLED
      nil)))

(defn clojurify-rebalance-options [^RebalanceOptions rebalance-options]
  (-> {:action :rebalance}
    (assoc-non-nil :delay-secs (if (.isSet_wait_secs rebalance-options) (.getWait_secs rebalance-options)))
    (assoc-non-nil :num-workers (if (.isSet_num_workers rebalance-options) (.getNum_workers rebalance-options)))
    (assoc-non-nil :component->executors (if (.isSet_num_executors rebalance-options) (into {} (.getNum_executors rebalance-options))))))

(defn thriftify-rebalance-options [rebalance-options]
  (if rebalance-options
    (let [thrift-rebalance-options (RebalanceOptions.)]
      (if (:delay-secs rebalance-options)
        (.setWait_secs thrift-rebalance-options (int (:delay-secs rebalance-options))))
      (if (:num-workers rebalance-options)
        (.setNum_workers thrift-rebalance-options (int (:num-workers rebalance-options))))
      (if (:component->executors rebalance-options)
        (.setNum_executors thrift-rebalance-options (map-val int (:component->executors rebalance-options))))
      thrift-rebalance-options)))

(defn clojurify-kill-options [^KillOptions kill-options]
  (-> {:action :kill}
    (assoc-non-nil :delay-secs (if (.isSet_wait_secs kill-options) (.getWait_secs kill-options)))))

(defn thriftify-kill-options [kill-options]
  (if kill-options
    (let [thrift-kill-options (KillOptions.)]
      (if (:delay-secs kill-options)
        (.setWait_secs thrift-kill-options (int (:delay-secs kill-options))))
      thrift-kill-options)))

(defn thriftify-topology-action-options [storm-base]
  (if (:topology-action-options storm-base)
    (let [ topology-action-options (:topology-action-options storm-base)
           action (:action topology-action-options)
           thrift-topology-action-options (TopologyActionOptions.)]
      (if (= action :kill)
        (.setKill_options thrift-topology-action-options (thriftify-kill-options topology-action-options)))
      (if (= action :rebalance)
        (.setRebalance_options thrift-topology-action-options (thriftify-rebalance-options topology-action-options)))
      thrift-topology-action-options)))

(defn clojurify-topology-action-options [^TopologyActionOptions topology-action-options]
  (if topology-action-options
    (or (and (.isSetKill_options topology-action-options)
             (clojurify-kill-options
               (.getKill_options topology-action-options)))
        (and (.isSetRebalance_options topology-action-options)
             (clojurify-rebalance-options
               (.getRebalance_options topology-action-options))))))

(defn clojurify-debugoptions [^DebugOptions options]
  (if options
    {
      :enable (.isEnable options)
      :samplingpct (.getSamplingpct options)
      }
    ))

(defn thriftify-debugoptions [options]
  (doto (DebugOptions.)
    (.setEnable (get options :enable false))
    (.setSamplingpct (get options :samplingpct 10))))

(defn thriftify-storm-base [storm-base]
  (doto (StormBase.)
    (.setName (:storm-name storm-base))
    (.setLaunch_time_secs (int (:launch-time-secs storm-base)))
    (.setStatus (convert-to-status-from-symbol (:status storm-base)))
    (.setNum_workers (int (:num-workers storm-base)))
    (.setComponent_executors (map-val int (:component->executors storm-base)))
    (.setOwner (:owner storm-base))
    (.setTopology_action_options (thriftify-topology-action-options storm-base))
    (.setPrev_status (convert-to-status-from-symbol (:prev-status storm-base)))
    (.setComponent_debug (map-val thriftify-debugoptions (:component->debug storm-base)))))

(defn clojurify-storm-base [^StormBase storm-base]
  (if storm-base
    (org.apache.storm.daemon.common.StormBase.
      (.getName storm-base)
      (.getLaunch_time_secs storm-base)
      (convert-to-symbol-from-status (.getStatus storm-base))
      (.getNum_workers storm-base)
      (into {} (.getComponent_executors storm-base))
      (.getOwner storm-base)
      (clojurify-topology-action-options (.getTopology_action_options storm-base))
      (convert-to-symbol-from-status (.getPrev_status storm-base))
      (map-val clojurify-debugoptions (.getComponent_debug storm-base)))))

(defn thriftify-stats [stats]
  (if stats
    (map-val thriftify-executor-stats
      (map-key #(ExecutorInfo. (int (first %1)) (int (last %1)))
        stats))
    {}))

(defn clojurify-stats [stats]
  (if stats
    (map-val clojurify-executor-stats
      (map-key (fn [x] (list (.getTask_start x) (.getTask_end x)))
        stats))
    {}))

(defn clojurify-zk-worker-hb [^ClusterWorkerHeartbeat worker-hb]
  (if worker-hb
    {:storm-id (.getStorm_id worker-hb)
     :executor-stats (clojurify-stats (into {} (.getExecutor_stats worker-hb)))
     :uptime (.getUptime_secs worker-hb)
     :time-secs (.getTime_secs worker-hb)
     }
    {}))

(defn thriftify-zk-worker-hb [worker-hb]
  (if (not-empty (filter second (:executor-stats worker-hb)))
    (doto (ClusterWorkerHeartbeat.)
      (.setUptime_secs (:uptime worker-hb))
      (.setStorm_id (:storm-id worker-hb))
      (.setExecutor_stats (thriftify-stats (filter second (:executor-stats worker-hb))))
      (.setTime_secs (:time-secs worker-hb)))))

(defn clojurify-error [^ErrorInfo error]
  (if error
    {
      :error (.getError error)
      :time-secs (.getError_time_secs error)
      :host (.getHost error)
      :port (.getPort error)
      }
    ))

(defn thriftify-error [error]
  (doto (ErrorInfo. (:error error) (:time-secs error))
    (.setHost (:host error))
    (.setPort (:port error))))

(defn clojurify-profile-request
  [^ProfileRequest request]
  (when request
    {:host (.getNode (.getNodeInfo request))
     :port (first (.getPort (.getNodeInfo request)))
     :action     (.getAction request)
     :timestamp  (.getTime_stamp request)}))

(defn thriftify-profile-request
  [profile-request]
  (let [nodeinfo (doto (NodeInfo.)
                   (.setNode (:host profile-request))
                   (.setPort (set [(:port profile-request)])))
        request (ProfileRequest. nodeinfo (:action profile-request))]
    (.setTime_stamp request (:timestamp profile-request))
    request))

(defn thriftify-credentials [credentials]
    (doto (Credentials.)
      (.setCreds (if credentials credentials {}))))

(defn clojurify-crdentials [^Credentials credentials]
  (if credentials
    (into {} (.getCreds credentials))
    nil
    ))

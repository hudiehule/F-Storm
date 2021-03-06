/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.storm.daemon.supervisor.timer;

import org.apache.storm.Config;
import org.apache.storm.cluster.IStormClusterState;
import org.apache.storm.daemon.supervisor.Supervisor;
import org.apache.storm.generated.SupervisorInfo;
import org.apache.storm.utils.Time;
import org.apache.storm.utils.Utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SupervisorHeartbeat implements Runnable {

     private final IStormClusterState stormClusterState;
     private final String supervisorId;
     private final Map<String, Object> conf;
     private final Supervisor supervisor;

    public SupervisorHeartbeat(Map<String, Object> conf, Supervisor supervisor) {
        this.stormClusterState = supervisor.getStormClusterState();
        this.supervisorId = supervisor.getId();
        this.supervisor = supervisor;
        this.conf = conf;
    }

    private SupervisorInfo buildSupervisorInfo(Map<String, Object> conf, Supervisor supervisor) {
        SupervisorInfo supervisorInfo = new SupervisorInfo();
        supervisorInfo.setTime_secs(Time.currentTimeSecs());
        supervisorInfo.setHostname(supervisor.getHostName());
        supervisorInfo.setAssignment_id(supervisor.getAssignmentId());

        List<Long> usedPorts = new ArrayList<>();
        usedPorts.addAll(supervisor.getCurrAssignment().get().keySet());
        supervisorInfo.setUsed_ports(usedPorts);
        List metaDatas = (List)supervisor.getiSupervisor().getMetadata();
        List<Long> portList = new ArrayList<>();
        if (metaDatas != null){
            for (Object data : metaDatas){
                Integer port = Utils.getInt(data);
                if (port != null)
                    portList.add(port.longValue());
            }
        }

        supervisorInfo.setMeta(portList);
        supervisorInfo.setScheduler_meta((Map<String, String>) conf.get(Config.SUPERVISOR_SCHEDULER_META));
        supervisorInfo.setUptime_secs(supervisor.getUpTime().upTime());
        supervisorInfo.setVersion(supervisor.getStormVersion());
        supervisorInfo.setResources_map(mkSupervisorCapacities(conf));
        return supervisorInfo;
    }

    private Map<String, Double> mkSupervisorCapacities(Map conf) {
        Map<String, Double> ret = new HashMap<String, Double>();
        Double mem = Utils.getDouble(conf.get(Config.SUPERVISOR_MEMORY_CAPACITY_MB), 4096.0);
        ret.put(Config.SUPERVISOR_MEMORY_CAPACITY_MB, mem);
        Double cpu = Utils.getDouble(conf.get(Config.SUPERVISOR_CPU_CAPACITY), 400.0);
        ret.put(Config.SUPERVISOR_CPU_CAPACITY, cpu);
        return ret;
    }

    @Override
    public void run() {
        SupervisorInfo supervisorInfo = buildSupervisorInfo(conf, supervisor);
        stormClusterState.supervisorHeartbeat(supervisorId, supervisorInfo);
    }
}

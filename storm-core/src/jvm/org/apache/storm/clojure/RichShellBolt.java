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
package org.apache.storm.clojure;

import org.apache.storm.generated.StreamInfo;
import org.apache.storm.task.ShellBolt;
import org.apache.storm.topology.IRichBolt;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import java.util.Map;

public class RichShellBolt extends ShellBolt implements IRichBolt {
    private Map<String, StreamInfo> _outputs;
    
    public RichShellBolt(String[] command, Map<String, StreamInfo> outputs) {
        super(command);
        _outputs = outputs;
    }
    
    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        for(String stream: _outputs.keySet()) {
            StreamInfo def = _outputs.get(stream);
            if(def.isDirect()) {
                declarer.declareStream(stream, true, new Fields(def.getOutput_fields()));
            } else {
                declarer.declareStream(stream, new Fields(def.getOutput_fields()));
            }
        }
    }

    @Override
    public Map<String, Object> getComponentConfiguration() {
        return null;
    }    
}

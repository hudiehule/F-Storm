package org.apache.storm.accelerator;

import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.BasicOutputCollector;
import org.apache.storm.topology.IRichBolt;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Created by Administrator on 2017/12/19.
 */
public class AccBoltExecutor implements IRichBolt{
    public static final Logger LOG = LoggerFactory.getLogger(AccBoltExecutor.class);

    private BaseAcceleratorBolt _baseAccBolt;

    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        _baseAccBolt.declareOutputFields(declarer);
    }
    private transient BasicOutputCollector _collector;

    public AccBoltExecutor(BaseAcceleratorBolt bolt){
        _baseAccBolt = bolt;
    }

    public void prepare(Map stormConf, TopologyContext context, OutputCollector collector) {
        _baseAccBolt.prepare(stormConf, context,collector);
        _collector = new BasicOutputCollector(collector);
    }

    public void execute(Tuple input) {
        _baseAccBolt.execute(input);
    }

    public void cleanup() {
        _baseAccBolt.cleanup();
    }

    public Map<String, Object> getComponentConfiguration() {
        return _baseAccBolt.getComponentConfiguration();
    }
}

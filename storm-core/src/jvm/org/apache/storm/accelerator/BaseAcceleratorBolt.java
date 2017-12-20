package org.apache.storm.accelerator;

import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.IRichBolt;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.generated.AccType;
import org.apache.storm.tuple.Tuple;

import java.util.Map;


/**
 * Created by Administrator on 2017/12/11.
 */
public abstract class BaseAcceleratorBolt implements IRichBolt{

    private AccType accType;
    private int batchSize;
    private String kernelFilePath;
    private String kernelName;

    public AccType getAccType(){
        return this.accType;
    }

    public BaseAcceleratorBolt(String kernelName,String kernelFilePath,int batchSize,AcceleratorType accTy){
          if(AcceleratorType.GPU.equals(accTy)){
              accType = AccType.findByValue(1);
          }else if(AcceleratorType.FPGA.equals(accTy)){
              accType = AccType.findByValue(2);
          }
          this.batchSize = batchSize;
          this.kernelFilePath = kernelFilePath;
          this.kernelName = kernelName;
    }

    public void prepare(Map stormConf, TopologyContext topologyContext) {


    }
    public void execute(Tuple tuple){

    }

 /*   @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer){

    }*/

    /**
     * function defined by user,
     * @param stormConf
     * @param topologyContext
     */
   public abstract void userPrepare(Map stormConf, TopologyContext topologyContext);


}

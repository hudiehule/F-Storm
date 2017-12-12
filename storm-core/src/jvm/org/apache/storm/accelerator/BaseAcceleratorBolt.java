package org.apache.storm.accelerator;

import org.apache.storm.topology.IRichBolt;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.generated.AccType;


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


    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer){

    }



}

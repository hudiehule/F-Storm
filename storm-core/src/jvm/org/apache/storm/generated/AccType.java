/**
 * Autogenerated by Thrift Compiler (0.9.3)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */
package org.apache.storm.generated;


import java.util.Map;
import java.util.HashMap;
import org.apache.thrift.TEnum;

public enum AccType implements org.apache.thrift.TEnum {
  GPU(1),
  FPGA(2),
  None(3);

  private final int value;

  private AccType(int value) {
    this.value = value;
  }

  /**
   * Get the integer value of this enum value, as defined in the Thrift IDL.
   */
  public int getValue() {
    return value;
  }

  /**
   * Find a the enum type by its integer value, as defined in the Thrift IDL.
   * @return null if the value is not found.
   */
  public static AccType findByValue(int value) { 
    switch (value) {
      case 1:
        return GPU;
      case 2:
        return FPGA;
      case 3:
        return None;
      default:
        return null;
    }
  }
}
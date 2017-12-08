/**
 * Autogenerated by Thrift Compiler (0.9.3)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */
package org.apache.storm.generated;

import org.apache.thrift.scheme.IScheme;
import org.apache.thrift.scheme.SchemeFactory;
import org.apache.thrift.scheme.StandardScheme;

import org.apache.thrift.scheme.TupleScheme;
import org.apache.thrift.protocol.TTupleProtocol;
import org.apache.thrift.protocol.TProtocolException;
import org.apache.thrift.EncodingUtils;
import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.apache.thrift.server.AbstractNonblockingServer.*;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.EnumMap;
import java.util.Set;
import java.util.HashSet;
import java.util.EnumSet;
import java.util.Collections;
import java.util.BitSet;
import java.nio.ByteBuffer;
import java.util.Arrays;
import javax.annotation.Generated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings({"cast", "rawtypes", "serial", "unchecked"})
@Generated(value = "Autogenerated by Thrift Compiler (0.9.3)", date = "2017-12-08")
public class Credentials implements org.apache.thrift.TBase<Credentials, Credentials._Fields>, java.io.Serializable, Cloneable, Comparable<Credentials> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("Credentials");

  private static final org.apache.thrift.protocol.TField CREDS_FIELD_DESC = new org.apache.thrift.protocol.TField("creds", org.apache.thrift.protocol.TType.MAP, (short)1);

  private static final Map<Class<? extends IScheme>, SchemeFactory> schemes = new HashMap<Class<? extends IScheme>, SchemeFactory>();
  static {
    schemes.put(StandardScheme.class, new CredentialsStandardSchemeFactory());
    schemes.put(TupleScheme.class, new CredentialsTupleSchemeFactory());
  }

  public Map<String,String> creds; // required

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    CREDS((short)1, "creds");

    private static final Map<String, _Fields> byName = new HashMap<String, _Fields>();

    static {
      for (_Fields field : EnumSet.allOf(_Fields.class)) {
        byName.put(field.getFieldName(), field);
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, or null if its not found.
     */
    public static _Fields findByThriftId(int fieldId) {
      switch(fieldId) {
        case 1: // CREDS
          return CREDS;
        default:
          return null;
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, throwing an exception
     * if it is not found.
     */
    public static _Fields findByThriftIdOrThrow(int fieldId) {
      _Fields fields = findByThriftId(fieldId);
      if (fields == null) throw new IllegalArgumentException("Field " + fieldId + " doesn't exist!");
      return fields;
    }

    /**
     * Find the _Fields constant that matches name, or null if its not found.
     */
    public static _Fields findByName(String name) {
      return byName.get(name);
    }

    private final short _thriftId;
    private final String _fieldName;

    _Fields(short thriftId, String fieldName) {
      _thriftId = thriftId;
      _fieldName = fieldName;
    }

    public short getThriftFieldId() {
      return _thriftId;
    }

    public String getFieldName() {
      return _fieldName;
    }
  }

  // isset id assignments
  public static final Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.CREDS, new org.apache.thrift.meta_data.FieldMetaData("creds", org.apache.thrift.TFieldRequirementType.REQUIRED, 
        new org.apache.thrift.meta_data.MapMetaData(org.apache.thrift.protocol.TType.MAP, 
            new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING), 
            new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING))));
    metaDataMap = Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(Credentials.class, metaDataMap);
  }

  public Credentials() {
  }

  public Credentials(
    Map<String,String> creds)
  {
    this();
    this.creds = creds;
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public Credentials(Credentials other) {
    if (other.isSetCreds()) {
      Map<String,String> __this__creds = new HashMap<String,String>(other.creds);
      this.creds = __this__creds;
    }
  }

  public Credentials deepCopy() {
    return new Credentials(this);
  }

  @Override
  public void clear() {
    this.creds = null;
  }

  public int getCredsSize() {
    return (this.creds == null) ? 0 : this.creds.size();
  }

  public void putToCreds(String key, String val) {
    if (this.creds == null) {
      this.creds = new HashMap<String,String>();
    }
    this.creds.put(key, val);
  }

  public Map<String,String> getCreds() {
    return this.creds;
  }

  public Credentials setCreds(Map<String,String> creds) {
    this.creds = creds;
    return this;
  }

  public void unsetCreds() {
    this.creds = null;
  }

  /** Returns true if field creds is set (has been assigned a value) and false otherwise */
  public boolean isSetCreds() {
    return this.creds != null;
  }

  public void setCredsIsSet(boolean value) {
    if (!value) {
      this.creds = null;
    }
  }

  public void setFieldValue(_Fields field, Object value) {
    switch (field) {
    case CREDS:
      if (value == null) {
        unsetCreds();
      } else {
        setCreds((Map<String,String>)value);
      }
      break;

    }
  }

  public Object getFieldValue(_Fields field) {
    switch (field) {
    case CREDS:
      return getCreds();

    }
    throw new IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new IllegalArgumentException();
    }

    switch (field) {
    case CREDS:
      return isSetCreds();
    }
    throw new IllegalStateException();
  }

  @Override
  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof Credentials)
      return this.equals((Credentials)that);
    return false;
  }

  public boolean equals(Credentials that) {
    if (that == null)
      return false;

    boolean this_present_creds = true && this.isSetCreds();
    boolean that_present_creds = true && that.isSetCreds();
    if (this_present_creds || that_present_creds) {
      if (!(this_present_creds && that_present_creds))
        return false;
      if (!this.creds.equals(that.creds))
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    List<Object> list = new ArrayList<Object>();

    boolean present_creds = true && (isSetCreds());
    list.add(present_creds);
    if (present_creds)
      list.add(creds);

    return list.hashCode();
  }

  @Override
  public int compareTo(Credentials other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;

    lastComparison = Boolean.valueOf(isSetCreds()).compareTo(other.isSetCreds());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetCreds()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.creds, other.creds);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    return 0;
  }

  public _Fields fieldForId(int fieldId) {
    return _Fields.findByThriftId(fieldId);
  }

  public void read(org.apache.thrift.protocol.TProtocol iprot) throws org.apache.thrift.TException {
    schemes.get(iprot.getScheme()).getScheme().read(iprot, this);
  }

  public void write(org.apache.thrift.protocol.TProtocol oprot) throws org.apache.thrift.TException {
    schemes.get(oprot.getScheme()).getScheme().write(oprot, this);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("Credentials(");
    boolean first = true;

    sb.append("creds:");
    if (this.creds == null) {
      sb.append("null");
    } else {
      sb.append(this.creds);
    }
    first = false;
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws org.apache.thrift.TException {
    // check for required fields
    if (creds == null) {
      throw new org.apache.thrift.protocol.TProtocolException("Required field 'creds' was not present! Struct: " + toString());
    }
    // check for sub-struct validity
  }

  private void writeObject(java.io.ObjectOutputStream out) throws java.io.IOException {
    try {
      write(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(out)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, ClassNotFoundException {
    try {
      read(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(in)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private static class CredentialsStandardSchemeFactory implements SchemeFactory {
    public CredentialsStandardScheme getScheme() {
      return new CredentialsStandardScheme();
    }
  }

  private static class CredentialsStandardScheme extends StandardScheme<Credentials> {

    public void read(org.apache.thrift.protocol.TProtocol iprot, Credentials struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TField schemeField;
      iprot.readStructBegin();
      while (true)
      {
        schemeField = iprot.readFieldBegin();
        if (schemeField.type == org.apache.thrift.protocol.TType.STOP) { 
          break;
        }
        switch (schemeField.id) {
          case 1: // CREDS
            if (schemeField.type == org.apache.thrift.protocol.TType.MAP) {
              {
                org.apache.thrift.protocol.TMap _map598 = iprot.readMapBegin();
                struct.creds = new HashMap<String,String>(2*_map598.size);
                String _key599;
                String _val600;
                for (int _i601 = 0; _i601 < _map598.size; ++_i601)
                {
                  _key599 = iprot.readString();
                  _val600 = iprot.readString();
                  struct.creds.put(_key599, _val600);
                }
                iprot.readMapEnd();
              }
              struct.setCredsIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          default:
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
        }
        iprot.readFieldEnd();
      }
      iprot.readStructEnd();

      // check for required fields of primitive type, which can't be checked in the validate method
      struct.validate();
    }

    public void write(org.apache.thrift.protocol.TProtocol oprot, Credentials struct) throws org.apache.thrift.TException {
      struct.validate();

      oprot.writeStructBegin(STRUCT_DESC);
      if (struct.creds != null) {
        oprot.writeFieldBegin(CREDS_FIELD_DESC);
        {
          oprot.writeMapBegin(new org.apache.thrift.protocol.TMap(org.apache.thrift.protocol.TType.STRING, org.apache.thrift.protocol.TType.STRING, struct.creds.size()));
          for (Map.Entry<String, String> _iter602 : struct.creds.entrySet())
          {
            oprot.writeString(_iter602.getKey());
            oprot.writeString(_iter602.getValue());
          }
          oprot.writeMapEnd();
        }
        oprot.writeFieldEnd();
      }
      oprot.writeFieldStop();
      oprot.writeStructEnd();
    }

  }

  private static class CredentialsTupleSchemeFactory implements SchemeFactory {
    public CredentialsTupleScheme getScheme() {
      return new CredentialsTupleScheme();
    }
  }

  private static class CredentialsTupleScheme extends TupleScheme<Credentials> {

    @Override
    public void write(org.apache.thrift.protocol.TProtocol prot, Credentials struct) throws org.apache.thrift.TException {
      TTupleProtocol oprot = (TTupleProtocol) prot;
      {
        oprot.writeI32(struct.creds.size());
        for (Map.Entry<String, String> _iter603 : struct.creds.entrySet())
        {
          oprot.writeString(_iter603.getKey());
          oprot.writeString(_iter603.getValue());
        }
      }
    }

    @Override
    public void read(org.apache.thrift.protocol.TProtocol prot, Credentials struct) throws org.apache.thrift.TException {
      TTupleProtocol iprot = (TTupleProtocol) prot;
      {
        org.apache.thrift.protocol.TMap _map604 = new org.apache.thrift.protocol.TMap(org.apache.thrift.protocol.TType.STRING, org.apache.thrift.protocol.TType.STRING, iprot.readI32());
        struct.creds = new HashMap<String,String>(2*_map604.size);
        String _key605;
        String _val606;
        for (int _i607 = 0; _i607 < _map604.size; ++_i607)
        {
          _key605 = iprot.readString();
          _val606 = iprot.readString();
          struct.creds.put(_key605, _val606);
        }
      }
      struct.setCredsIsSet(true);
    }
  }

}


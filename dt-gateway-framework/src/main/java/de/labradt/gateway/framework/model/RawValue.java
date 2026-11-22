package de.labradt.gateway.framework.model;

import java.time.LocalDateTime;

/** What an adapter returns after reading a node, including metadata. */
public class RawValue {

    private String address;
    private Object value;
    private String rawType;
    private LocalDateTime timestamp;
    private ProtocolType protocol;
    private String statusCode;

    public RawValue() {}

    public RawValue(String address, Object value, String rawType,
                    LocalDateTime timestamp, ProtocolType protocol, String statusCode) {
        this.address = address;
        this.value = value;
        this.rawType = rawType;
        this.timestamp = timestamp;
        this.protocol = protocol;
        this.statusCode = statusCode;
    }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }

    public String getRawType() { return rawType; }
    public void setRawType(String rawType) { this.rawType = rawType; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public ProtocolType getProtocol() { return protocol; }
    public void setProtocol(ProtocolType protocol) { this.protocol = protocol; }

    public String getStatusCode() { return statusCode; }
    public void setStatusCode(String statusCode) { this.statusCode = statusCode; }
}
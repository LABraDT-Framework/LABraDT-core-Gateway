package de.labradt.gateway.framework.model;

import java.util.List;

/**
 * One machine, fully described by one explicit JSON file: identity, connection,
 * and every mapping spelled out. No composition, no inheritance; what you read
 * in the file is exactly what the gateway runs. Reusing one file for several
 * identical machines is future work.
 */
public class MachineConfig {

    private String machineId;
    private String machineType;
    private ProtocolType protocol;
    private ConnectionConfig connection;
    private List<AdapterMapping> mappings;

    public String getMachineId() { return machineId; }
    public void setMachineId(String machineId) { this.machineId = machineId; }

    public String getMachineType() { return machineType; }
    public void setMachineType(String machineType) { this.machineType = machineType; }

    public ProtocolType getProtocol() { return protocol; }
    public void setProtocol(ProtocolType protocol) { this.protocol = protocol; }

    public ConnectionConfig getConnection() { return connection; }
    public void setConnection(ConnectionConfig connection) { this.connection = connection; }

    public List<AdapterMapping> getMappings() { return mappings; }
    public void setMappings(List<AdapterMapping> mappings) { this.mappings = mappings; }
}

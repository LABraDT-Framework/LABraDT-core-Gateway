package de.labradt.gateway.framework.model;

import java.util.Map;

/**
 * One signal mapping, authored by the config engineer. The engineer supplies
 * the AAS-standard fields directly (idShort, valueType, category, semanticId,
 * per IDTA-01001), plus the gateway-side translation settings (sourceAddress,
 * valueMap, ranges, deadband) that describe how to produce the value.
 */
public class AdapterMapping {

    // Standard AAS Property fields (engineer-supplied, copied to output)
    private String idShort;        // pattern [a-zA-Z][a-zA-Z0-9_]*, validated at load
    private String valueType;      // xs:double | xs:int | xs:boolean | xs:string
    private String category;       // CONSTANT | PARAMETER | VARIABLE (default VARIABLE)
    private String semanticId;     // optional; the engineer owns semantics

    // Gateway-side translation settings
    private String machineId;      // stamped by the loader
    private String sourceAddress;  // MQTT topic
    private String unit;           // emitted as a Unit qualifier
    private ProtocolType protocol; // backfilled from the machine's protocol
    private String sourceUnit;
    private String targetUnit;
    private double[] validRange;
    private Map<String, String> valueMap;
    private double deadband = 0.0; // absolute threshold in the element's unit;
                                   // 0 = every value change is notified

    public AdapterMapping() {}

    public String getIdShort() { return idShort; }
    public void setIdShort(String idShort) { this.idShort = idShort; }

    public String getValueType() { return valueType; }
    public void setValueType(String valueType) { this.valueType = valueType; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getSemanticId() { return semanticId; }
    public void setSemanticId(String semanticId) { this.semanticId = semanticId; }

    public String getMachineId() { return machineId; }
    public void setMachineId(String machineId) { this.machineId = machineId; }

    public String getSourceAddress() { return sourceAddress; }
    public void setSourceAddress(String sourceAddress) { this.sourceAddress = sourceAddress; }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public ProtocolType getProtocol() { return protocol; }
    public void setProtocol(ProtocolType protocol) { this.protocol = protocol; }

    public String getSourceUnit() { return sourceUnit; }
    public void setSourceUnit(String sourceUnit) { this.sourceUnit = sourceUnit; }

    public String getTargetUnit() { return targetUnit; }
    public void setTargetUnit(String targetUnit) { this.targetUnit = targetUnit; }

    public double[] getValidRange() { return validRange; }
    public void setValidRange(double[] validRange) { this.validRange = validRange; }

    public Map<String, String> getValueMap() { return valueMap; }
    public void setValueMap(Map<String, String> valueMap) { this.valueMap = valueMap; }

    public double getDeadband() { return deadband; }
    public void setDeadband(double deadband) { this.deadband = deadband; }

    /** The store/REST addressing key {@code machineId.idShort}. */
    public String routingKey() {
        return machineId + "." + idShort;
    }

    public boolean isConstantCategory() {
        return "CONSTANT".equalsIgnoreCase(category);
    }
}

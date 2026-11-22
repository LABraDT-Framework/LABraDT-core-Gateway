package de.labradt.gateway.framework.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A standards-conformant AAS {@code Property} (IDTA-01001 / IEC 63278-1),
 * the one data shape this gateway produces, caches, and serves. The JSON
 * serialization of this bean is the standard element:
 *
 * <pre>
 * {
 *   "modelType": "Property",
 *   "idShort": "ClampForce",
 *   "category": "VARIABLE",
 *   "semanticId": { "type": "ExternalReference", "keys": [ ... ] },
 *   "valueType": "xs:double",
 *   "value": "2200.0",
 *   "qualifiers": [ {Unit}, {StatusCode}, {ObservedAt}, {Timestamp} ]
 * }
 * </pre>
 *
 * <p>Operational metadata the metamodel has no slot for (unit, statusCode,
 * freshness timestamps) is carried as {@code Qualifiers}, the metamodel's
 * sanctioned extension mechanism. Units would canonically live in a
 * ConceptDescription (IEC 61360); the qualifier is a documented simplification.
 *
 * <p>Internal-only state (routing key, conversion metadata, raw status fields)
 * is exposed through non-bean accessors such as {@link #routingKey()} so it
 * never leaks into the serialized standard shape.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SubmodelElement {


    // Standard Property fields (serialized)
    private String idShort;
    private String category;       // CONSTANT | PARAMETER | VARIABLE
    private String valueType;      // xs:double, xs:int, xs:boolean, xs:string
    private String value;
    private String semanticId;     // raw reference value; serialized as a Reference

    // Internal state (NOT serialized as flat fields)
    private String routingKey;     // "machineId.idShort", store/REST addressing
    private String unit;           // Unit qualifier
    private String sourceUnit;     // conversion metadata
    private String targetUnit;
    private String statusCode;     // StatusCode qualifier (OK, OUT_OF_RANGE, STALE)
    private LocalDateTime timestamp;   // when the VALUE last changed; Timestamp qualifier

    /**
     * When the gateway last successfully observed this value, regardless of
     * whether the value itself changed. Set by {@code SubmodelStore.put()}
     * on every poll. This is the timestamp the staleness check uses, because
     * staleness asks whether the gateway is still reading, not whether the
     * value is still moving.
     */
    private LocalDateTime observedAt;  // ObservedAt qualifier

    public SubmodelElement() {}

    // Serialized (bean) getters: the standard shape

    public String getModelType() { return "Property"; }

    public String getIdShort() { return idShort; }
    public void setIdShort(String idShort) { this.idShort = idShort; }

    public String getCategory() { return category == null || category.isBlank() ? "VARIABLE" : category; }
    public void setCategory(String category) { this.category = category; }

    public String getValueType() { return valueType; }
    public void setValueType(String valueType) { this.valueType = valueType; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    /** Serialized as the metamodel's Reference structure (or omitted when unset). */
    public Map<String, Object> getSemanticId() {
        if (semanticId == null || semanticId.isBlank()) return null;
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("type", "ExternalReference");
        Map<String, String> key = new LinkedHashMap<>();
        key.put("type", "GlobalReference");
        key.put("value", semanticId);
        ref.put("keys", List.of(key));
        return ref;
    }

    /** Unit / StatusCode / ObservedAt / Timestamp as standard Qualifiers. */
    public List<Map<String, String>> getQualifiers() {
        List<Map<String, String>> qualifiers = new ArrayList<>();
        if (unit != null && !unit.isBlank()) {
            qualifiers.add(qualifier("Unit", "xs:string", unit));
        }
        if (statusCode != null) {
            qualifiers.add(qualifier("StatusCode", "xs:string", statusCode));
        }
        if (observedAt != null) {
            qualifiers.add(qualifier("ObservedAt", "xs:dateTime", utc(observedAt)));
        }
        if (timestamp != null) {
            qualifiers.add(qualifier("Timestamp", "xs:dateTime", utc(timestamp)));
        }
        return qualifiers;
    }

    /**
     * The times are taken in the JVM's zone (LocalDateTime.now()); they are published as UTC instants with "Z",
     * e.g. 2026-11-20T15:06:32.924722549Z, so readers in another zone (or a Docker container in UTC) agree.
     */
    static String utc(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toString();
    }

    private static Map<String, String> qualifier(String type, String valueType, String value) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("kind", "ConceptQualifier");
        q.put("type", type);
        q.put("valueType", valueType);
        q.put("value", value);
        return q;
    }

    // Internal accessors (non-bean names, never serialized)

    public void setSemanticId(String semanticId) { this.semanticId = semanticId; }
    public String semanticIdValue() { return semanticId; }

    public String routingKey() { return routingKey; }
    public void setRoutingKey(String routingKey) { this.routingKey = routingKey; }

    public String unit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public String sourceUnit() { return sourceUnit; }
    public void setSourceUnit(String sourceUnit) { this.sourceUnit = sourceUnit; }

    public String targetUnit() { return targetUnit; }
    public void setTargetUnit(String targetUnit) { this.targetUnit = targetUnit; }

    public String statusCode() { return statusCode; }
    public void setStatusCode(String statusCode) { this.statusCode = statusCode; }

    public LocalDateTime timestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public LocalDateTime observedAt() { return observedAt; }
    public void setObservedAt(LocalDateTime observedAt) { this.observedAt = observedAt; }

    /** Non-bean name: internal check, never serialized. */
    public boolean constantCategory() { return "CONSTANT".equalsIgnoreCase(getCategory()); }

    /**
     * Change-suppression threshold in the element's own unit, carried from the
     * mapping so the store can judge numeric changes against the last
     * NOTIFIED value. Internal only, never serialized.
     */
    private double deadband;

    public double deadband() { return deadband; }
    public void setDeadband(double deadband) { this.deadband = deadband; }

    /** Shallow copy, used by StalenessPolicy to flag without mutating the cache. */
    public SubmodelElement copy() {
        SubmodelElement c = new SubmodelElement();
        c.idShort = idShort;
        c.category = category;
        c.valueType = valueType;
        c.value = value;
        c.semanticId = semanticId;
        c.routingKey = routingKey;
        c.unit = unit;
        c.sourceUnit = sourceUnit;
        c.targetUnit = targetUnit;
        c.statusCode = statusCode;
        c.timestamp = timestamp;
        c.observedAt = observedAt;
        c.deadband = deadband;
        return c;
    }
}

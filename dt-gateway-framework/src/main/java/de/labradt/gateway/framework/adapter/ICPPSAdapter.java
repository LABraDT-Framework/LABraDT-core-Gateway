package de.labradt.gateway.framework.adapter;

import de.labradt.gateway.framework.model.AdapterMapping;
import de.labradt.gateway.framework.model.ProtocolType;
import de.labradt.gateway.framework.model.RawValue;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Protocol adapter SPI.
 *
 * <p>ISO 23247-2 traceability: realizes the device communication entity
 * (§5.3.2). {@code readAddress}/{@code readBatchAddresses} are the data
 * collection sub-entity's data collecting FE (§6.2.1.1), {@code writeAddress}
 * is the device control sub-entity's controlling FE (§6.2.2.1), and
 * {@code getMachineId} provides collection/control identification (§6.2.1.3,
 * §6.2.2.3).
 */
public interface ICPPSAdapter {

    String getMachineId();
    void connect();
    void disconnect();
    RawValue readAddress(String address);

    /**
     * Whether the machine behind this adapter is currently delivering data.
     * Distinct from {@link #isConnected()} for broker-mediated protocols,
     * where the transport can be up while the machine has gone silent.
     * Staleness is decided per machine on this fact, not per element.
     * The default treats transport connectivity as source liveness, which
     * holds for client/server protocols like MQTT where session death is
     * machine death.
     */
    default boolean isSourceAlive() { return isConnected(); }
    List<RawValue> readBatchAddresses(List<String> addresses);
    void writeAddress(String address, Object value);
    ProtocolType getProtocol();
    boolean isConnected();
    Map<String, RawValue> getCachedNodes();

    /**
     * Optional: verify that every {@link AdapterMapping} for this protocol points
     * at an address the adapter can actually find on the live server. Called by the
     * framework after the adapter first becomes connected. The default is a no-op
     * so plugins that don't need pre-flight validation aren't forced to implement it.
     */
    default void validateMappings(Collection<AdapterMapping> mappings) {}
}

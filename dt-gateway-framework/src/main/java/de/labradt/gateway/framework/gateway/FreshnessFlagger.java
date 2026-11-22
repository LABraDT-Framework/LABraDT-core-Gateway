package de.labradt.gateway.framework.gateway;

import de.labradt.gateway.framework.adapter.AdapterRegistry;
import de.labradt.gateway.framework.model.StalenessPolicy;
import de.labradt.gateway.framework.model.SubmodelElement;
import org.springframework.stereotype.Component;

/**
 * Applies the machine-level staleness flag to an element at serving time.
 *
 * <p>Staleness is a property of the machine (is it delivering data?) displayed
 * on its elements. This is the single place that decision is taken, shared by
 * the REST controller and the SSE broadcasters so the read path and the stream
 * can never disagree about whether a machine is alive.
 */
@Component
public class FreshnessFlagger {

    private final AdapterRegistry adapterRegistry;

    public FreshnessFlagger(AdapterRegistry adapterRegistry) {
        this.adapterRegistry = adapterRegistry;
    }

    /** Returns the element, flagged STALE if its machine is not delivering data. */
    public SubmodelElement flag(String routingKey, SubmodelElement element) {
        return StalenessPolicy.applyTo(element, machineAlive(routingKey));
    }

    /** Whether the machine named by the routing key's prefix is delivering data. */
    public boolean machineAlive(String routingKey) {
        int dot = routingKey == null ? -1 : routingKey.indexOf('.');
        if (dot <= 0) return true; // no machine prefix, nothing to judge
        String machineId = routingKey.substring(0, dot);
        return adapterRegistry.hasAdapter(machineId)
                && adapterRegistry.getAdapter(machineId).isSourceAlive();
    }
}

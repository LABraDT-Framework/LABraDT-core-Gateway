package de.labradt.gateway.framework.gateway;

import de.labradt.gateway.framework.gateway.exception.MappingNotFoundException;
import de.labradt.gateway.framework.model.AdapterMapping;
import de.labradt.gateway.framework.model.MachineConfig;
import de.labradt.gateway.framework.model.MachineConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class MappingRegistry {

    private static final Logger log = LoggerFactory.getLogger(MappingRegistry.class);

    /** Keyed by the routing key "machineId.idShort": the element's own
     *  idShort stays clean (AAS pattern) and machine identity lives here. */
    private final Map<String, AdapterMapping> byRoutingKey = new LinkedHashMap<>();

    public MappingRegistry(MachineConfigLoader configLoader) {
        for (MachineConfig config : configLoader.loadAll()) {
            if (config.getMappings() == null) continue;
            for (AdapterMapping mapping : config.getMappings()) {
                byRoutingKey.put(mapping.routingKey(), mapping);
            }
        }
        log.info("Indexed {} submodel elements", byRoutingKey.size());
    }

    public AdapterMapping get(String routingKey) {
        AdapterMapping mapping = byRoutingKey.get(routingKey);
        if (mapping == null) {
            throw new MappingNotFoundException(routingKey);
        }
        return mapping;
    }

    public boolean has(String routingKey) {
        return byRoutingKey.containsKey(routingKey);
    }

    public Collection<AdapterMapping> all() {
        return Collections.unmodifiableCollection(byRoutingKey.values());
    }

    public int size() {
        return byRoutingKey.size();
    }
}

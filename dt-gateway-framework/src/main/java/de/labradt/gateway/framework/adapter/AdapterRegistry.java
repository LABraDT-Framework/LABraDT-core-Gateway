package de.labradt.gateway.framework.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AdapterRegistry {

    private static final Logger log = LoggerFactory.getLogger(AdapterRegistry.class);

    private final Map<String, ICPPSAdapter> adapters = new LinkedHashMap<>();

    public AdapterRegistry(List<AdapterPool> pools) {
        for (AdapterPool pool : pools) {
            for (ICPPSAdapter adapter : pool.all()) {
                adapters.put(adapter.getMachineId(), adapter);
                log.info("Registered adapter for machine '{}'", adapter.getMachineId());
            }
        }
    }

    public ICPPSAdapter getAdapter(String machineId) {
        ICPPSAdapter adapter = adapters.get(machineId);
        if (adapter == null) {
            throw new IllegalArgumentException("No adapter registered for machine: " + machineId);
        }
        return adapter;
    }

    public boolean hasAdapter(String machineId) {
        return adapters.containsKey(machineId);
    }

    public Collection<ICPPSAdapter> getAllAdapters() {
        return Collections.unmodifiableCollection(adapters.values());
    }
}

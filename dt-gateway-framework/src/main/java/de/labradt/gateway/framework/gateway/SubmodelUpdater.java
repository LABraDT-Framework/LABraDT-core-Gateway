package de.labradt.gateway.framework.gateway;

import de.labradt.gateway.framework.adapter.AdapterRegistry;
import de.labradt.gateway.framework.adapter.ICPPSAdapter;
import de.labradt.gateway.framework.model.AdapterMapping;
import de.labradt.gateway.framework.model.SubmodelElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Polls every mapped signal and refreshes the {@link SubmodelStore}.
 *
 * <p>ISO 23247-2 traceability: realizes the digital twin entity's
 * synchronization FE (§6.3.1.1). The time-based polling approach is
 * explicitly sanctioned by ISO 23247-1 §5.3.13 ("a time-based method updates
 * the twin continuously").
 */
@Component
public class SubmodelUpdater {

    private static final Logger log = LoggerFactory.getLogger(SubmodelUpdater.class);

    private final GatewayService gateway;
    private final SubmodelStore store;
    private final AdapterRegistry adapterRegistry;
    private final MappingRegistry mappingRegistry;
    private final AtomicLong cycle = new AtomicLong();

    /** Machines whose adapter has already been validated against the live server once. */
    private final Set<String> validatedMachines = new HashSet<>();

    public SubmodelUpdater(GatewayService gateway,
                           SubmodelStore store,
                           AdapterRegistry adapterRegistry,
                           MappingRegistry mappingRegistry) {
        this.gateway = gateway;
        this.store = store;
        this.adapterRegistry = adapterRegistry;
        this.mappingRegistry = mappingRegistry;
    }

    @Scheduled(fixedDelayString = "${dt.framework.poll-interval-ms:1000}")
    public void refresh() {
        if (mappingRegistry.size() == 0) return;

        long n = cycle.incrementAndGet();
        int ok = 0;
        int skipped = 0;

        runValidationIfFirstConnect();

        for (AdapterMapping mapping : mappingRegistry.all()) {
            if (!adapterRegistry.hasAdapter(mapping.getMachineId())) {
                skipped++;
                continue;
            }
            try {
                SubmodelElement element = gateway.read(mapping.routingKey());
                store.put(element);
                ok++;
            } catch (Exception e) {
                skipped++;
                log.debug("Skipped {} on cycle {}: {}",
                        mapping.routingKey(), n, e.getMessage());
            }
        }

        if (n == 1 || n % 30 == 0) {
            log.info("Cycle {}: {} updated, {} skipped, store size={}",
                    n, ok, skipped, store.size());
        }
    }

    private void runValidationIfFirstConnect() {
        Map<String, List<AdapterMapping>> byMachine = mappingRegistry.all().stream()
                .collect(Collectors.groupingBy(AdapterMapping::getMachineId,
                        LinkedHashMap::new, Collectors.toList()));

        for (Map.Entry<String, List<AdapterMapping>> entry : byMachine.entrySet()) {
            String machineId = entry.getKey();
            if (validatedMachines.contains(machineId)) continue;
            if (!adapterRegistry.hasAdapter(machineId)) continue;
            ICPPSAdapter adapter = adapterRegistry.getAdapter(machineId);
            if (!adapter.isConnected()) continue;

            log.info("Validating {} mappings for machine '{}'...", entry.getValue().size(), machineId);
            try {
                adapter.validateMappings(entry.getValue());
            } catch (Exception e) {
                log.warn("Mapping validation failed for '{}': {}", machineId, e.getMessage());
            }
            validatedMachines.add(machineId);
        }
    }
}

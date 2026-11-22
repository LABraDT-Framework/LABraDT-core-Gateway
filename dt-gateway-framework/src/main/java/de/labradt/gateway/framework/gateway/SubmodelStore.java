package de.labradt.gateway.framework.gateway;

import de.labradt.gateway.framework.model.MachineConfigProperties;
import de.labradt.gateway.framework.model.SubmodelElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of the latest {@link SubmodelElement} per idShort.
 *
 * <p><strong>Deadband filtering:</strong> when enabled (default), listeners
 * are notified only on a meaningful change (different value or different
 * statusCode). The cache is always updated either way, so REST reads always
 * see the latest entry. This stops noisy listeners (data recorder, UI repaint)
 * from thrashing when the poll cadence is faster than the value-change rate
 * on the machine.
 *
 * <p>ISO 23247-2 traceability: holds the digital twin entity's digital
 * representation (§6.3.1.3); the deadband notification policy is data
 * pre-processing in the sense of §6.2.1.2 ("filtering and aggregation").
 */
@Component
public class SubmodelStore {

    private static final Logger log = LoggerFactory.getLogger(SubmodelStore.class);

    private final Map<String, SubmodelElement> store = new ConcurrentHashMap<>();

    /**
     * The last element per key that change-listeners were actually notified
     * about. Deadband suppression must compare against this, not against the
     * last stored value; otherwise slow drift (each step below the threshold)
     * would never produce a notification no matter how far the value moves.
     */
    private final Map<String, SubmodelElement> lastNotified = new ConcurrentHashMap<>();

    private final List<SubmodelStoreListener> listeners;
    private final boolean deadbandEnabled;

    public SubmodelStore(List<SubmodelStoreListener> listeners,
                         MachineConfigProperties properties) {
        this.listeners = listeners;
        this.deadbandEnabled = properties.getDeadband().isEnabled();
        log.info("SubmodelStore initialised, deadband filtering: {}",
                deadbandEnabled ? "enabled" : "disabled");
    }

    public void put(SubmodelElement element) {
        // Stamp the observation moment: when the gateway saw the signal,
        // distinct from element.timestamp which is when the VALUE changed.
        // Staleness checks use observedAt because they ask whether the gateway
        // is still reading, not whether the value is still moving.
        element.setObservedAt(LocalDateTime.now());

        // Keyed by the routing key ("machineId.idShort") so two machines with
        // the same element name never collide; element.idShort stays clean.
        String key = element.routingKey() != null ? element.routingKey() : element.getIdShort();
        if (key == null || key.isBlank()) {
            log.warn("Element has neither routingKey nor idShort, skipping store put");
            return;
        }
        store.put(key, element);
        SubmodelElement previous = lastNotified.get(key);

        // Always fire the observation pulse: freshness consumers (UI) need
        // to know we saw this signal even when the value did not change.
        for (SubmodelStoreListener listener : listeners) {
            try {
                listener.onElementObserved(element);
            } catch (Exception e) {
                log.warn("Listener {} threw on onElementObserved for {}: {}",
                        listener.getClass().getSimpleName(),
                        element.getIdShort(),
                        e.getMessage());
            }
        }

        if (deadbandEnabled && !isMeaningfulChange(previous, element)) {
            return; // change below threshold; skip the "changed" callback
        }
        lastNotified.put(key, element);

        for (SubmodelStoreListener listener : listeners) {
            try {
                listener.onElementStored(element);
            } catch (Exception e) {
                log.warn("Listener {} threw on onElementStored for {}: {}",
                        listener.getClass().getSimpleName(),
                        element.getIdShort(),
                        e.getMessage());
            }
        }
    }

    public SubmodelElement get(String idShort) {
        return store.get(idShort);
    }

    public Map<String, SubmodelElement> getAll() {
        return Collections.unmodifiableMap(store);
    }

    public int size() {
        return store.size();
    }

    /**
     * A "meaningful" change is one a downstream listener would care about:
     * the status flag flipped (e.g. OK to OUT_OF_RANGE while numerically
     * identical is still worth recording), or the value moved by at least the
     * mapping's deadband relative to the last NOTIFIED value. For numeric
     * elements the deadband is an absolute threshold in the element's own
     * unit; non-numeric values and elements without a usable deadband fall
     * back to exact comparison. Timestamps alone never count: a value that
     * simply persists should not produce repeated notifications.
     */
    private static boolean isMeaningfulChange(SubmodelElement previous, SubmodelElement current) {
        if (previous == null) return true;
        if (!Objects.equals(previous.statusCode(), current.statusCode())) return true;
        if (Objects.equals(previous.getValue(), current.getValue())) return false;

        double deadband = current.deadband();
        if (deadband > 0 && previous.getValue() != null && current.getValue() != null) {
            try {
                double before = Double.parseDouble(previous.getValue());
                double after = Double.parseDouble(current.getValue());
                return Math.abs(after - before) >= deadband;
            } catch (NumberFormatException ignored) {
                // not numeric; exact comparison already said "different"
            }
        }
        return true;
    }
}

package de.labradt.gateway.framework.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.labradt.gateway.framework.model.SubmodelElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Periodically re-broadcasts the full store to every SSE client as
 * {@code keyframe} events, with the machine-level staleness flag applied.
 *
 * <p>The change stream ({@link SseBroadcaster}) is efficient but silent when
 * nothing changes, which is precisely the case when a machine goes quiet, so
 * a consumer watching only change events would never learn the machine died.
 * Keyframes close that gap: they are the periodic ground truth that
 * (a) lets a consumer detect a machine transitioning to {@code STALE},
 * (b) corrects any update missed during a transient disconnect, and
 * (c) gives consumers that prefer a fixed-interval full picture exactly that,
 * on the same stream. The analogy is video keyframes (I-frames) interleaved
 * with deltas (P-frames).
 *
 * <p>This realizes the time-based synchronization method named alongside the
 * event-based method in the digital twin framework. [ISO 23247-1:2021,
 * §5.3.13] Disable with
 * {@code dt.framework.stream.keyframe-enabled=false}; tune the cadence with
 * {@code dt.framework.stream.keyframe-interval-ms}.
 */
@Component
@ConditionalOnProperty(prefix = "dt.framework.stream", name = "keyframe-enabled",
        havingValue = "true", matchIfMissing = true)
public class SseKeyframeBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(SseKeyframeBroadcaster.class);

    private final SubmodelStore store;
    private final SseEmitterRegistry registry;
    private final FreshnessFlagger freshness;
    private final ObjectMapper objectMapper;

    public SseKeyframeBroadcaster(SubmodelStore store,
                                  SseEmitterRegistry registry,
                                  FreshnessFlagger freshness,
                                  ObjectMapper objectMapper) {
        this.store = store;
        this.registry = registry;
        this.freshness = freshness;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${dt.framework.stream.keyframe-interval-ms:30000}")
    public void broadcastKeyframe() {
        if (registry.size() == 0) return; // no subscribers

        // Snapshot emitters once so the inner loop iterates a stable list and
        // getAll() is not called once per element (O(N*M) registry snapshots).
        List<SseEmitter> emitters = registry.getAll();
        List<SseEmitter> dead = new ArrayList<>();
        int sent = 0;
        for (Map.Entry<String, SubmodelElement> entry : store.getAll().entrySet()) {
            SubmodelElement flagged = freshness.flag(entry.getKey(), entry.getValue());
            String json;
            try {
                json = objectMapper.writeValueAsString(
                        Map.of("key", entry.getKey(), "property", flagged));
            } catch (JsonProcessingException e) {
                log.warn("Keyframe: failed to serialise '{}': {}", entry.getKey(), e.getMessage());
                continue;
            }
            for (SseEmitter emitter : emitters) {
                if (dead.contains(emitter)) continue;
                try {
                    emitter.send(SseEmitter.event().name("keyframe").data(json));
                } catch (IOException e) {
                    dead.add(emitter);
                }
            }
            sent++;
        }
        dead.forEach(registry::remove);
        log.debug("Keyframe broadcast: {} elements to {} clients", sent, registry.size());
    }
}

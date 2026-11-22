package de.labradt.gateway.framework.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.labradt.gateway.framework.model.SubmodelElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Pushes every meaningful value change to all connected SSE clients as a
 * JSON-encoded {@link SubmodelElement}.
 *
 * <p>Implements {@link SubmodelStoreListener} so Spring auto-discovers it
 * alongside the data recorder (and any plugin listener such as the Swing dashboard),
 * with zero framework changes needed.
 *
 * <p><strong>Which callback is used:</strong> {@code onElementStored} fires
 * only when the value or statusCode actually changed (deadband-filtered).
 * This is intentional: SSE clients should not receive the same value
 * repeated every second just because the poll loop ran. They receive updates
 * only when something genuinely changed.
 *
 * <p><strong>Dead emitter cleanup:</strong> if a send attempt throws
 * (client disconnected mid-stream), the emitter is collected and removed
 * from the registry at the end of the broadcast pass. The
 * {@link CopyOnWriteArrayList} inside the registry makes this safe: the
 * iteration is over a snapshot, so removing while iterating is fine.
 */
@Component
public class SseBroadcaster implements SubmodelStoreListener {

    private static final Logger log = LoggerFactory.getLogger(SseBroadcaster.class);

    private final SseEmitterRegistry registry;
    private final ObjectMapper objectMapper;

    public SseBroadcaster(SseEmitterRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    /**
     * Called by {@link SubmodelStore} whenever a value meaningfully changes.
     * Serialises the element to JSON and pushes it to every connected client.
     */
    @Override
    public void onElementStored(SubmodelElement element) {
        if (registry.size() == 0) {
            return; // no clients connected, nothing to do
        }

        String json;
        try {
            // The element itself is a pure standard AAS Property (no machine
            // context inside it), so the event wraps it with the routing key.
            json = objectMapper.writeValueAsString(java.util.Map.of(
                    "key", element.routingKey() == null ? element.getIdShort() : element.routingKey(),
                    "property", element));
        } catch (JsonProcessingException e) {
            log.warn("SSE: failed to serialise element '{}': {}", element.getIdShort(), e.getMessage());
            return;
        }

        List<SseEmitter> dead = new ArrayList<>();

        for (SseEmitter emitter : registry.getAll()) {
            try {
                emitter.send(
                        SseEmitter.event()
                                .name("update")          // event type; client filters on this
                                .data(json)              // the full SubmodelElement as JSON
                );
            } catch (IOException e) {
                // Client disconnected; mark for removal.
                // Do not remove inside the loop; CopyOnWriteArrayList iteration
                // is over a snapshot so it is safe, but we defer removal to keep
                // the loop body simple.
                dead.add(emitter);
            }
        }

        dead.forEach(registry::remove);
    }
}

package de.labradt.gateway.framework.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe registry of all currently connected SSE clients.
 *
 * <p>Uses {@link CopyOnWriteArrayList} so that the broadcaster can iterate
 * while the connection handler adds/removes entries concurrently: no
 * ConcurrentModificationException, no manual synchronisation needed.
 *
 * <p>Lifecycle: emitters are added when a client opens the stream endpoint
 * and removed automatically when the connection closes (completion, timeout,
 * or error) or when a send attempt fails.
 */
@Component
public class SseEmitterRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterRegistry.class);

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public void add(SseEmitter emitter) {
        emitters.add(emitter);
        log.info("SSE client connected, active streams: {}", emitters.size());
    }

    public void remove(SseEmitter emitter) {
        emitters.remove(emitter);
        log.info("SSE client disconnected, active streams: {}", emitters.size());
    }

    public List<SseEmitter> getAll() {
        return emitters;
    }

    public int size() {
        return emitters.size();
    }
}

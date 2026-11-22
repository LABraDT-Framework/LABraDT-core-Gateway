package de.labradt.gateway.framework.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.labradt.gateway.framework.adapter.AdapterRegistry;
import de.labradt.gateway.framework.adapter.ICPPSAdapter;
import de.labradt.gateway.framework.gateway.exception.MappingNotFoundException;
import de.labradt.gateway.framework.gateway.exception.WriteRejectedException;
import de.labradt.gateway.framework.model.MachineConfigProperties;
import de.labradt.gateway.framework.model.MachineConfigLoader;
import de.labradt.gateway.framework.model.SubmodelElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST contract exposed to AAS-side consumers. Elements are addressed by the
 * routing key "machineId.idShort"; every payload is a standard AAS Property.
 *
 * <pre>
 *   GET    /api/submodel                 all cached elements, keyed by routing key
 *   GET    /api/submodel/{key}           one element (404 if unknown, 204 if not yet polled)
 *   PATCH  /api/submodel/{key}/$value    write a new value to the machine
 *   GET    /api/submodel/health          liveness and per-machine connectivity
 *   GET    /api/submodel/stream          SSE: snapshot on connect, then change events
 * </pre>
 *
 * Reads are augmented with a freshness check: staleness is a MACHINE property
 * displayed on elements. If an element's machine is not currently delivering
 * data, its statusCode is flipped to "STALE" (CONSTANT elements exempt).
 *
 * Writes are gated by the global safe-mode flag, useful for the first lab
 * session against a real machine.
 */
@RestController
// ISO 23247-2 traceability: realizes the resource access and interchange
// sub-entity, the interoperability support FE (§6.3.3.1) and user interface FE
// (§6.4), exposing the digital twin to the user domain over REST/SSE, the
// protocols ISO 23247-4 (A.2.1) names as exemplars for this interface.
@RequestMapping("/api/submodel")
public class SubmodelController {

    private static final Logger log = LoggerFactory.getLogger(SubmodelController.class);

    private final SubmodelStore store;
    private final GatewayService gateway;
    private final MappingRegistry mappingRegistry;
    private final AdapterRegistry adapterRegistry;
    private final MachineConfigProperties properties;
    private final SseEmitterRegistry emitterRegistry;
    private final ObjectMapper objectMapper;
    private final FreshnessFlagger freshness;
    private final MachineConfigLoader configLoader;

    public SubmodelController(SubmodelStore store,
                              GatewayService gateway,
                              MappingRegistry mappingRegistry,
                              AdapterRegistry adapterRegistry,
                              MachineConfigProperties properties,
                              SseEmitterRegistry emitterRegistry,
                              ObjectMapper objectMapper,
                              FreshnessFlagger freshness,
                              MachineConfigLoader configLoader) {
        this.store = store;
        this.gateway = gateway;
        this.mappingRegistry = mappingRegistry;
        this.adapterRegistry = adapterRegistry;
        this.properties = properties;
        this.emitterRegistry = emitterRegistry;
        this.objectMapper = objectMapper;
        this.freshness = freshness;
        this.configLoader = configLoader;
    }

    @GetMapping("/configuration")
    public Map<String,Object> configuration() { return configLoader.configurationStatus(); }

    @GetMapping("/{idShort}")
    public ResponseEntity<SubmodelElement> getOne(@PathVariable String idShort) {
        if (!mappingRegistry.has(idShort)) {
            throw new MappingNotFoundException(idShort);
        }
        SubmodelElement element = store.get(idShort);
        if (element == null) {
            // Mapping exists but no value has been polled yet.
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(withStalenessFlag(idShort, element));
    }

    @GetMapping
    public ResponseEntity<Map<String, SubmodelElement>> getAll() {
        Map<String, SubmodelElement> flagged = store.getAll().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> withStalenessFlag(e.getKey(), e.getValue()),
                        (a, b) -> a,
                        LinkedHashMap::new));
        return ResponseEntity.ok(flagged);
    }

    @PatchMapping("/{idShort}/$value")
    public ResponseEntity<Void> writeValue(@PathVariable String idShort,
                                           @RequestBody Object value) {
        if (properties.isSafeMode()) {
            throw new WriteRejectedException(
                    "Safe mode is active; the gateway is read-only. Set dt.framework.safe-mode=false to enable writes.");
        }
        gateway.write(idShort, value);
        return ResponseEntity.noContent().build();
    }

    /**
     * SSE stream endpoint. Clients connect once and receive every value
     * change as a JSON-encoded {@link SubmodelElement} pushed by the gateway.
     *
     * <p><strong>On connect:</strong> the current snapshot of all cached
     * values is sent immediately as {@code snapshot} events so the client
     * has a complete picture before the first update arrives.
     *
     * <p><strong>On change:</strong> {@link SseBroadcaster} fires an
     * {@code update} event for every element whose value or statusCode changed.
     *
     * <p>The emitter has a 30-minute server-side timeout so half-open TCP
     * connections (client network died without a clean close) are eventually
     * reaped. The client should reconnect on timeout; the snapshot-on-connect
     * mechanism ensures it gets a full picture immediately.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L); // 30 minutes

        // Clean up registry when the connection ends for any reason.
        emitter.onCompletion(() -> emitterRegistry.remove(emitter));
        emitter.onTimeout(()    -> emitterRegistry.remove(emitter));
        emitter.onError(e       -> emitterRegistry.remove(emitter));

        // Register before sending the snapshot so no update is missed if a
        // value changes between the snapshot loop and registration.
        emitterRegistry.add(emitter);

        // Send every currently-cached value immediately so the client has a
        // full picture without waiting for the next change cycle. Each event
        // wraps the standard Property with its routing key.
        for (Map.Entry<String, SubmodelElement> entry : store.getAll().entrySet()) {
            try {
                SubmodelElement flagged = freshness.flag(entry.getKey(), entry.getValue());
                emitter.send(
                        SseEmitter.event()
                                .name("snapshot")
                                .data(objectMapper.writeValueAsString(
                                        Map.of("key", entry.getKey(), "property", flagged)))
                );
            } catch (IOException e) {
                // Client already gone; remove and bail out.
                emitterRegistry.remove(emitter);
                emitter.completeWithError(e);
                return emitter;
            }
        }

        log.info("SSE stream opened, sent snapshot of {} elements", store.size());
        return emitter;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("timestamp", Instant.now().toString());
        body.put("safeMode", properties.isSafeMode());
        body.put("mappings", mappingRegistry.size());
        body.put("storeSize", store.size());

        Map<String, String> adapters = new LinkedHashMap<>();
        for (ICPPSAdapter adapter : adapterRegistry.getAllAdapters()) {
            String state = !adapter.isConnected() ? "DISCONNECTED"
                    : adapter.isSourceAlive() ? "CONNECTED"
                    : "QUIET";   // transport up, machine silent beyond its window
            adapters.put(adapter.getMachineId(), state);
        }
        body.put("adapters", adapters);

        return ResponseEntity.ok(body);
    }

    /**
     * Staleness is a machine property displayed on elements: the element is
     * flagged STALE iff its machine is not currently delivering data. Delegated
     * to {@link FreshnessFlagger} so the REST path and the SSE keyframe path
     * share one definition of "alive".
     */
    private SubmodelElement withStalenessFlag(String routingKey, SubmodelElement element) {
        return freshness.flag(routingKey, element);
    }
}

# DT Gateway: Complete System Documentation

A component-by-component reference for the Digital Twin Middleware Framework, focused on its **MQTT integration** and documenting the system class by class as of July 2026.

---

## 1. What the system is

The project is a **protocol-agnostic IoT gateway** that connects heterogeneous shop-floor machines (CNC machines, injection molders) to the digital world. In this document, the machine-facing integration uses **MQTT**: the gateway receives telemetry through an MQTT broker, translates every signal into one uniform, standards-conformant data shape (an **AAS Property** per IDTA-01001 / IEC 63278-1), caches the latest value of each signal, and serves the whole picture to consumers over REST and Server-Sent Events (SSE). It also exposes a write endpoint, guarded by a safe-mode switch (the MQTT write path is demo-only).

The architecture is deliberately a **plugin system**: the core framework (`dt-gateway-framework`) knows nothing about any concrete protocol. Each protocol is a pair of independently built Maven modules (an *adapter* that speaks the wire protocol, and a *translator* that owns the payload semantics) discovered at startup through Spring Boot auto-configuration. MQTT demonstrates this extension model: its adapter and translator were added as plugin modules without modifying `dt-gateway-framework`.

The design is aligned with **ISO 23247** (Digital Twin framework for manufacturing). Key classes carry traceability comments mapping them to the standard's functional entities (device communication entity, data translation FE, synchronization FE, and so on).

### The one data shape

Everything the gateway produces, caches, and serves is a standard AAS `Property`:

```json
{
  "modelType": "Property",
  "idShort": "ClampForce",
  "category": "VARIABLE",
  "valueType": "xs:double",
  "value": "2200.0",
  "qualifiers": [
    { "kind": "ConceptQualifier", "type": "Unit", "valueType": "xs:string", "value": "kN" },
    { "kind": "ConceptQualifier", "type": "StatusCode", "valueType": "xs:string", "value": "OK" }
  ]
}
```

There is no separate internal model plus export layer. The Java bean `SubmodelElement` serializes directly to this shape (with optional fields such as `semanticId` and freshness qualifiers added when configured). Operational metadata the AAS metamodel has no slot for (unit, status code, freshness timestamps) rides along as `Qualifiers`, the metamodel's sanctioned extension mechanism.

### Addressing

Elements are addressed everywhere (store, REST, SSE) by a **routing key** of the form `machineId.idShort`, for example `molder-mqtt.ClampForce`. The `idShort` itself stays clean and AAS-pattern conformant; machine identity lives only in the routing key. Two machines can therefore expose the same element name without collision.

---

## 2. End-to-end data flow

### Read path (machine to consumer)

```text
MQTT publisher --> MQTT broker --> MqttAdapter (subscriptions + topic cache)
                                               |
SubmodelUpdater (scheduled)                     |
   |                                           |
   v                                           |
GatewayService.read(machineId.idShort)          |
   |                                           |
   +--> MqttAdapter.readAddress(topic) <-------+
            | RawValue (latest cached message)
            v
        MqttTranslator.translateValue(mapping)
            | SubmodelElement (standard AAS Property)
            v
        SubmodelStore (latest values; deadband-filtered notifications)
            |
            +--> SubmodelController     REST GET /api/submodel...
            +--> SseBroadcaster         SSE change events
            +--> SseKeyframeBroadcaster SSE periodic keyframes
            +--> JsonlDataRecorder      logs/data.jsonl
            +--> DashboardController    optional Swing dashboard
```

Every poll cycle (`dt.framework.poll-interval-ms`: 1000 ms by framework default, 2000 ms in the app's demo settings), `SubmodelUpdater` iterates the mappings, calls `GatewayService.read()` for each one, and stores the resulting property in `SubmodelStore`. The MQTT adapter subscribes to the configured topics and updates an in-memory cache when messages arrive. Scheduled reads use that cache instead of waiting for a fresh broker message.

### Write path (consumer to machine)

```
PATCH /api/submodel/{machineId.idShort}/$value
   │  (403 if safe mode is on)
   ▼
GatewayService.write()
   │  wraps the value in a SubmodelElement
   ▼
ITranslationLayer.reverseTranslateElement()   ← reverse value map, reverse unit
   │  RawValue with a typed native value          conversion, cast to configured type
   ▼
ICPPSAdapter.writeAddress()                   ← MQTT publish (demo-only write path)
```

For MQTT, the current write path publishes to a telemetry topic. This is a **demo-only, fire-and-forget operation**: a successful publish does not establish that the machine applied the value. Production control would need distinct command topics and an observed state or acknowledgment. The gateway's safe-mode setting can disable the PATCH endpoint entirely.

### Staleness model

A central design decision: **staleness is a property of the machine, displayed on its elements**. An element is served with `statusCode: "STALE"` when its machine is not currently delivering data, regardless of whether the value changed. A clamp force legitimately held at the same value for hours on a living machine is fresh; the same number from a machine the gateway cannot hear is last-known and labelled as such. Elements with category `CONSTANT` (serial numbers, material types) are exempt: a dead link does not invalidate a serial number.

**MQTT freshness:** a live broker connection does not prove that the machine is publishing. `MqttAdapter.isSourceAlive()` requires both a broker connection and at least one machine message received within `connection.quietWindowMs`. Set the window to `0` for report-by-exception machines that can legitimately remain silent. Retained messages help populate the cache after startup.

The flag is applied **at serving time**, never stored, so the cache always holds clean last-known values.

---

## 3. Repository layout

All modules build for **Java 21** (Spring Boot 4.0.4): `java.version` in `dt-gateway-framework` and
`dt-gateway-app`.

| Path | What it is |
|---|---|
| `pom.xml` | Aggregator POM building both modules in topological order |
| `dt-gateway-framework/` | Protocol-agnostic core: SPIs, registries, store, poll loop, REST/SSE, staleness |
| `dt-gateway-app/` | The runnable Spring Boot application plus machine configs and the web demo page |
| `tools/` | Machine onboarding wizard `add_machine.py` (install a config, browse a live machine, import a discovery snapshot), `import_nodes.py` and lab utilities (see `tools/LAB_TOOLS_README.md`); Python packages in `tools/requirements.txt` |
| `logs/` | Runtime output: app log, JSONL data log, discovery snapshots |

The MQTT integration is maintained in the internal project `gateway-plugins/mqtt-plugin`, containing the `mqtt-adapter` and `mqtt-translator` modules, mock publisher, and demo config (sections 5 and 6). The optional Swing desktop dashboard is maintained in `gateway-plugins/swing-plugin` (section 7).

The remaining top-level `.md` files (`README.md`, `ADDING_MACHINES.md`, `REST_CONTRACT.md`, `STANDARDS_MAPPING.md`, `ENGINE_INTEGRATION.md`) are user-facing guides for specific audiences.

---

## 4. Module: `dt-gateway-framework` (the core)

The heart of the system. Contains no protocol code at all. Three packages: `adapter`, `translation`, `model`, plus the `gateway` package with the runtime machinery.

### 4.1 Adapter SPI (`de.labradt.dt_framework.adapter`)

**`ICPPSAdapter`** (interface)

The protocol adapter Service Provider Interface. Anything that can talk to a machine implements this. Methods:
- `getMachineId()`: which machine this adapter instance serves (one adapter per machine).
- `connect()` / `disconnect()`: lifecycle. Implementations must make `connect()` non-blocking and self-healing.
- `readAddress(String)` / `readBatchAddresses(List)`: return a `RawValue` for an address; for MQTT, that address is a topic.
- `writeAddress(String, Object)`: write through the adapter; for MQTT this is a demo-only publish, without device acknowledgment.
- `getProtocol()`: which `ProtocolType` this adapter speaks.
- `isConnected()`: transport-level connectivity.
- `isSourceAlive()` (default method): machine-level liveness, distinct from broker connectivity. The MQTT adapter overrides the default `isConnected()` behavior to check recent publisher activity.
- `getCachedNodes()`: the adapter's current address-to-value cache (used for discovery snapshots and the wizard).
- `validateMappings(Collection)` (default no-op): optional pre-flight check that every configured address actually exists on the live machine; called by the framework once after first connect.

ISO 23247-2 traceability: realizes the device communication entity (data collecting FE and controlling FE).

**`AdapterPool`** (interface)
The extensibility seam. Each protocol's auto-configuration creates one `AdapterPool` bean containing all the adapter instances it manages (one per machine of that protocol). This indirection is what lets the framework collect adapters from plugin jars it has never heard of.

**`AdapterRegistry`** (`@Component`)
Constructor-injected with `List<AdapterPool>` (Spring gathers every pool bean from every plugin). Flattens them into a `machineId -> ICPPSAdapter` map. Lookup by machine id, existence check, and iteration for the health endpoint.

### 4.2 Translation SPI (`de.labradt.dt_framework.translation`)

**`ITranslationLayer`** (interface)
The translation Service Provider Interface: converts protocol payloads to typed `SubmodelElement`s and back.
- `translateValue(RawValue, AdapterMapping) -> SubmodelElement`: the read direction.
- `reverseTranslateElement(SubmodelElement, AdapterMapping) -> RawValue`: the write direction.
- `getProtocol()` / `supportsProtocol()`: registration keys.

ISO 23247-2 traceability: the cross-system entity's data translation FE (protocol conversion, syntax adaptation, semantic awareness).

**`TranslatorRegistry`** (`@Component`)
Constructor-injected with `List<ITranslationLayer>` (again auto-discovered from plugin jars). Maps `ProtocolType -> translator`, throws on lookup miss.

### 4.3 Domain model (`de.labradt.dt_framework.model`)

**`ProtocolType`** (enum)
The relevant protocol identifier here is `MQTT`. The shared enum also declares future-oriented identifiers such as `MODBUS`, `DDS`, and `PROFINET`; those do not imply an implemented plugin.

**`RawValue`**
What an adapter returns from a read: `address`, `value` (untyped `Object`), `rawType` (protocol-native type string), `timestamp`, `protocol`, `statusCode`. Pure data carrier between adapter and translator.

**`SubmodelElement`**
The single output data shape, a standards-conformant AAS `Property`. Carefully split into two faces:
- *Serialized bean getters* produce exactly the standard JSON: `modelType` (always `"Property"`), `idShort`, `category` (defaults to `VARIABLE`), `valueType`, `value`, `semanticId` (expanded into the metamodel's `Reference` structure with `ExternalReference`/`GlobalReference` keys), and `qualifiers` (a list built on the fly from unit, statusCode, observedAt, timestamp).
- *Internal accessors with non-bean names* (`routingKey()`, `unit()`, `statusCode()`, `deadband()`, `observedAt()`, `timestamp()` and so on) are invisible to Jackson, so internal state never leaks into the serialized standard shape.

Two distinct timestamps, and the distinction matters:
- `timestamp`: when the **value** last changed (becomes the `Timestamp` qualifier).
- `observedAt`: when the gateway last **successfully observed** the signal, set on every store put (becomes the `ObservedAt` qualifier). Staleness reasoning uses this one, because staleness asks "is the gateway still reading?", not "is the value still moving?".

Also carries a per-element `deadband` (copied from the mapping) used by the store's change-suppression logic, and a `copy()` method so staleness flagging never mutates the cached instance.

**`AdapterMapping`**
One signal mapping as authored by the config engineer. Two groups of fields:
- *Standard AAS fields, copied to the output verbatim*: `idShort`, `valueType` (xs: type), `category`, `semanticId`.
- *Gateway-side translation settings*: `sourceAddress` (MQTT topic), `unit`, `sourceUnit`/`targetUnit` (unit conversion pair), `validRange` (two-element array for range checking), `valueMap` (raw value to display value dictionary, for example `"0" -> "IDLE"`), `deadband` (absolute change-suppression threshold), plus `machineId` and `protocol` stamped in by the loader.

`routingKey()` returns `machineId + "." + idShort`.

**`ConnectionConfig`**
Per-machine connection block carried inside the machine's JSON file. For MQTT, `endpoint` is the broker URL; `username`/`password` are optional (blank means anonymous). Reconnect bounds are `reconnectInitialBackoffMs` (default 1 s) and `reconnectMaxBackoffMs` (default 30 s). MQTT uses `quietWindowMs` (default 15 s, `0` disables the silence check) and optional `topicFilter` (a subscription wildcard such as `factory/molder-mqtt/#`).

**`MachineConfig`**
One machine, fully described by one explicit JSON file: `machineId`, `machineType`, `protocol`, `connection`, `mappings`. Deliberately has no composition or template mechanism; an earlier template-fragment design was removed after user testing showed its failure modes were all silent.

**`MachineConfigLoader`** (`@Component`)
Loads every `*.json` under `dt.framework.config-path` (supports `classpath:` and `file:` locations), parses with Jackson, validates loudly, caches the result so the mapping registry and plugin auto-configuration share one parse. Validation rejects a whole file (with `[CONFIG][ERROR]` log lines) on: missing/duplicate `machineId`, missing `protocol`, missing `connection.endpoint`, empty `mappings`, missing `sourceAddress`, missing/duplicate/pattern-violating `idShort` (checked against the AAS metamodel pattern `[a-zA-Z][a-zA-Z0-9_]{0,127}`, constraint AASd-002), invalid `valueType` (must be one of the seven allowed xs: types), invalid `category`. After validation it stamps `machineId` and backfills `protocol` onto each mapping, and resolves `${ENV_VAR}` / `${ENV_VAR:default}` placeholders in credentials so secrets never live in JSON files.

**`MachineConfigProperties`** (`@ConfigurationProperties("dt.framework")`)
Framework settings bean: `configPath`, `safeMode` (global write kill-switch, all PATCHes return 403), `dataLog.enabled`/`dataLog.path` (JSONL recorder), `deadband.enabled` (listener notification filtering, default true).

**`StalenessPolicy`** (final utility class)
The single source of truth for the staleness rule described in section 2. Pure functions: `isStale(element, machineAlive)` and `applyTo(element, machineAlive)`, the latter returning either the element unchanged or a flagged **copy** with `statusCode = "STALE"`. The cache is never mutated.

### 4.4 Runtime machinery (`de.labradt.dt_framework.gateway`)

**`IGateway` / `GatewayService`** (`@Service`)
The orchestrator tying the three registries together. `read(routingKey)` resolves the mapping, checks the adapter exists and is connected (else `AdapterUnavailableException`, HTTP 503), reads the raw value from the adapter, and hands it to the protocol's translator. `write(routingKey, value)` wraps the incoming value in a `SubmodelElement`, runs the translator's reverse pipeline, and pushes the resulting typed native value through the adapter; failures become `GatewayWriteException` (HTTP 502).

**`MappingRegistry`** (`@Component`)
Indexes every mapping from every loaded machine config by routing key. Throws `MappingNotFoundException` (HTTP 404) on unknown keys.

**`SubmodelStore`** (`@Component`)
The in-memory digital representation: a `ConcurrentHashMap<routingKey, SubmodelElement>`. Every `put()`:
1. Stamps `observedAt = now`.
2. Updates the cache unconditionally (REST reads always see the latest).
3. Fires `onElementObserved` on every registered `SubmodelStoreListener` (the "I just saw this signal" pulse, needed by freshness UIs).
4. Applies **deadband filtering**: `onElementStored` fires only on a *meaningful change*, meaning the statusCode flipped, or the value moved at least the mapping's deadband relative to the **last notified** value (not the last stored value; comparing against last-notified prevents slow drift below the threshold from being suppressed forever). Non-numeric values fall back to exact comparison. Timestamps alone never count.

Listener exceptions are caught and logged so one broken consumer cannot poison the store.

**`SubmodelStoreListener`** (interface)
The observer SPI with the two callbacks above, both defaulted to no-ops. Implementations in this repository: `SseBroadcaster` and `JsonlDataRecorder`; in plugins: the Swing `DashboardController` (`gateway-plugins/swing-plugin`). Any new bean implementing it is auto-collected; this is the second plugin seam (output side) alongside `AdapterPool` (input side).

**`SubmodelUpdater`** (`@Component`, `@Scheduled`)
The poll loop, running at `dt.framework.poll-interval-ms` (default property value 1000 ms; the app sets 2000 ms). Each cycle it walks every mapping, calls `gateway.read()`, and puts the result in the store; per-mapping failures are counted and logged at debug, and a summary line is logged on cycle 1 and every 30th cycle. It also runs one-time post-connect mapping validation per machine (`adapter.validateMappings`) the first time each machine's adapter is seen connected. ISO 23247 traceability: the synchronization FE, time-based method.

**`FreshnessFlagger`** (`@Component`)
The one place the "is this machine alive" question is answered at serving time. Extracts the machineId prefix from the routing key, asks the adapter's `isSourceAlive()`, and delegates to `StalenessPolicy.applyTo`. Shared by the REST controller, the SSE snapshot, and the keyframe broadcaster so the read path and the stream can never disagree.

**`SubmodelController`** (`@RestController`, `/api/submodel`)
The REST and SSE contract:

| Endpoint | Behavior |
|---|---|
| `GET /api/submodel` | All cached elements keyed by routing key, staleness applied |
| `GET /api/submodel/{key}` | One element; 404 if the key has no mapping, 204 if mapped but not yet polled |
| `PATCH /api/submodel/{key}/$value` | Write-through to the machine; 403 in safe mode, 204 on success, 502 if the machine rejects the write |
| `GET /api/submodel/health` | `status`, `timestamp`, `safeMode`, mapping/store counts, and per-machine adapter state: `CONNECTED`, `QUIET` (transport up, machine silent beyond its window), or `DISCONNECTED` |
| `GET /api/submodel/stream` | SSE stream (see below) |

The SSE endpoint creates an emitter with a 30-minute server-side timeout (reaps half-open TCP connections), registers it **before** sending the snapshot (so no update between snapshot and registration can be missed), then immediately sends the full current store as `snapshot` events so the client has a complete picture before the first change arrives.

**`SseEmitterRegistry`** (`@Component`)
Thread-safe list of connected SSE clients backed by `CopyOnWriteArrayList` (broadcasters iterate a snapshot while the connection handler mutates concurrently). Emitters are removed on completion, timeout, error, or failed send.

**`SseBroadcaster`** (`@Component`, implements `SubmodelStoreListener`)
Pushes every meaningful change as an `update` event: `{"key": "<routingKey>", "property": {...standard Property...}}`. Uses `onElementStored` deliberately, so clients are not spammed with unchanged values every poll cycle. Dead emitters detected by a failed send are collected and removed after the pass.

**`SseKeyframeBroadcaster`** (`@Component`, `@Scheduled`, toggleable via `dt.framework.stream.keyframe-enabled`)
Periodically (default 30 s in code, 10 s in the app's properties) re-broadcasts the **entire store** as `keyframe` events with the staleness flag applied. Rationale: the change stream is silent exactly when a machine dies (nothing changes anymore), so a change-only consumer would never learn about STALE transitions. Keyframes are the periodic ground truth, analogous to video I-frames interleaved with P-frame deltas. They also recover updates missed during transient disconnects. This realizes ISO 23247-1's time-based synchronization method alongside the event-based one.

**`JsonlDataRecorder`** (`@Component`, enabled via `dt.framework.data-log.enabled=true`)
Appends one self-contained JSON line per meaningful change to an append-only file (default `logs/data.jsonl`): `recordedAt`, `key`, `idShort`, `value`, `valueType`, `unit`, `timestamp`, `statusCode`. Hand-rolled serialization with proper escaping, guarded by a lock, flushed per line, and failures never propagate back to the store. Readable with pandas (`read_json(lines=True)`) or `jq`.

**`FrameworkConfig`** (`@Configuration`, `@EnableScheduling`)
Provides the shared Jackson `ObjectMapper` (with `JavaTimeModule`, ISO-8601 dates) used by REST and SSE, and turns on Spring scheduling for the updater and keyframe broadcaster.

**Exception package** (`gateway.exception`)
`GatewayExceptionHandler` (`@RestControllerAdvice`) maps framework exceptions to a uniform error envelope `{status, error, message, timestamp}`:

| Exception | HTTP | Meaning |
|---|---|---|
| `MappingNotFoundException` | 404 | Routing key not configured |
| `AdapterUnavailableException` | 503 | No adapter for the machine, or adapter disconnected |
| `GatewayWriteException` | 502 | The downstream adapter or translator failed during the write path |
| `WriteRejectedException` | 403 | Policy rejection (safe mode) |
| `IllegalArgumentException` | 400 | Malformed request |
| anything else | 500 | Internal error |

---

## 5. Module: `mqtt-adapter` (plugin)

The MQTT adapter lives in the internal project `gateway-plugins/mqtt-plugin`; its `MODULES.md` contains the class-by-class plugin reference. The adapter establishes the broker connection, subscribes to mapped topics (or the configured `topicFilter`), receives pushed messages, and caches the latest value by topic. It also implements the `quietWindowMs` publisher-liveness check used to mark signals `STALE` when the machine stops reporting. The plugin project also contains the MQTT mock publisher and demo configuration.

---

## 6. Module: `mqtt-translator` (plugin)

The MQTT translator lives alongside the adapter in `gateway-plugins/mqtt-plugin`. It turns cached MQTT values into the gateway's typed AAS `Property` shape, applying configured mappings such as `valueMap`, unit conversion, and `validRange`. Supported input forms include scalar payloads and the JSON envelope `{"value": ..., "timestamp": "...", "quality": "GOOD"}`. The reverse translation method supports the demo write path. Its class-by-class reference is in the plugin project's `MODULES.md`.

---

## 7. Module: `swing-ui-plugin` (moved)

Moved to the internal project `gateway-plugins/swing-plugin`. Its `MODULES.md`
holds the class-by-class reference that was here.

---

## 8. Module: `dt-gateway-app` (the runnable application)

**`DtGatewayApplication`**

The only class: a standard `@SpringBootApplication` scanning `de.labradt.dt_framework`. Everything else (adapters, translators, UI) arrives via auto-configuration from the plugin jars on the classpath. This is the whole point: the app module is a thin composition root.

**`application.properties`** (default profile; demo settings for use with the plugins' mocks)
- `dt.framework.config-path=classpath:configs` (empty in the core: no machine files ship; the plugins bring demo configs)
- `dt.framework.poll-interval-ms=2000`
- Keyframes enabled every 10 s
- `safe-mode=false`, data log to `logs/data.jsonl`, deadband filtering on
- The MQTT and optional Swing UI switches are enabled in the demo settings, but take effect only when the respective plugin is on the classpath
- Rolling file logging to `logs/dt-gateway.log`

**Machine configs** (`dt-gateway-app/src/main/resources/configs/`): empty in the core (only `.gitkeep`, so the directory exists for the wizard). With no machine files, the gateway starts with zero machines. The MQTT demo config `molder-mqtt.json` lives in `gateway-plugins/mqtt-plugin/configs/`. To run the MQTT demo through the core app, copy that file into the app's `configs/` directory, include the MQTT plugin modules as application dependencies, and start the broker and publisher from the plugin's `mocks/` directory.

**`static/demo.html`**

A dark-themed, dependency-free page served at `/demo.html`. Its live-stream log consumes `snapshot` and `update` events from `/api/submodel/stream` and marks `STALE` values inline; this read-only view can display MQTT machines. **The existing write dropdown and quick-write buttons are hard-coded to a different demo machine, not to `molder-mqtt`, so they are not an MQTT write demonstration.** For MQTT write-path testing, use the REST PATCH endpoint with safe mode off, keeping in mind that MQTT writes are demo-only publishes. The page currently has no `keyframe` listener, and its snapshot counter is hard-coded to 25 elements.

---

## 9. Supporting tooling (not part of the Java build)

**MQTT mock publisher and broker scripts** are in `gateway-plugins/mqtt-plugin/mocks/`.

**`tools/requirements.txt`**: Python dependencies for the onboarding wizard; MQTT browsing uses `paho-mqtt`.

**`tools/add_machine.py`** (onboarding wizard)

Supports three onboarding workflows: (1) **install** a hand-written config after strict validation (including warnings for unknown or mistyped keys); (2) **browse MQTT** by briefly subscribing to broker topics, inspecting live messages, selecting signals, and generating a config; and (3) **import a discovery snapshot** into an existing config without connecting again. It asks for essential signal information such as name, type, and unit. No templating is required; a valid hand-written JSON file works identically. Plain-text passwords are rejected in install mode; use environment-variable placeholders.

---

## 10. Configuration reference (properties)

| Property | Default | Purpose |
|---|---|---|
| `dt.framework.config-path` | (unset) | Folder of machine JSON files (`classpath:` or `file:`) |
| `dt.framework.poll-interval-ms` | 1000 | Poll loop cadence |
| `dt.framework.safe-mode` | false | Global read-only switch; all writes 403 |
| `dt.framework.data-log.enabled` | false | JSONL change recorder |
| `dt.framework.data-log.path` | logs/data.jsonl | Recorder output file |
| `dt.framework.deadband.enabled` | true | Notify listeners only on meaningful change |
| `dt.framework.stream.keyframe-enabled` | true | Periodic full-state SSE keyframes |
| `dt.framework.stream.keyframe-interval-ms` | 30000 | Keyframe cadence |
| `dt.adapter.mqtt.enabled` | false | MQTT plugin on/off; only takes effect when the MQTT plugin (`gateway-plugins/mqtt-plugin`) is on the classpath |
| `dt.ui.enabled` | false | Swing dashboard (only with the Swing plugin, `gateway-plugins/swing-plugin`) |
| `dt.ui.title` | DT Gateway Dashboard | Window title (Swing plugin) |
| `dt.ui.status-refresh-ms` | 1000 | Status bar refresh (Swing plugin) |

## 11. MQTT machine config file reference (JSON)

This is an **MQTT-only** example. Use a real broker address, unique machine ID, and the actual topics published by the machine. Passwords are provided through environment-variable placeholders; comments are deliberately kept outside the JSON so the file can be used directly.

```json
{
  "machineId": "molder-mqtt",
  "machineType": "InjectionMolder",
  "protocol": "MQTT",
  "connection": {
    "endpoint": "tcp://localhost:1883",
    "username": "${DT_MOLDER_MQTT_USERNAME}",
    "password": "${DT_MOLDER_MQTT_PASSWORD}",
    "reconnectInitialBackoffMs": 1000,
    "reconnectMaxBackoffMs": 30000,
    "quietWindowMs": 5000,
    "topicFilter": "factory/molder-mqtt/#"
  },
  "mappings": [
    {
      "sourceAddress": "factory/molder-mqtt/clamp-force",
      "idShort": "ClampForce",
      "valueType": "xs:double",
      "category": "VARIABLE",
      "unit": "kN",
      "validRange": [0, 5000],
      "deadband": 10.0
    },
    {
      "sourceAddress": "factory/molder-mqtt/state",
      "idShort": "MachineState",
      "valueType": "xs:string",
      "category": "VARIABLE",
      "valueMap": {"0": "IDLE", "1": "RUNNING"}
    }
  ]
}
```

**Required:** `machineId` (unique), `protocol: "MQTT"`, `connection.endpoint`, and a nonempty `mappings` array. Each mapping needs a unique AAS-compliant `idShort`, a `sourceAddress` MQTT topic, and a valid `valueType`.

**Optional:** `machineType`, connection credentials, reconnect bounds, `quietWindowMs`, `topicFilter`, plus mapping properties such as `semanticId`, `unit`, `sourceUnit` / `targetUnit`, `validRange`, `valueMap`, and `deadband`. For anonymous MQTT brokers, omit `username` and `password` rather than using unset placeholders.

The MQTT adapter caches the most recent message per topic. Retained messages help populate this cache at startup. Payloads can be bare scalar values or JSON envelopes containing `value`, `timestamp`, and `quality`. Changing a machine's config requires restarting the gateway.

## 12. Extension recipes

**New protocol** (proven by the MQTT pair): create an adapter module implementing `ICPPSAdapter` with an auto-configuration exposing an `AdapterPool`, and a translator module implementing `ITranslationLayer` with its own auto-configuration; register both in `META-INF/spring/...AutoConfiguration.imports`; add the jars to `dt-gateway-app`'s dependencies. Zero framework changes.

**New output consumer** (proven by SSE, the recorder, and the Swing UI plugin): implement `SubmodelStoreListener` as a Spring bean. Use `onElementStored` for change-driven consumers, `onElementObserved` for freshness-driven ones.

**New MQTT machine**: place one JSON machine file in the configured `configs/` folder (or use the wizard), with its broker endpoint and mapped topics. No framework code change is needed. The MQTT plugin must be on the classpath, or the machine will be skipped.
# LABraDT Gateway — CPPS Connectivity and AAS Data Bridge

**Component:** Gateway Framework  
**Port:** 8085  
**Overview of all LABraDT projects and the architecture diagram:** [README.md](https://github.com/LABraDT-Framework)

The Gateway abstracts connectivity to physical machines and production systems. Its Gateway Framework provides REST and SSE interfaces for accessing AAS submodels and exchanging machine data. The interfaces `ICPPSAdapter` and `ITranslationLayer` decouple the framework from specific communication protocols and production system implementations, enabling connectivity to different machines and communication technologies.

```text
Machine / MQTT publisher
  → MQTT broker
  → MQTT adapter     (stateful plugin: subscriptions, cache, liveness)
  → MQTT translator  (raw MQTT payload → standard AAS Property)
  → SubmodelStore    (cached state, deadband-filtered notifications)
  → REST / SSE       (/api/submodel/<machineId>.<idShort>, freshness applied at egress)
```

Two facts define the design:

- **Adding an MQTT machine is configuration, not code.** Each machine is described by one JSON file in `dt-gateway-app/src/main/resources/configs/`.
- **MQTT integration is implemented as a plugin.** The adapter and translator live in the separate internal project `gateway-plugins/mqtt-plugin`. They were integrated without changes to `dt-gateway-framework`.

---

## Quick Start

**Prerequisites:** Java 21+, Maven 3.9+ (bundled as `apache-maven-3.9.16/`), and Python 3. Install the wizard's requirements with `pip install -r tools/requirements.txt`. For the demo, you also need the MQTT plugin's mock broker/publisher dependencies, including Mosquitto and `paho-mqtt` (see its README).

The core repository does **not** include the MQTT plugin or a prepopulated machine configuration. Before starting, build the MQTT plugin and add its `mqtt-adapter` and `mqtt-translator` modules as dependencies of `dt-gateway-app`, following the MQTT plugin README. Ensure `dt.adapter.mqtt.enabled=true` in `dt-gateway-app/src/main/resources/application.properties`.

```bash
# Terminal 1: start the MQTT demo broker and publisher
cd /path/to/gateway-plugins/mqtt-plugin/mocks
./run_demo.sh
```

```bash
# Terminal 2: from the DT Gateway repository root
cp /path/to/gateway-plugins/mqtt-plugin/configs/molder-mqtt.json \
  dt-gateway-app/src/main/resources/configs/

bash apache-maven-3.9.16/bin/mvn install -DskipTests
bash apache-maven-3.9.16/bin/mvn -pl dt-gateway-app spring-boot:run
```

```bash
# Terminal 3: verify that the MQTT machine is connected and providing data
curl http://localhost:8085/api/submodel/health | python3 -m json.tool
curl http://localhost:8085/api/submodel/molder-mqtt.InjectionPressure | python3 -m json.tool
```

The sample `molder-mqtt` machine ID and `InjectionPressure` signal come from the MQTT demo configuration. Replace them with your own `machineId` and mapping `idShort` when onboarding a real machine. The MQTT plugin README is the authoritative reference for building its modules and launching its mocks.

---

## How It Works

### Interface Definitions

The framework defines two small Service Provider Interfaces (SPIs) in the core. The MQTT plugin implements both; the gateway does not need to know how MQTT subscriptions or payload parsing work.

#### `ICPPSAdapter`: Transport and Machine Liveness

There is one stateful MQTT adapter instance per machine. It connects to the broker, subscribes to topics, caches the most recent values, and reports whether the machine is still publishing.

```java
public interface ICPPSAdapter {
    String getMachineId();
    void connect();                        // non-blocking, retry with backoff
    void disconnect();
    RawValue readAddress(String address);  // MQTT: latest cached value for the topic
    default boolean isSourceAlive() { return isConnected(); }
    List<RawValue> readBatchAddresses(List<String> addresses);
    void writeAddress(String address, Object value);
    ProtocolType getProtocol();
    boolean isConnected();
    Map<String, RawValue> getCachedNodes();
    default void validateMappings(Collection<AdapterMapping> mappings) {}
}
```

For MQTT, `isConnected()` indicates broker connectivity, but a live broker connection is **not** proof that the publisher is alive. The plugin uses `isSourceAlive()` and the configured `quietWindowMs` to distinguish a publishing machine from a silent one.

#### `ITranslationLayer`: Payload to AAS Property

The MQTT translator is stateless. It turns each raw message into a typed AAS Property, applying mapping options such as value maps, unit conversions, range checks, and status information.

```java
public interface ITranslationLayer {
    SubmodelElement translateValue(RawValue rawValue, AdapterMapping mapping);
    RawValue reverseTranslateElement(SubmodelElement element, AdapterMapping mapping);
    boolean supportsProtocol(ProtocolType protocol);
    ProtocolType getProtocol();
}
```

Both interfaces include Javadoc mapping their responsibilities to ISO 23247-2 (device communication entity and data translation functional entity).

Three more interfaces matter to consumers of the gateway:

- **Northbound:** REST and SSE, defined in [REST_CONTRACT.md](REST_CONTRACT.md). Consumer integration is described in [ENGINE_INTEGRATION.md](ENGINE_INTEGRATION.md).
- **Configuration:** one JSON file per MQTT machine, specified in [ADDING_MACHINES.md](ADDING_MACHINES.md).
- **Egress observers:** `SubmodelStoreListener`, used by SSE broadcasting, JSONL recording, and the optional Swing dashboard (`gateway-plugins/swing-plugin`).

### Is It Static?

**The contracts are stable:** the adapter and translator interfaces and the REST/SSE surface are designed to stay unchanged when an MQTT machine is added.

**The runtime wiring is configuration-driven:**

- Spring Boot discovers the MQTT plugin at startup through its `AutoConfiguration.imports` manifest and the application's Maven dependencies.
- The gateway loads MQTT machines from their JSON files at startup. Invalid configurations are rejected with messages explaining the relevant rule.
- The polling interval, safe mode, keyframe cadence, config path, and optional UI are controlled by properties rather than hard-coded per machine.

**Configuration is not hot-reloaded.** When you add or edit a machine file, restart the gateway to apply it.

---

## Tutorial

The following walkthrough starts the MQTT demo, inspects AAS Properties, tests freshness detection, and adds another MQTT machine.

### 1. Build the Framework and MQTT Plugin

Use the MQTT plugin README to build its `mqtt-adapter` and `mqtt-translator` modules and add them as dependencies of `dt-gateway-app`. From the gateway repository root:

```bash
bash apache-maven-3.9.16/bin/mvn install -DskipTests
```

The gateway core alone starts with **zero machines** because it does not ship protocol plugins or populated demo configs.

### 2. Start the MQTT Demo Publisher and Broker

The MQTT plugin contains a mock injection molder driven by a Python publisher and a Mosquitto broker on port `1883`.

```bash
# In the MQTT plugin project
cd /path/to/gateway-plugins/mqtt-plugin/mocks
./run_demo.sh
```

Install the mock prerequisites described in the MQTT plugin README first. The onboarding wizard's Python requirements can be installed with `pip install -r tools/requirements.txt` from the gateway root.

### 3. Install the MQTT Config and Start the Gateway

In a second terminal, from the DT Gateway repository root:

```bash
cp /path/to/gateway-plugins/mqtt-plugin/configs/molder-mqtt.json \
  dt-gateway-app/src/main/resources/configs/

bash apache-maven-3.9.16/bin/mvn -pl dt-gateway-app spring-boot:run
```

Verify the relevant application property:

```properties
dt.adapter.mqtt.enabled=true
```

The log should show the configuration loading, MQTT adapter connection, and polling updates. A machine with no available matching plugin is skipped (`N skipped` in the poll log and no adapter in health output).

If the optional Swing plugin (`gateway-plugins/swing-plugin`) is on the classpath, it can display the data in a desktop dashboard; disable it on a headless system with `dt.ui.enabled=false`.

### 4. Read MQTT-Derived AAS Properties

```bash
# Health of configured machines
curl http://localhost:8085/api/submodel/health | python3 -m json.tool

# The demo molder's MQTT injection-pressure signal
curl http://localhost:8085/api/submodel/molder-mqtt.InjectionPressure | python3 -m json.tool

# All exposed elements
curl http://localhost:8085/api/submodel | python3 -m json.tool
```

A signal is addressed as `<machineId>.<idShort>`. The returned value is an AAS Property, including type, value, and any applicable qualifiers such as unit and status.

### 5. Watch Live Updates Over SSE

```bash
curl -N http://localhost:8085/api/submodel/stream
```

The SSE stream provides an initial snapshot followed by deadband-filtered updates. In the described demo setup, an unconditional keyframe is sent every 10 seconds.

### 6. Observe MQTT Publisher-Failure Detection

Stop **only the MQTT publisher**, not the Mosquitto broker:

```bash
pkill -f mqtt_molder_publisher.py
```

After the `connection.quietWindowMs` interval elapses without messages from the machine, the molder's non-constant elements are served with a `StatusCode` qualifier of `STALE`. Broker connectivity alone does not clear the stale verdict. Restart the publisher using the MQTT plugin's command, for example:

```bash
# From the MQTT plugin project
python3 mocks/mqtt_molder_publisher.py
```

The next messages clear the stale verdict on a subsequent update cycle, without restarting the gateway. For publishers that legitimately remain silent for extended periods, the MQTT config may set `quietWindowMs` to `0`.

**Tip:** MQTT retained messages help populate the adapter's topic cache at subscription time. Without retained messages, a signal may be unavailable until the publisher sends a new value.

### 7. Understand MQTT Writes and Safe Mode

The gateway has a write path, but **the MQTT plugin's current write behavior is demonstration-only**: it publishes directly to the mapped telemetry topic. Real deployments generally need separate command topics and a confirmed state response.

Setting `dt.framework.safe-mode=true` disables writes through the gateway's policy gate; rejected requests receive HTTP 403 with a typed error response. See [ENGINE_INTEGRATION.md](ENGINE_INTEGRATION.md) for the complete REST write contract and response format.

### 8. Onboard Another MQTT Machine

You can create a configuration by copying the demo or by using the wizard:

```bash
# From the gateway repository root
python3 tools/add_machine.py
```

The wizard can validate and install a configuration or browse an MQTT broker to discover topics and help you choose mappings. Configure at least:

| JSON field | MQTT meaning |
|---|---|
| `machineId` | Unique identifier; also the element prefix in REST URLs |
| `protocol` | `"MQTT"` |
| `connection.endpoint` | MQTT broker address such as `tcp://localhost:1883` |
| `connection.quietWindowMs` | Machine-level silent-publisher detection interval |
| `mappings[].sourceAddress` | Topic to subscribe to |
| `mappings[].idShort` | Unique AAS element name within the machine |
| `mappings[].valueType` | AAS value type such as `xs:double` or `xs:boolean` |

The MQTT translator accepts bare scalar payloads or a JSON envelope such as:

```json
{"value": 125.4, "timestamp": "2026-11-20T08:00:00Z", "quality": "GOOD"}
```

Update the mapping topics and broker endpoint for the real publisher, then restart the gateway. More mapping fields, wizard modes, and validation rules are documented in [ADDING_MACHINES.md](ADDING_MACHINES.md).

### 9. Connect a Real MQTT Publisher Securely

Replace the demo broker address and topic names with those used by your machine. Keep broker credentials out of committed JSON files. The wizard can write environment-variable placeholders for credentials based on the machine ID; for example, a machine named `molder-2` uses `DT_MOLDER_2_USERNAME` and `DT_MOLDER_2_PASSWORD`.

```bash
export DT_MOLDER_2_USERNAME='mqtt-user'
export DT_MOLDER_2_PASSWORD='your-secret'
```

Use references in the connection configuration rather than plaintext secrets:

```json
{
  "connection": {
    "endpoint": "tcp://mqtt.example.local:1883",
    "username": "${DT_MOLDER_2_USERNAME}",
    "password": "${DT_MOLDER_2_PASSWORD}",
    "quietWindowMs": 5000
  }
}
```

This is a **connection-block illustration**, not a complete machine configuration. The full file also needs a unique `machineId`, `protocol: "MQTT"`, and mappings. The wizard's install mode rejects plaintext `connection.password` fields. Missing environment variables cause the loader to warn and leave credentials blank, so inspect startup and connection logs if authentication fails.

The documented lab setup uses plaintext MQTT transport; it does **not** establish a production TLS configuration. For real hardware, follow your infrastructure team's broker authentication and transport-security requirements, and evaluate whether demo-style MQTT writes should be disabled with safe mode.

---

## Extension Points

The Gateway is expandable in three ways:

1. **Add an MQTT machine:** supply a new JSON configuration with a unique `machineId`, the correct broker endpoint, and mapped topics. Restart; no Java changes are needed.
2. **Add an egress consumer:** implement `SubmodelStoreListener` and register it as a bean. The JSONL recorder, SSE broadcaster, and optional Swing dashboard illustrate this extension point.
3. **Add support for another protocol:** implement `ICPPSAdapter` and `ITranslationLayer` in new Maven modules, provide Spring auto-configuration, and add the modules as application dependencies. The MQTT adapter (`mqtt-adapter`) and translator (`mqtt-translator`) are the worked examples. The core continues to provide state caching, deadband filtering, freshness, REST/SSE, and recording.

The MQTT capability in `gateway-plugins/mqtt-plugin` was integrated without changes to `dt-gateway-framework`. One caveat: protocol identifiers are represented by a core `ProtocolType` enum, so a future protocol outside the existing values would need an additional enum constant.

---

## How to Run the Tests

```bash
bash apache-maven-3.9.16/bin/mvn verify
```

The source documentation identifies automated coverage for the application composition root. The MQTT plugin currently lacks automated tests; the MQTT mock-fleet runs are therefore an important integration check. Broader automated coverage remains future work.

---

## Plugins Folder

`DtGatewayApplication` starts through `PluginLauncher`. Every JAR below `plugins/` (one sub-folder per plugin, e.g. `plugins/mqtt-plugin-gateway-implementations/`) joins the classpath, and the `*.properties` files directly in `plugins/` provide defaults for system properties (e.g. `dt.adapter.mqtt.enabled=true`). So `mvn -pl dt-gateway-app spring-boot:run` loads the plugins without any pom change. The LABraDT UI writes this folder (System → Project → Apply to Projects, and at every Gateway start). Use `LABRADT_PLUGINS_DIR` (or `-Dlabradt.plugins.dir`) for another folder.

---

## Where Is X?

| I am looking for... | It is here |
|---|---|
| Adapter SPI (southbound contract) | `dt-gateway-framework/.../adapter/ICPPSAdapter.java` |
| Translator SPI (semantic contract) | `dt-gateway-framework/.../translation/ITranslationLayer.java` |
| AAS Property data model | `dt-gateway-framework/.../model/SubmodelElement.java` |
| Per-signal configuration model | `dt-gateway-framework/.../model/AdapterMapping.java` |
| Config loading and validation | `dt-gateway-framework/.../model/MachineConfigLoader.java` |
| Three runtime registries | `dt-gateway-framework/.../gateway/MappingRegistry.java`, `.../adapter/AdapterRegistry.java`, `.../translation/TranslatorRegistry.java` |
| Polling loop | `dt-gateway-framework/.../gateway/SubmodelUpdater.java` |
| Three-registry join (reading a signal) | `dt-gateway-framework/.../gateway/GatewayService.java` |
| Live state cache | `dt-gateway-framework/.../gateway/SubmodelStore.java` |
| Freshness rules and egress decoration | `dt-gateway-framework/.../model/StalenessPolicy.java`, `.../gateway/FreshnessFlagger.java` |
| REST and SSE endpoints | `dt-gateway-framework/.../gateway/SubmodelController.java`, `SseBroadcaster.java`, `SseKeyframeBroadcaster.java` |
| Append-only telemetry recorder | `dt-gateway-framework/.../gateway/JsonlDataRecorder.java` |
| Typed error responses | `dt-gateway-framework/.../gateway/exception/` |
| MQTT plugin (Eclipse Paho, cache, quiet-window liveness) | Separate internal project `gateway-plugins/mqtt-plugin` |
| Plugin discovery wiring | `*/src/main/resources/META-INF/spring/...AutoConfiguration.imports` in the plugin modules |
| MQTT machine config files | `dt-gateway-app/src/main/resources/configs/` (empty in the core repository) |
| Runtime settings (poll rate, safe mode, keyframes, UI) | `dt-gateway-app/src/main/resources/application.properties` |
| MQTT demo broker, publisher, configs | MQTT plugin project: `mocks/`, `configs/` |
| Machine onboarding wizard | `tools/add_machine.py` |
| Automated application tests | `dt-gateway-app/src/test/` |
| Optional desktop dashboard | Separate internal project `gateway-plugins/swing-plugin` (toggle `dt.ui.enabled`) |

For a deeper class-by-class reference, see [SYSTEM_DOCUMENTATION.md](SYSTEM_DOCUMENTATION.md).

---

## Troubleshooting

| Symptom | Check |
|---|---|
| Machine missing from health output | JSON config exists, unique `machineId`, gateway restarted, MQTT modules on the classpath, `dt.adapter.mqtt.enabled=true` |
| Machine skipped | The plugin dependencies and Spring auto-configuration; inspect `N skipped` in the poll log |
| No values arrive | Broker URL, username/password, exact topic spelling, publisher activity, and whether values have been published since subscription |
| Values start empty | The publisher may not use MQTT retained messages |
| Values become `STALE` | Publisher stopped or sends less frequently than `quietWindowMs` |
| Changed JSON has no effect | Configuration reload requires a gateway restart |
| Wizard flags unexpected keys | Correct the typo or mapping; its install mode performs stricter validation |

---

## Modules

| Module or project | Role |
|---|---|
| `dt-gateway-framework` | Protocol-agnostic core: SPIs, config validation, registries, polling, store, freshness, REST/SSE |
| `dt-gateway-app` | Runnable assembly: chooses plugins via Maven dependencies, holds configs and properties |
| `tools` | `add_machine.py` onboarding wizard and supporting utilities |
| `gateway-plugins/mqtt-plugin` | Separate project containing `mqtt-adapter`, `mqtt-translator`, demo broker/publisher, and sample MQTT configs; uses Eclipse Paho |
| `gateway-plugins/swing-plugin` | Optional desktop observer implemented as an egress plugin |

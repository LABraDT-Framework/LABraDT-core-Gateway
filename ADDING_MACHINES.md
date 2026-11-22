# Onboarding MQTT Machines into the DT Gateway

## Overview

The DT Gateway connects shop-floor machines to a digital-twin (AAS) layer. **Adding an MQTT machine is a configuration task, not a coding task.** Each machine has one explicit JSON file defining its identity, MQTT broker connection, subscribed topics, and signal mappings. The gateway loads these files at startup; there are no templates, inheritance, or hidden configuration merges.

You can write a configuration file yourself or use `tools/add_machine.py` to validate and install a file or discover topics from a live MQTT broker. The MQTT plugin lives in `gateway-plugins/mqtt-plugin`; it integrates with the existing gateway without requiring changes to the framework core.

## How MQTT data flows

| Component | Responsibility |
|---|---|
| MQTT publisher | Sends machine values to MQTT topics on a broker |
| MQTT adapter | Subscribes to mapped topics and caches the latest messages for each `machineId` |
| MQTT translator | Converts raw payloads to typed AAS Properties; applies value maps, unit conversion, and range checks |
| SubmodelStore | Holds the latest translated elements |
| REST / SSE API | Exposes elements as `/api/submodel/<machineId>.<elementName>` |

```text
Machine / MQTT publisher
  -> MQTT broker
  -> MQTT adapter (topic subscriptions, latest-value cache, liveness)
  -> MQTT translator (raw payload -> typed AAS Property)
  -> SubmodelStore
  -> REST / SSE (/api/submodel/<machineId>.<elementName>)
```

---

## 1. Prepare the MQTT machine configuration

Place one JSON file per machine in:

```text
dt-gateway-app/src/main/resources/configs/
```

The filename is only for organization. The `machineId` **inside** the file must be unique. The gateway reads the configuration directory on startup, so **restart the gateway after changing a machine file**.

### Example MQTT configuration

The broker address, topic names, and signal values below are **illustrative**. Replace them with the actual endpoint and topics published by your machine.

```json
{
  "machineId": "molder-mqtt",
  "machineType": "InjectionMolder",
  "protocol": "MQTT",
  "comment": "MQTT-connected injection molder",
  "connection": {
    "endpoint": "tcp://localhost:1883",
    "username": "${DT_LAB_USERNAME}",
    "password": "${DT_LAB_PASSWORD}",
    "quietWindowMs": 5000
  },
  "mappings": [
    {
      "sourceAddress": "factory/molder-mqtt/injection-pressure",
      "idShort": "InjectionPressure",
      "valueType": "xs:double",
      "category": "VARIABLE",
      "unit": "bar",
      "validRange": [0, 2500],
      "deadband": 1.0
    }
  ]
}
```

| Field | Meaning |
|---|---|
| `machineId` | Required, unique identifier; also the REST element prefix |
| `machineType` | Informational machine type |
| `protocol` | Set to `"MQTT"` |
| `connection.endpoint` | MQTT broker URL, e.g. `tcp://host:1883` |
| `connection.username` / `password` | Optional credentials; use environment-variable placeholders rather than plaintext passwords |
| `connection.quietWindowMs` | After this many milliseconds with no machine messages, non-constant elements become `STALE`; set to `0` for a legitimately silent, report-by-exception publisher |
| `connection.topicFilter` | Optional MQTT topic filter; otherwise the adapter subscribes to the configured mapping topics |
| `mappings` | Non-empty list of the machine's signals |

For every mapping, **`sourceAddress` is the MQTT topic**, not a device address. It must match a topic published by the machine. The `idShort` is the AAS element name and must be unique within that machine, using the pattern `[a-zA-Z][a-zA-Z0-9_]*`.

### Mapping options

```jsonc
{
  "sourceAddress": "factory/molder-mqtt/injection-pressure", // MQTT topic
  "idShort": "InjectionPressure",                    // AAS element name
  "valueType": "xs:double",                        // also xs:int, xs:boolean, xs:string
  "category": "VARIABLE",                          // CONSTANT, PARAMETER, VARIABLE
  "semanticId": "0173-1#02-AAJ979#001",             // optional semantic identifier
  "unit": "bar",                                   // optional unit label
  "validRange": [0, 2500],                         // optional; outside -> OUT_OF_RANGE
  "deadband": 1.0,                                 // optional change threshold
  "valueMap": { "0": "STOPPED", "1": "RUNNING" },    // optional enum mapping
  "sourceUnit": "mm",                              // optional source for unit conversion
  "targetUnit": "m"                                // optional conversion target
}
```

This block shows **available options**, not a second ready-to-use pressure mapping. Use only the settings that apply to a given signal, and choose a `valueType` consistent with the translated value.

Each element is addressed as `<machineId>.<elementName>`, for example `molder-mqtt.InjectionPressure`, so different machines' elements do not collide.

### Supported MQTT message payloads

The MQTT translator accepts both of these payload styles:

**Bare scalar:**

```text
125.4
```

**JSON envelope:**

```json
{"value":125.4,"timestamp":"2026-11-20T08:00:00Z","quality":"GOOD"}
```

It then exposes a standard AAS Property, with metadata such as unit and status represented as qualifiers. For example:

```json
{
  "modelType": "Property",
  "idShort": "InjectionPressure",
  "category": "VARIABLE",
  "valueType": "xs:double",
  "value": "125.4",
  "qualifiers": [
    {"kind":"ConceptQualifier","type":"Unit","valueType":"xs:string","value":"bar"},
    {"kind":"ConceptQualifier","type":"StatusCode","valueType":"xs:string","value":"OK"}
  ]
}
```

---

## 2. Add an MQTT machine

### Option A: Copy an existing MQTT configuration

From the gateway project root, copy the example config from the MQTT plugin:

```bash
cp gateway-plugins/mqtt-plugin/configs/molder-mqtt.json \
   dt-gateway-app/src/main/resources/configs/molder-mqtt-2.json
```

The gateway application's default `configs/` directory is initially empty; plugin directories contain examples.

1. Set a new, unique `machineId` (for example `molder-mqtt-2`).
2. Update `connection.endpoint` to the correct MQTT broker.
3. Update every mapping's `sourceAddress` to the topics published by that machine.
4. Add, remove, or adjust mappings to match the machine's actual signals.
5. Restart the gateway and verify the machine's elements via the REST API.

The machine's JSON file deliberately contains all its mappings. This duplicates some definitions across similar machines, but keeps each file self-contained and predictable.

### Option B: Use the machine wizard

Run the wizard from the gateway project root:

```bash
python3 tools/add_machine.py
```

The wizard supports three workflows:

1. **Validate and install a JSON file.** Supply an existing MQTT config. The wizard validates required fields, checks for a unique `machineId`, warns about unknown or misspelled keys (such as `validRnage`), and copies the file to the configured directory. Plaintext `connection.password` is rejected; use a `${ENV_VAR}` placeholder or `passwordEnv`.
2. **Browse a live MQTT broker.** Supply its endpoint. The wizard subscribes briefly, shows discovered topics with values and inferred types, and lets you select signals and create a configuration. Afterwards, edit the JSON to add options such as `valueMap`, `validRange`, or `deadband`.
3. **Import a discovery snapshot, if available.** If the gateway has written `logs/discovered-nodes-<machineId>.txt`, the wizard can use it to merge selected signals into an existing config without making another network connection.

For authenticated connections, the wizard can browse using supplied credentials without writing the password directly into the config. It generates environment-variable placeholders using the machine ID, for example `DT_MOLDER_2_USERNAME` and `DT_MOLDER_2_PASSWORD` for `molder-2`. Set the required variables before starting the gateway; without a username variable, the gateway attempts anonymous access.

> To remove a machine from the gateway configuration, delete its JSON file from `dt-gateway-app/src/main/resources/configs/` and restart the gateway.

---

## 3. Start and verify the MQTT setup

### Prerequisites

The following commands are examples for a macOS development environment using the MQTT plugin's mock publisher:

```bash
brew install mosquitto
pip3 install paho-mqtt
```

`mosquitto` supplies the MQTT broker. `paho-mqtt` is used by the mock publisher and wizard.

### Build and run

From the gateway project root, build the project after Java changes:

```bash
bash apache-maven-3.9.16/bin/mvn install -DskipTests
```

**Terminal 1 — launch the MQTT plugin's mocks (broker and publisher):**

```bash
cd gateway-plugins/mqtt-plugin/mocks
./run_mocks.sh
```

**Terminal 2 — from the gateway project root, start the gateway:**

```bash
bash apache-maven-3.9.16/bin/mvn -pl dt-gateway-app spring-boot:run
```

Ensure the MQTT plugin is on the application's classpath and enabled. The relevant `application.properties` settings are:

```properties
dt.framework.config-path=classpath:configs
dt.adapter.mqtt.enabled=true
dt.framework.poll-interval-ms=2000
```

### Check the API

```bash
# Overview of machine connectivity
curl http://localhost:8085/api/submodel/health | python3 -m json.tool

# A mapped value from the example MQTT machine
curl http://localhost:8085/api/submodel/molder-mqtt.InjectionPressure
```

Use your configured `machineId` and mapping `idShort` in the second URL. The gateway serves the latest cached MQTT value, translated into an AAS Property.

### MQTT behavior to keep in mind

- **Subscriptions:** The adapter subscribes to the topics in the mappings, or optionally uses `connection.topicFilter`.
- **Startup values:** Use **retained MQTT messages** on the publisher when you want the adapter's cache populated as soon as it subscribes.
- **Dead-publisher detection:** A working broker connection does *not* prove the machine is alive. If the machine has not sent messages within `quietWindowMs`, its non-constant elements are marked `STALE` together.
- **Writes:** The current write path publishes directly to the telemetry topic and is intended for demonstrations only.

---

## Troubleshooting

| Symptom | What to check |
|---|---|
| Machine does not appear | Its JSON file is in `dt-gateway-app/src/main/resources/configs/`, the `machineId` is unique, and the gateway was restarted |
| Machine is skipped | The MQTT plugin is on the classpath and `dt.adapter.mqtt.enabled=true`; inspect startup/poll logs for skipped machines |
| No signal value arrives | Broker URL, credentials, exact topic spelling in `sourceAddress`, and whether the publisher is sending messages |
| Signal is `STALE` | The publisher may have stopped; compare its publish frequency with `connection.quietWindowMs` |
| No initial value after startup | The publisher may not have sent a new message; consider retained messages |
| Config changes do nothing | Restart the gateway, because files are read at startup |
| Validation warnings | Run `tools/add_machine.py` in file-install mode to catch unknown keys and typos |

## Current limitations and future work

- **No hot reload:** Configuration changes require a gateway restart.
- **No built-in reuse for identical machines:** Copy and edit separate machine files; differences between copies can drift.
- **Classpath configuration:** `classpath:configs` works in development; deployed JARs should use an external directory such as `file:./configs`.
- **Lenient loader:** Unknown keys may be ignored at startup; the wizard's validation detects them.
- **Limited MQTT payload formats:** The translator supports bare scalars or its JSON envelope, not arbitrary payload structures such as Sparkplug B or multi-field JSON without additional extraction logic.
- **Demo-only MQTT writes:** Production command topics and confirmation from published state are not implemented by the shown setup.
- **No TLS in the lab configuration:** Broker connections in the example setup are plaintext.
- **Wizard browse authentication:** The browse workflow supports anonymous or username/password access, not certificate-based authentication.


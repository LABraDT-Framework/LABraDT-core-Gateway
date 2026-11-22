# Standards Mapping: ISO 23247 & Asset Administration Shell (MQTT)

How the MQTT-based DT Gateway relates to the two standards families that govern digital twins in manufacturing, with clause-level traceability. The same tags appear as Javadoc on the corresponding classes.

- **ISO 23247** (Automation systems and integration — Digital twin framework for manufacturing, Parts 1–4, 2021) describes the **architecture**: domains, entities, and functional entities (FEs). It is technology-neutral and does not mandate a particular device communication protocol.
- **Asset Administration Shell (AAS)**, IDTA-01001 metamodel / IEC 63278-1, describes the **digital representation**: Submodels, SubmodelElements, identifiers, and semantics.

The standards are complementary: ISO 23247 describes *where the gateway sits and what it does*; AAS describes *the shape of the data it exposes*. The gateway implements a subset of the ISO 23247-2 functional architecture and serves MQTT-derived live state as IDTA-01001-aligned `Property` elements. **This is an element-level alignment claim, not a claim of full ISO 23247 or AAS API conformance.**

---

## 1. ISO 23247-2 functional view → MQTT implementation

| ISO 23247-2 clause | Functional entity | MQTT-focused implementation | Status |
|---|---|---|---|
| §5.2.2 | Observable manufacturing domain | Shop-floor equipment, including machines publishing their telemetry over MQTT; the physical equipment is outside the gateway | Outside framework, as defined |
| §6.2.1.1 | Data collecting FE | `MqttAdapter` in `gateway-plugins/mqtt-plugin` subscribes to mapped MQTT topics and exposes the latest message via `ICPPSAdapter.readAddress()` | ✓ |
| §6.2.1.2 | Data pre-processing FE (filtering and aggregation) | Deadband filtering in `SubmodelStore`, range checks in the translator, and staleness policy for silent publishers | ✓ |
| §6.2.1.3 | Collection identification FE | Per-machine/topic mapping configuration and `validateMappings()` | ✓ |
| §6.2.2.1 | Controlling FE (commands understood by a device) | `writeAddress()` and reverse translation can publish an MQTT value, but the current demo writes to a telemetry topic without a device acknowledgment or confirmed actuation | Partial — demo-only writes |
| §6.2.2.3 | Control identification FE | `machineId`-keyed adapter routing and per-signal MQTT topic mapping | ✓ (routing) |
| §6.3.1.1 | Synchronization FE | `SubmodelUpdater` periodically transfers the MQTT adapter's cached topic values into the digital representation | ✓ |
| §6.3.1.3 | Digital representation FE | `MqttTranslator` → `SubmodelElement` → `SubmodelStore` | ✓ |
| §6.3.1.2 | Presentation FE | The REST/SSE-backed `static/demo.html` displays live properties and freshness; optional Swing dashboard via `gateway-plugins/swing-plugin`. The demo page's existing quick-write controls are not an MQTT-specific write demonstration | ✓ (read-side presentation) |
| §6.3.3.1 | Interoperability support FE | REST API and SSE stream serving standard-shaped AAS `Property` elements | ✓ |
| §6.4 | User interface FE | `SubmodelController` endpoints; web demo and optional dashboard provide user-facing views | ✓ |
| §6.5.3 | Data translation FE (protocol conversion, syntax adaptation, semantic awareness) | `ITranslationLayer`, implemented for MQTT by `MqttTranslator` in `gateway-plugins/mqtt-plugin` | ✓ |
| §6.5.1 | Data assurance FE | `validRange`, `statusCode`, and machine-wide `STALE` flags driven by MQTT publisher liveness (`quietWindowMs`) | Partial |
| §6.3.3.2 / §6.5.2 | Access control / security support FE | Safe mode restricts writes; no application authentication or TLS in the described lab setup | ✗ Gap |
| §6.3.3.3 | Plug-and-play support FE | One-file-per-machine MQTT onboarding, but applying a new configuration requires a gateway restart | Partial |
| §6.3.2.x | Simulation / analytics / reporting FEs | `JsonlDataRecorder` records changes; simulation and analytics are not implemented | ✗ Out of scope |

### MQTT communication in the ISO 23247 architecture

For this implementation, **MQTT (ISO/IEC 20922)** connects the observable manufacturing equipment to the gateway through a broker. The northbound consumer interface uses **REST/HTTP and SSE**. The architecture remains protocol-neutral, but the implementation and examples documented here are MQTT-specific.

```text
Machine / MQTT publisher
       │ MQTT telemetry messages
       ▼
   MQTT broker
       │ topic subscriptions
       ▼
 MqttAdapter (cached RawValue per topic)
       ▼
 MqttTranslator (typed AAS Property)
       ▼
 SubmodelStore (latest values, deadband)
       ▼
 REST / SSE consumers (freshness applied when served)
```

A broker connection alone does not prove the machine is active. `MqttAdapter.isSourceAlive()` also checks whether the machine has published within `connection.quietWindowMs`. When the publisher goes silent, non-constant properties are served as `STALE`. A `quietWindowMs` value of `0` disables this silence check for machines that publish only on exceptions.

**Scope claim for the thesis:** the MQTT gateway demonstrates the device communication entity's *data-collection* functions, the cross-system *data translation* FE, and the synchronization, representation, and read-side presentation functions of the digital twin entity. **Production-grade device control is not established** by the current fire-and-forget MQTT demo write path. Simulation/analytics and the security FEs are outside the implemented scope.

---

## 2. AAS alignment

### The design: a standard Property is the gateway's data model

There is no separate AAS export layer. For each MQTT topic, the configuration author supplies standard fields (`idShort`, `valueType`, `category`, `semanticId`) alongside gateway translation settings (`sourceAddress` as an MQTT topic, `valueMap`, `validRange`, units, and `deadband`). The loader rejects configuration files that violate its implemented checks for the AAS `idShort` pattern (AASd-002), supported XSD value types, and category vocabulary. `MqttTranslator` produces a `SubmodelElement` that is serialized to a standard-shaped AAS `Property` through REST and SSE.

| AAS requirement (IDTA-01001) | MQTT gateway implementation |
|---|---|
| Property structure (`modelType`, `idShort`, `valueType`, `value`) | `SubmodelElement` serializes to the `Property` shape |
| `idShort` pattern `[a-zA-Z][a-zA-Z0-9_]*` (AASd-002) | Enforced at configuration load and by the onboarding wizard; invalid names are rejected rather than silently sanitized |
| XSD value types | For example, `xs:double`, `xs:int`, `xs:boolean`, and `xs:string` are configured per MQTT mapping and emitted in the property |
| `semanticId` | Supplied by the configuration author and emitted as an `ExternalReference`; the author is responsible for choosing accurate semantics |
| `CONSTANT` / `PARAMETER` / `VARIABLE` category | Configured per property; `CONSTANT` is exempt from machine-liveness staleness marking |
| Machine identity | Kept in the routing key `machineId.idShort` (for example, `molder-mqtt.ClampForce`), not inserted into the `Property` itself; the key is used in REST URLs and SSE wrappers |

For example, an MQTT telemetry message received on the topic configured for `ClampForce` can be represented at:

```text
GET /api/submodel/molder-mqtt.ClampForce
```

This endpoint returns the mapped `Property` with qualifiers describing its units and status, including `STALE` when the publisher is no longer considered alive.

### Documented deviations and boundaries

- **`Unit`, `StatusCode`, `ObservedAt`, and `Timestamp` are encoded as qualifiers.** Units canonically belong to semantic definitions such as IEC 61360 ConceptDescriptions; the gateway uses AAS qualifiers to carry operational metadata and freshness information.
- **Elements only, no complete Submodel or AAS envelope.** The gateway supplies `Property` elements to a consuming AAS engine. Assembling Submodels and Asset Administration Shells with globally unique identifiers is the consuming engine's responsibility.
- **Gateway-native REST API.** Its `/api/submodel/...` endpoints are **not** the standardized AAS Part 2 API (IDTA-01002).
- **MQTT payloads have a defined, limited shape.** The translator handles bare scalar values and the configured JSON envelope (for example `{"value": 2200, "timestamp": "...", "quality": "GOOD"}`). It does not provide arbitrary payload extraction for formats such as Sparkplug B or multi-field JSON.

---

## 3. Known gaps (both standards)

1. **Security:** ISO 23247-1 §5.3.11 establishes security requirements. The documented gateway uses no application-level authentication and has plaintext broker/server transport in its lab setup. Safe mode is a write restriction, not an access-control system.
2. **Production-grade MQTT control:** the current write path performs a demo-only, fire-and-forget publish to a telemetry topic. A production design needs distinct command topics, suitable authorization, and an acknowledgment or observed state change.
3. **Simulation / analytics FEs:** not implemented; outside the gateway's present scope.
4. **Restart-to-apply:** a new MQTT machine file is loaded only at startup, limiting the plug-and-play claim (§6.3.3.3).
5. **Properties only:** no AAS Operations or Events, and no equivalent Function/Event gateway elements.
6. **Hierarchical modeling:** the gateway exposes a flat `machineId.idShort` namespace, not a hierarchy spanning IEC 62264 levels (ISO 23247-1 §5.3.15).
7. **AAS Part 2 API (IDTA-01002):** the REST surface is native even though the data it serves is shaped as metamodel `Property` elements. Supporting standard AAS clients would require Submodel envelopes with globally unique IRIs (encoded in URLs), standardized `/submodels/.../submodel-elements/...` routes and `$value` serialization, standard `Result` error responses, and paging. This work is deferred or can be delegated to an AAS repository such as Eclipse BaSyx.
8. **MQTT payload interoperability:** the JSON envelope and scalar payload support do not cover every real broker payload format; per-mapping field extraction is future work.


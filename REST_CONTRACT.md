# DT Gateway, REST Contract

This document is the **single contract** between the Digital Twin Gateway and any
AAS-side consumer. The gateway exposes machine data over plain HTTP/JSON.
There is no message broker, no AAS server impersonation, consumers call these
endpoints directly.

---

## Base URL

```
http://<gateway-host>:8085/api/submodel
```

Default port `8085`, set by `server.port` in
`dt-gateway-app/src/main/resources/application.properties` (the engine runs on
8080 and expects the gateway on 8085). Override it with the `SERVER_PORT`
environment variable, with `java -jar dt-gateway-app.jar --server.port=<n>`, or
under Maven with `-Dspring-boot.run.arguments=--server.port=<n>`.

---

## Endpoints

| Method | Path                              | Purpose                                |
|--------|-----------------------------------|----------------------------------------|
| GET    | `/api/submodel`                   | All currently-cached submodel elements |
| GET    | `/api/submodel/{machineId.idShort}` | One element by routing key           |
| PATCH  | `/api/submodel/{machineId.idShort}/$value` | Write a new value to the machine |
| GET    | `/api/submodel/health`            | Liveness + per-machine connectivity    |
| GET    | `/api/submodel/stream`            | SSE live stream (see ENGINE_INTEGRATION.md) |

---

## Data shape, a standard AAS `Property` (IDTA-01001)

Elements are addressed by the **routing key** `machineId.idShort`
(e.g. `molder-1.ClampForce`) in the URL; the body is the standard element:

```json
{
  "modelType": "Property",
  "idShort": "ClampForce",
  "category": "VARIABLE",
  "semanticId": { "type": "ExternalReference",
                  "keys": [{ "type": "GlobalReference", "value": "0173-1#02-AAJ979#001" }] },
  "valueType": "xs:double",
  "value": "2200.0",
  "qualifiers": [
    { "kind": "ConceptQualifier", "type": "Unit",       "valueType": "xs:string",   "value": "kN" },
    { "kind": "ConceptQualifier", "type": "StatusCode", "valueType": "xs:string",   "value": "OK" },
    { "kind": "ConceptQualifier", "type": "ObservedAt", "valueType": "xs:dateTime", "value": "2026-06-12T23:11:31" },
    { "kind": "ConceptQualifier", "type": "Timestamp",  "valueType": "xs:dateTime", "value": "2026-06-12T23:11:26" }
  ]
}
```

- `value` is always a **string** (per the AAS serialization), cast using `valueType` (`xs:double`, `xs:int`, `xs:boolean`, `xs:string`).
- `semanticId` appears only when the config declares one.
- Unit, status, and freshness ride as `qualifiers`: `StatusCode` `OK` / `OUT_OF_RANGE` / `STALE`; `ObservedAt` = last successful gateway read; `Timestamp` = last value change.

---

## GET /api/submodel

Returns all cached elements as a map keyed by the **routing key**.

**Response 200:**
```json
{
  "cnc-line1.SpindleSpeed": { "modelType": "Property", "idShort": "SpindleSpeed", "valueType": "xs:double", "value": "1234.5", ... },
  "molder-1.ClampForce":    { "modelType": "Property", "idShort": "ClampForce",   "valueType": "xs:double", "value": "2200.0", ... }
}
```

---

## GET /api/submodel/{machineId.idShort}

**Response 200**, element exists and a value has been polled.
**Response 204**, `idShort` is configured but no value has been polled yet.
**Response 404**, `idShort` is not configured in the gateway.

---

## PATCH /api/submodel/{machineId.idShort}/$value

Writes a new value to the machine. The request body is the **raw value**
(JSON literal, number, boolean, or string), not a wrapper object. Send the
value in its *translated* form (valueMap label, target unit), the gateway
reverses the translation pipeline before writing.

**Request:**
```http
PATCH /api/submodel/cnc-line1.WriteTargetX/$value
Content-Type: application/json

"G0 X100"
```

**Response 204**, write sent to the machine (verify by observing the value converge).
**Response 403**, gateway is in safe mode (`dt.framework.safe-mode=true`); all writes disabled.
**Response 404**, routing key is not configured.
**Response 502**, translator/adapter failed while writing (unknown valueMap label, type mismatch, machine refused).
**Response 503**, the machine's adapter is disconnected.

---

## GET /api/submodel/health

**Response 200:**
```json
{
  "status": "UP",
  "timestamp": "2026-06-12T10:15:23.123Z",
  "safeMode": false,
  "mappings": 94,
  "storeSize": 94,
  "adapters": {
    "cnc-line1": "CONNECTED",
    "molder-mqtt": "CONNECTED"
  }
}
```

Use this for liveness probes. `adapters` shows **per-machine** connectivity
(keyed by machineId).

---

## Error envelope

All non-2xx responses use this shape:

```json
{
  "status": 404,
  "error": "Not Found",
  "message": "No mapping found for idShort: FooBar",
  "timestamp": "2026-04-30T10:15:23.123Z"
}
```

| HTTP | Meaning                                                            |
|------|--------------------------------------------------------------------|
| 400  | Malformed request                                                  |
| 403  | Write rejected, gateway is in safe mode                           |
| 404  | Routing key not configured in the gateway                          |
| 502  | Downstream adapter/translator failed during the request, including a write the machine rejected (Bad status, e.g. `Bad_NotWritable`; the message names it) |
| 503  | Mapping exists, but the machine's adapter is disconnected          |
| 500  | Unhandled internal error                                           |

---

## Polling guidance

The gateway refreshes its cache every **2000 ms** (configurable via
`dt.framework.poll-interval-ms`). Polling the gateway faster than that yields
no new data, values will simply repeat. Prefer the SSE stream
(`/api/submodel/stream`) over polling: it pushes only meaningful changes.

---

## Out of scope (intentionally)

- **AAS Part 2 API conformance**, the *payloads* are IDTA-01001-conformant Properties, but the URL surface is gateway-native, not the Part 2 API (see STANDARDS_MAPPING.md, gap #6).
- **Authentication**, none in lab deployment (documented limitation).
- **Operations / batch writes**, only single-element reads and writes are supported.

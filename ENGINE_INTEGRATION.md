# Engine Integration Guide, Consuming the DT Gateway

For the developer building the AAS engine / digital-twin application on top of
this gateway. Covers: the exact shape of the data you receive, every REST/SSE
endpoint you can call, and what the gateway expects from you to write values
back to a machine.

Base URL (default): `http://localhost:8085`

---

## 1. The data shape, a standard AAS Property

Every signal the gateway serves is a **standards-conformant AAS `Property`**
(IDTA-01001 / IEC 63278-1 metamodel, JSON serialization). You will never
receive any other element type. Example:

```json
{
  "modelType": "Property",
  "idShort": "ClampForce",
  "category": "VARIABLE",
  "semanticId": {
    "type": "ExternalReference",
    "keys": [{ "type": "GlobalReference", "value": "0173-1#02-AAJ979#001" }]
  },
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

Field rules:

| Field | Rule |
|---|---|
| `value` | **Always a JSON string**, per the AAS value serialization. Cast it yourself using `valueType`. |
| `valueType` | One of `xs:double`, `xs:int`, `xs:boolean`, `xs:string`. |
| `category` | `CONSTANT` = declared never-changing (vendor name, serial). `VARIABLE` = live signal. |
| `semanticId` | Present **only** when the machine's config declares one. Engine should treat it as the authoritative meaning (ECLASS/IEC CDD reference); fall back to `idShort` otherwise. |
| `qualifiers` | May be absent or partial. Match on `type`: |

Qualifier vocabulary (all values are strings):

| Qualifier `type` | Meaning |
|---|---|
| `Unit` | Engineering unit of `value` (e.g. `kN`, `rpm`, `°C`). Omitted when unitless. |
| `StatusCode` | `OK` = trust it. `OUT_OF_RANGE` = value violates the configured plausibility range. `STALE` = **the machine is not currently delivering data** (transport lost, or for MQTT silent beyond its quiet window), *every* non-constant element of that machine shows STALE together, and the value shown is the last known one. A steady value on a live machine is **not** stale. `ERROR` = the protocol reported bad quality. |
| `ObservedAt` | ISO-8601 local, when the gateway last successfully read the signal. |
| `Timestamp` | ISO-8601 local, when the value last actually changed. |

> Note (documented deviation): units ride as a Qualifier rather than a
> ConceptDescription (IEC 61360), and machine identity is **not** inside the
> element, see addressing below. Grouping elements into Submodels/Shells is
> the engine's responsibility; the gateway serves elements.

---

## 2. Addressing, the routing key

Elements are addressed by the **routing key**: `machineId.idShort`
(e.g. `molder-1.ClampForce`). The key appears in URLs and SSE wrappers, never
inside the Property itself. Split on the **first** dot: machineIds may contain
hyphens (`cnc-line1`) but never dots; idShorts match `[a-zA-Z][a-zA-Z0-9_]*`.

---

## 3. Read APIs

| Method | Path | Returns |
|---|---|---|
| GET | `/api/submodel` | All cached elements: a JSON object mapping **routing key -> Property** |
| GET | `/api/submodel/{machineId.idShort}` | One Property |
| GET | `/api/submodel/health` | Gateway liveness + per-machine connection state |
| GET | `/api/submodel/stream` | SSE stream (see §4) |

```bash
curl http://localhost:8085/api/submodel/molder-1.ClampForce
curl http://localhost:8085/api/submodel          # everything, keyed by routing key
curl http://localhost:8085/api/submodel/health
```

Status codes on single-element GET:

| Code | Meaning |
|---|---|
| 200 | Element returned (check the `StatusCode` qualifier before trusting the value) |
| 204 | Element is configured but no value has been polled yet (e.g. right after gateway start), retry shortly |
| 404 | No such routing key configured |

Health response shape:

```json
{
  "status": "UP",
  "safeMode": false,
  "mappings": 94,
  "storeSize": 94,
  "adapters": { "cnc-line1": "CONNECTED", "molder-mqtt": "QUIET", "...": "..." }
}
```

`adapters` gives per-machine state: `CONNECTED` (delivering data), `QUIET`
(transport up but the machine is silent beyond its quiet window, its elements
read `STALE`), `DISCONNECTED` (transport down; writes return 503).

---

## 4. Live updates, SSE stream

`GET /api/submodel/stream` (`Accept: text/event-stream`). **Three** event
types, all with the same payload shape, the Property **wrapped** with its
routing key:

```
event:snapshot
data:{"key":"molder-1.ClampForce","property":{"modelType":"Property","idShort":"ClampForce", ...}}

event:update
data:{"key":"molder-1.ClampForce","property":{ ... }}

event:keyframe
data:{"key":"molder-1.ClampForce","property":{ ... }}
```

- **`snapshot`**, on connect, one per cached element: the full current state
  (staleness applied). Build your model from these.
- **`update`**, a value or status *meaningfully changed* (deadband-filtered;
  an unchanged value polled every 2 s does not spam you). This is the
  low-latency, change-only feed. No `update` events while a machine is steady
  or silent is normal.
- **`keyframe`**, every element re-broadcast periodically (default 10 s,
  `dt.framework.stream.keyframe-interval-ms`) **with staleness applied**. This
  is how the stream reports a machine going `STALE`: when a machine dies the
  `update` feed simply stops, so the keyframe is what carries the `OK->STALE`
  transition, and it also recovers any update missed during a transient drop.

Choose your style: a change-only consumer listens to `update` and ignores
`keyframe`; a fixed-interval-full consumer listens to `keyframe`; a robust
consumer uses both (updates for latency, keyframes for liveness and
drift-correction). The connection has no server-side timeout; reconnect on
error for a fresh snapshot.

Minimal Python consumer:

```python
import json, sseclient, requests   # pip install sseclient-py requests

resp = requests.get("http://localhost:8085/api/submodel/stream", stream=True)
for event in sseclient.SSEClient(resp).events():
    msg = json.loads(event.data)            # {"key": ..., "property": ...}
    key, prop = msg["key"], msg["property"]
    quals = {q["type"]: q["value"] for q in prop.get("qualifiers", [])}
    print(event.event, key, prop["value"], quals.get("Unit", ""), quals.get("StatusCode"))
```

---

## 5. The write loop, what the gateway expects from the engine

### The request

```
PATCH /api/submodel/{machineId.idShort}/$value
Content-Type: application/json
```

The body is the **bare JSON value**, not a Property, not an object:

```bash
# numeric element (valueType xs:double / xs:int)
curl -X PATCH http://localhost:8085/api/submodel/cnc-line1.WriteTargetX/\$value \
     -H "Content-Type: application/json" -d '"G0 X100"'

curl -X PATCH http://localhost:8085/api/submodel/molder-1.ClampForce/\$value \
     -H "Content-Type: application/json" -d '1800.5'
```

### What value to send, always the *translated* (human) representation

Send the value **in the same form you read it**, and the gateway runs the
translation pipeline in reverse before touching the machine:

| Element's read form | What you send | What the gateway does |
|---|---|---|
| valueMap'd enum, you read `"HELD"` | `"HELD"` | Reverse-maps the label to the machine code (`true`) and writes that. Sending a label that is not in the valueMap is **rejected**, the gateway will not guess. |
| Unit-converted, you read metres | the value **in metres** | Converts back to the machine's unit (mm) before writing |
| Typed, `valueType: xs:double` | a number (or numeric string) | Casts to the protocol's native type; an unparseable value is rejected |

Never send raw machine codes or source units, the gateway's contract is
symmetric: *what you read is what you write.*

### Response codes

| Code | Meaning | Engine behaviour |
|---|---|---|
| 204 | Write was **sent** to the machine | See verification below |
| 403 | Gateway is in **safe mode** (`dt.framework.safe-mode=true`), all writes disabled | Surface to the user; do not retry |
| 404 | Unknown routing key | Config/engine mismatch |
| 503 | Machine's adapter disconnected | Retry later; watch `/health` |
| 502 | Translation/write failed (bad valueMap label, unparseable value, machine refused) | Fix the value; message body explains |
| 500 | Unhandled internal error | Report it |

### Verification, 204 is "sent", not "applied"

The gateway does not guarantee the machine *accepted* the value (for MQTT
machines, writes are fire-and-forget publishes by design). The engine closes
the loop by **observing**: after a write, watch the SSE stream (or re-read the
element) and confirm the value converges to what you wrote within a few poll
cycles (default poll interval: 2 s). That observation, not the 204, is your
write acknowledgment.

```
engine                gateway                 machine
  │ PATCH $value "1800.5" │                       │
  │──────────────────────▶│ reverse-translate     │
  │            204        │──────── write ───────▶│
  │◀──────────────────────│                       │
  │                       │◀─── next poll reads ──│
  │◀═ SSE update: value=1800.5 (convergence = ack)│
```

---

## 6. Operational notes

- **Poll cadence**: values refresh every 2 s (`dt.framework.poll-interval-ms`).
  Freshness: an element whose machine has stopped delivering data
 is served with `StatusCode: STALE`, staleness is judged per machine
  (is it delivering data?), not per value, and `CONSTANT` elements are exempt.
- **Machines appear/disappear only on gateway restart** (config is read at
  startup). After a restart, expect 204s for a few seconds, then full data.
- **No authentication** on any endpoint (lab scope, documented limitation).
- The gateway's API surface is native, **not** the AAS Part 2 API
  (IDTA-01002): standard *documents*, custom *paths*. If the engine is built
  against Part 2 paths, it needs a small adapter for the table in §3, or see
  `STANDARDS_MAPPING.md`, gap #6.

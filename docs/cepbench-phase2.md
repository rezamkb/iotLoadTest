# cepbench — Phase 2: edge workload and sentinel

Publishes device reports through an MQTT edge and watches whether Drools is still firing.

Phase 1 creates the environment; this drives it. Read
[cepbench-phase1.md](cepbench-phase1.md) first for `provision`, `activate` and the manifest.

## Why through an edge

An earlier draft of this phase published straight onto the CEP input queue
(`iot/cep/in/device/reported_0`). That isolates Drools nicely, but it skips device-hub and twin
entirely, so it measures a path production never takes. Going through the edge exercises the real
ingress: MQTT → Dirana → gateway → twin → CEP.

The cost is that a bottleneck anywhere upstream shows up as a low event rate that has nothing to do
with CEP. `publishFailures` and `achievedEventsPerSecond` in the run report are there to make that
visible rather than letting it silently depress the numbers.

## The edge belongs to you, not to the run

`cepbench` never creates or deletes an edge. It attaches its provisioned devices to an edge you
already own, and detaches them again. Each attachment is journalled as `EDGE_ATTACHMENT`, so
`cleanup` detaches exactly what it attached — before deleting the devices, so your edge is not left
holding attachments to devices that no longer exist.

`POST /edges` does return `clientId` and `alternativeClientId`, and `EdgeServiceImpl.createEdge`
registers both with Dirana and SSO automatically, so creating edges per run is possible. It is not
what this does.

## clientId and alternativeClientId are not interchangeable

| Field | Direction | Used for |
|---|---|---|
| `clientId` | uplink | the MQTT client identifier when publishing |
| `alternativeClientId` | downlink | subscribing to `dvcout/<edgeId>/<clientId>/edge/twin/#` |

`EdgeMqttPublisher` connects with `clientId`. Connecting with the other one produces a connection
that looks perfectly healthy and delivers nothing, so it is worth checking first when a run reports
traffic sent and no alarms at all.

No username or password is normally needed: the broker authenticates on the registered client id.
Both fields exist in the config for brokers that do want them, inline or from an environment
variable.

## The payload

Fixed by `gateway/src/main/resources/json/IOT-in-edge-twin-report.json`, which the gateway validates
against before the message goes anywhere. `additionalProperties: false` at the root means only two
keys are legal:

```json
{
  "$requestId": "cepbench-42",
  "deviceReport": {
    "deviceId": "gqcrarftoxs",
    "deviceTwinDocument": { "attributes": { "reported": { "temp": 41 } } }
  }
}
```

`deviceReport` also accepts an **array**, which is what `reportsPerPublish` uses. `$requestId` is
capped at 128 characters and is truncated rather than rejected.

A payload that drifts from this schema is dropped upstream of CEP, and the run would then measure
nothing while looking like it worked. `EdgeReportPayloadFactoryTest` pins the shape for that reason.

## Configuration

Two new optional sections. Commands that need them say so; `plan`, `provision`, `activate` and
`cleanup` keep working without them.

```json
"edge": {
  "edgeId": "iwj9ecpqwpg",
  "clientId": "3DWBGGJMJWBGBFWJ9LKWWQV",
  "alternativeClientId": "96XJJD96DNJFV8XZBNLZVID",
  "brokerUrl": "tcp://<broker>:1883",
  "publishTopic": "dvcasy/edge/twin/report",
  "qos": 0,
  "maxInflight": 10000
},
"workload": {
  "eventsPerSecond": 50,
  "durationSeconds": 300,
  "reportsPerPublish": 1,
  "matchingFraction": 0.0,
  "sentinelIntervalSeconds": 30,
  "sentinelTimeoutSeconds": 15,
  "progressIntervalSeconds": 10,
  "stopOnSentinelFailure": true
}
```

`publishTopic` is environment specific. The value above comes from `EdgeConfig` in the existing
MQTT load test, which was validated against a **dev** broker; the develop profiles show suffixed
variants such as `dvcasy/twin/update/rep-dev`, so confirm it against the environment you are
targeting before a long run.

`eventsPerSecond` counts device reports, not MQTT publishes. With `reportsPerPublish: 1` they are the
same number, which is what keeps a rate figure comparable between runs. Raise it only when MQTT, not
CEP, is the limit.

## Commands

```powershell
./gradlew.bat cepbench -Pcommand=attach -Pconfig=src/main/resources/cepbench.local.json
./gradlew.bat cepbench -Pcommand=run    -Pconfig=src/main/resources/cepbench.local.json
./gradlew.bat cepbench -Pcommand=detach -Pconfig=src/main/resources/cepbench.local.json
```

Full sequence for one experiment:

```
plan → provision → attach → activate → run → deactivate → detach → cleanup → export
```

`attach` is resumable and idempotent: a device already recorded as attached is skipped, and a device
the platform reports as already attached is journalled anyway, because this run is then responsible
for detaching it. `cleanup` detaches on its own, so the explicit `detach` only matters if you want to
keep the environment and stop the traffic.

## The sentinel

Publish rate proves the producer is alive. It says nothing about whether Drools is evaluating, and
the production failure looks identical to a healthy run from outside: events accepted, inserted into
the session, never fired.

So one rule is reserved as a sentinel. Every `sentinelIntervalSeconds`, the probe reads the rule's
alarm count, publishes a reading that must match it, and waits up to `sentinelTimeoutSeconds` for
that count to advance.

Three details that matter:

- **The sentinel rule is excluded from the background load**, and so are all of its devices.
  Otherwise background traffic could fire it by coincidence and the probe would report health it
  never measured.
- **A single-select rule is preferred.** A windowing rule needs several events before it can fire,
  which would make the measured latency meaningless.
- **The baseline is re-read immediately before each publish**, never cached between probes. An alarm
  from a previous probe that landed late would otherwise be counted as this one succeeding.

A probe that could not run at all — the API was unreachable, the MQTT connection was down — is
reported as an error, not as a failure to fire. Only the second is evidence.

## matchingFraction, and why it defaults to 0

A stateless fact is retracted only when its rule fires. Non-matching events are therefore retained in
the entry point indefinitely, and with a device shared across many rules one incoming event becomes
one retained fact per rule. That accumulation is the mechanism behind the failure being reproduced,
so pure non-matching traffic is the interesting workload, not a degenerate one.

Raise `matchingFraction` when you want firing throughput instead of retention pressure. Do not vary
it in the same comparison as `eventsPerSecond` or the rule count.

## When the sentinel fails

The run stops (unless `stopOnSentinelFailure` is false) and reports
`firstFiringFailureAfterSeconds`. Before restarting the CEP node — a restart destroys the evidence —
capture:

1. `GET /diagnostics/drools` from the CEP node. `verdict` alone usually names the cause. The endpoint
   lives in the `iot-platform` repo, at
   `cep/src/main/java/ir/fanap/fanthings/cep/controller/DroolsDiagnosticsController.java`.
2. `GET /diagnostics/drools?facts=true` for per-entry-point fact counts. This one takes the working
   memory lock, so it can block behind a wedged firing thread — which is itself informative.
3. A thread dump, especially the `fireUntilHalt` thread.
4. Heap usage and GC statistics.

The reading to make first is `firingLoop.state`. `FAILED` with a `failureType` means an exception
escaped `fireUntilHalt()` and the engine is dead until restart. `RUNNING` with `matchCreatedStateless`
climbing and `matchFiredEnteredStateless` flat means something else.

## Known gaps

- **Run results are not persisted.** `run` reports to stdout only, so an hour-long run's sentinel
  timeline lives in the console. Worth adding before long unattended runs.
- **No downlink subscriber.** `alternativeClientId` is carried in the config but nothing subscribes
  to `dvcout/...`, so command/response round trips are not exercised.
- **One edge, one connection.** Throughput is bounded by a single MQTT connection. Raise
  `reportsPerPublish`, or spread devices across several edges, if MQTT saturates before CEP does.
- **Nothing has been compiled or run.** Every wire detail here was read out of the platform source
  rather than observed on a live system.

# cepbench — Phase 1: environment control plane

Creates, activates and removes the platform resources one CEP benchmark run needs, against the
sandbox REST API. No event generation yet; that is Phase 2.

## Commands

```powershell
./gradlew.bat cepbench -Pcommand=plan       -Pconfig=src/main/resources/cepbench.sandbox.example.json
./gradlew.bat cepbench -Pcommand=provision  -Pconfig=src/main/resources/cepbench.sandbox.example.json
./gradlew.bat cepbench -Pcommand=status     -Pconfig=src/main/resources/cepbench.sandbox.example.json
./gradlew.bat cepbench -Pcommand=activate   -Pconfig=src/main/resources/cepbench.sandbox.example.json
./gradlew.bat cepbench -Pcommand=deactivate -Pconfig=src/main/resources/cepbench.sandbox.example.json
./gradlew.bat cepbench -Pcommand=cleanup    -Pconfig=src/main/resources/cepbench.sandbox.example.json
```

`plan` makes no network calls. Run it first: it prints the device count, the rule mix and one
rendered example of each rule shape, which is the last cheap chance to notice that the config is not
what you meant.

## Secrets and the confirmation guard

```powershell
$env:CEPBENCH_API_TOKEN = '<sandbox bearer token, without the "Bearer " prefix>'
$env:CEPBENCH_CONFIRM   = 'drools-baseline-001@api.sandpod.ir'
```

The token is never read from the config file; the file only names the environment variable holding
it. Every command except `plan` and `status` additionally requires `CEPBENCH_CONFIRM` to equal
`<runId>@<api host>` exactly. Provisioning creates hundreds of resources on a shared sandbox and
cleanup deletes them, so the operator has to name both the run and the target host.

## What one run owns

For `runId = drools-baseline-001`, every resource is named `cepbench-drools-baseline-001-*`:

- one device type with `temp` (number) and `occ` (boolean),
- one `Warn` alarm type,
- the planned devices,
- the planned rules, created **inactive**; `activate` is a separate step.

Every device and rule carries `tags: {"code": "<locationCode>"}`. That tag is what
`LocationResolverService` maps to a location and `CepShardUtil` hashes to a CEP node, so devices and
rules must share it or a rule can compile on one node while its events go to another. Sandbox runs
`cetNodeCount = 1`, so everything lands on node 0 today, but the tag keeps the setup correct if that
changes.

## The manifest

`.cepbench/<runId>.jsonl`, append-only, one JSON object per line, fsynced before the next API call.

- `cleanup` deletes **only** ids the journal records, in reverse dependency order: rules, devices,
  alarm type, device type.
- Nothing is ever looked up by name. A resource that happens to match this run's naming convention
  but is absent from the journal belongs to somebody else and is never touched.
- A delete appends a tombstone rather than rewriting the file, so an interrupted cleanup is safe to
  re-run.
- Opening a journal that was written against a different API host is refused: ids from one
  environment mean nothing in another.

`provision` is resumable. The plan is deterministic, so a run interrupted half way through creates
only what the journal does not already record.

## maxRulesPerDevice

The one knob that changes what the benchmark measures.

| Value | Effect | Use for |
|---|---|---|
| `1` | Each rule gets its own devices. One event reaches exactly one entry point. | Rule-count and rate scaling, where the number must be attributable to one variable. |
| `> 1` | Up to N rules share a device. One event is inserted into N entry points and becomes N retained facts. | Growing working memory, which is how the retention failure is reproduced. |

Never vary it in the same comparison as rule count or event rate.

## Notes and known rough edges

- **POST is never retried.** A create that times out may have succeeded server-side; retrying risks
  an orphan no manifest records. GET, PUT and DELETE are retried, since they are idempotent.
- **Activation is asynchronous.** A success status means the request was accepted, not that the rule
  is compiled into the engine, so `activate` polls `GET /rules/{id}` until `activated` is true.
  Never start a load run on the strength of the activate call alone.
- **`alarmTypeCode` may collide.** Alarm type *names* are scoped by `runId`, but the code is not. Use
  a distinct code per run, or expect a 409 on the second run.
- **`status` queries every rule.** With 1000 rules at concurrency 4 it takes a few seconds.
- Compiled against the endpoints in `api/.../RulesController` and friends: `PUT /rules/{id}/activated`
  to activate, `DELETE /rules/{id}/activated` to deactivate.

## Next

Phase 2 is the event generator: a direct JMS publisher onto `iot/cep/in/device/reported_0` with the
`ReportedFact` payload shape, plus the sentinel that distinguishes "the producer is still sending"
from "Drools is still firing".

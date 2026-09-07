# cepbench — Phase 1: environment control plane

Creates, activates and removes the platform resources one CEP benchmark run needs, against the
sandbox REST API. Event generation is [Phase 2](cepbench-phase2.md), which adds the `attach`,
`detach` and `run` commands on top of everything here.

## Commands

```powershell
./gradlew.bat cepbench -Pcommand=plan       -Pconfig=src/main/resources/cepbench.sandbox.example.json
./gradlew.bat cepbench -Pcommand=provision  -Pconfig=src/main/resources/cepbench.sandbox.example.json
./gradlew.bat cepbench -Pcommand=status     -Pconfig=src/main/resources/cepbench.sandbox.example.json
./gradlew.bat cepbench -Pcommand=activate   -Pconfig=src/main/resources/cepbench.sandbox.example.json
./gradlew.bat cepbench -Pcommand=deactivate -Pconfig=src/main/resources/cepbench.sandbox.example.json
./gradlew.bat cepbench -Pcommand=cleanup    -Pconfig=src/main/resources/cepbench.sandbox.example.json
./gradlew.bat cepbench -Pcommand=export     -Pconfig=src/main/resources/cepbench.sandbox.example.json
```

`plan` makes no network calls. Run it first: it prints the device count, the rule mix and one
rendered example of each rule shape, which is the last cheap chance to notice that the config is not
what you meant.

`export` makes no network calls either, and neither needs the API token to be set. Both still
validate the rest of the config.

## Secrets

There is no arming or confirmation step. `provision`, `activate`, `deactivate` and `cleanup` act on
whatever `apiBaseUrl` points at, as soon as they are run. `plan` prints the target and the resource
counts without touching anything, so run it after any config edit and before any of those four.

### Where the token lives

Two options, and the config picks whichever is present:

| Field | Meaning |
|---|---|
| `tokenEnvironmentVariable` | Name of an environment variable holding the token. Keeps the config committable. |
| `token` | The raw bearer token, inline. Convenient; the file must not be committed. |

If both appear, `token` wins. Either way the value is the raw token — the client adds `Bearer `, and
a value that already carries the prefix is rejected.

`*.local.json` is in `.gitignore`, so `cepbench.local.json` is the place for an inline token.
`src/main/resources/cepbench.local.json` ships as a ready-to-edit starter with a small scenario mix.
`cepbench.sandbox.example.json` stays on the environment-variable form and stays committable.

The one thing to know about the inline form: a token in a file is a token you can leak by sharing
the file, and `.gitignore` only protects against `git add`, not against copying the file into a
ticket or a chat. For a shared sandbox token that is usually an acceptable trade; for anything
tied to a real account it is not.

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

A rule's record also carries the devices it selects on:

```json
{"op":"CREATED","kind":"RULE","key":"r-multi-0001","id":"ypmbccdeeqr","name":"cepbench-demo-001-r-multi-0001","scenario":"MULTI_SELECT_TWO_DEVICE","deviceIds":["faoksqg88ao","2dsdyq5p8p6"],"at":"..."}
```

That mapping is written at creation time rather than recomputed, and the distinction matters. A
rule's `when` clause is fixed on the platform the moment it is created, but the planner reallocates
devices to rules whenever `maxRulesPerDevice` or the scenario counts change — exactly the knobs the
capacity and retention phases vary. Recomputing the mapping after such an edit would report a
confident but wrong answer with no error. Journals written before this field existed report no
mapping rather than guessing one.

## CSV export

`export` folds the journal and writes two files next to it:

- `<runId>-rules.csv` — `ruleKey, ruleId, ruleName, scenario, stateful, deviceCount, deviceIds, when`
- `<runId>-devices.csv` — `deviceKey, deviceId, deviceName, ruleCount, ruleKeys, ruleIds`

Multi-valued columns are `;`-separated so they need no quoting gymnastics. The `when` column is
re-rendered from the recorded scenario and device ids, so it shows what the platform was actually
asked to compile.

Activation state is deliberately **not** in the CSV. It is live platform state that changes without
the journal knowing; `status` reports it. A stale copy in a file invites someone to trust it.

The export is the join Phase 2 needs: the generator has to know which device feeds which rule to
build a payload that matches, and the sentinel has to pick a rule and address its device.

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

Phase 2 is the event generator and the sentinel: see [cepbench-phase2.md](cepbench-phase2.md).

It publishes through an MQTT edge rather than straight onto the CEP input queue, as an earlier draft
of this document proposed. Direct JMS isolates Drools more cleanly but skips device-hub and twin, so
it measures a path production never takes.

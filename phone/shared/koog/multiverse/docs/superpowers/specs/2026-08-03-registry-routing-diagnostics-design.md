# Registry & Routing-Policy Diagnostics — Design

Status: Approved
Date: 2026-08-03
Scope: `koog/multiverse/master-agent` (shared JVM core) + `koog/multiverse/master-agent-android`

## Problem

While functionally validating `master-agent-android` on-device, there was no way to inspect (a) which
devices are currently in the capability registry and their key attributes, or (b) how the routing
policy would actually rank/choose among them right now. Both are needed to confirm the routing logic
is behaving correctly against a live registry snapshot, not just to eyeball `registry.json`.

## Goals

1. A "View devices" menu showing, per registered device: device name (`deviceId`), IP address, port,
   multipath support, LLM mode (role: local/remote), and served LLM models.
2. A "View routing policy" menu showing the current routing priority order — every tier of the
   `PreferRemotePolicy` + `RoutingEngine` fallback chain, fully ranked, evaluated against the live
   registry snapshot, with the actual winning decision highlighted.
3. Both must reflect the **real** routing/registry code paths — not a separate reimplementation that
   could drift out of sync with actual routing behavior.
4. Update `master-agent-android/README.md` to document the new menus.

## Non-goals

- No new Activities/navigation graph — this is diagnostic tooling, not a new app surface.
- No changes to `/v1/compute`'s response contract (grillme_version2 Sec 3.7 is untouched).
- No persistence of policy/registry views; always a fresh live snapshot on each open.

## Design

### 1. Shared-core changes (`master-agent` module)

**`RoutingPolicy` interface** gains two new methods (implemented by `PreferRemotePolicy`) that expose
full ranked candidate lists instead of only the winner:

```kotlin
interface RoutingPolicy {
    val requestedModel: String
    fun chooseRemote(candidates: List<CapabilityEntry>): CapabilityEntry?
    fun chooseLocal(candidates: List<CapabilityEntry>): CapabilityEntry?
    fun rankRemote(candidates: List<CapabilityEntry>): List<CapabilityEntry>
    fun rankLocal(candidates: List<CapabilityEntry>): List<CapabilityEntry>
}
```

`PreferRemotePolicy.chooseRemote`/`chooseLocal` are refactored to `rankRemote(...).firstOrNull()` /
`rankLocal(...).firstOrNull()` — the existing filter+`minByOrNull(gpuLoad)` logic moves into the `rank*`
methods unchanged, sorted ascending by `gpuLoad`. This guarantees `choose*` and `rank*` can never
disagree on the top pick, because `choose*` is now defined in terms of `rank*`.

`RoutingEngine` gains a small helper extracted from its inline last-resort filter:

```kotlin
internal fun anyReachableServing(candidates: List<CapabilityEntry>, model: String): List<CapabilityEntry> =
    candidates.filter { it.status.reachable && it.servesModel(model) }
```

`select()` is updated to call this helper instead of its inline `firstOrNull` filter, so the "tier 3"
fallback in the diagnostics view and the engine's actual fallback behavior are the same code.

**New `RoutingDiagnostics` object** (new file `routing/RoutingDiagnostics.kt`) composes a full tiered
report using only these existing pieces — no new selection logic:

```kotlin
data class RankedCandidate(val deviceId: String, val gpuLoad: Double, val selected: Boolean)
data class PolicyTier(val tier: Int, val name: String, val candidates: List<RankedCandidate>)
data class RoutingDiagnosticsReport(
    val requestedModel: String,
    val wifiUp: Boolean,
    val g5Up: Boolean,
    val tiers: List<PolicyTier>,
    val decisionTarget: String?,   // "REMOTE" | "LOCAL" | null on failure
    val decisionDeviceId: String?,
    val useMultipath: Boolean,
    val error: String?,            // NoTargetAvailableException message, else null
)

object RoutingDiagnostics {
    fun evaluate(
        snapshot: List<CapabilityEntry>,
        network: NetworkStatus,
        policy: RoutingPolicy,
        engine: RoutingEngine,
    ): RoutingDiagnosticsReport
}
```

It builds tier 1 (`rankRemote`), tier 2 (`rankLocal`), tier 3 (`anyReachableServing`), marks `selected`
by cross-referencing the deviceId chosen by an actual `engine.select(...)` call (wrapped in
`runCatching` to populate `error` on `NoTargetAvailableException`), and reports the real decision's
`useMultipath`. All three tiers are always computed and returned in full, even ones the engine
short-circuited past — this view exists specifically to show what *would* win at every tier.

### 2. New HTTP endpoints (`api/ComputeRoutes.kt`)

Two new `@Serializable` DTOs in `model/ComputeResponse.kt` (or a new `model/DiagnosticsBody.kt`):

```kotlin
@Serializable
data class DeviceInfo(
    val deviceId: String,
    val role: String,        // "local_agent" | "remote_agent" — the LLM-mode field
    val host: String,
    val port: Int,
    val multipath: String,   // "supported" | "unsupported" | "unknown"
    val models: List<String>,
    val reachable: Boolean,
    val gpuLoad: Double,
)
@Serializable data class DevicesBody(val devices: List<DeviceInfo>)

@Serializable data class RankedCandidateBody(val deviceId: String, val gpuLoad: Double, val selected: Boolean)
@Serializable data class PolicyTierBody(val tier: Int, val name: String, val candidates: List<RankedCandidateBody>)
@Serializable
data class RoutingPolicyBody(
    val requestedModel: String,
    val wifiUp: Boolean,
    val g5Up: Boolean,
    val tiers: List<PolicyTierBody>,
    val decisionTarget: String?,
    val decisionDeviceId: String?,
    val useMultipath: Boolean,
    val error: String?,
)
```

Routes added to `computeRoutes(...)`:

- `GET /v1/registry/devices` → maps `agent.registrySnapshot()` (new accessor alongside the existing
  `registryDeviceCount()`) to `DevicesBody`.
- `GET /v1/routing/policy` → calls `agent.routingDiagnostics()` (new accessor that wires
  `RoutingDiagnostics.evaluate` with the agent's own registry snapshot, network, default-model policy,
  and routing engine) and maps to `RoutingPolicyBody`.

`MasterAgent` gains these two read-only accessors; no change to `handle()`'s request flow.

### 3. Android UI (`MainActivity.kt`)

Two new buttons, "View devices" and "View routing policy", added below the existing three. Each:

1. Runs a background `HttpURLConnection` GET (same pattern as `checkHealth()`) against
   `http://127.0.0.1:8080/v1/registry/devices` or `/v1/routing/policy`.
2. Parses the JSON response (`kotlinx.serialization`, already a dependency) into the DTOs above
   (duplicated as minimal Android-side `@Serializable` copies, matching how the phone-app design in
   grillme_version2 treats the API as the contract, not shared source).
3. Formats into human-readable multi-line text and shows it in a scrollable `AlertDialog`
   (`ScrollView` + `TextView`, monospace), e.g.:

```
aws-remote-01  [remote]
  10.0.3.2:8443
  multipath: supported
  models: llama3.2-vision, whisper
  gpuLoad: 0.34   reachable: true

xelite-local  [local]
  127.0.0.1:11434
  multipath: unsupported
  models: llama3.2-vision
  gpuLoad: 0.10   reachable: true
```

```
Requested model: llama3.2-vision   wifi:up  5g:up

Tier 1 — remote_preferred
  -> aws-remote-01  gpuLoad=0.34   [SELECTED]

Tier 2 — local_fallback
     xelite-local   gpuLoad=0.10

Tier 3 — any_reachable_fallback
     aws-remote-01  gpuLoad=0.34

Decision: REMOTE -> aws-remote-01   multipath=true
```

On network error, the dialog shows `"error: <message>"` (same failure style as `checkHealth()`).

### 4. Documentation

`master-agent-android/README.md` "Structure"/"Install / run" sections updated to mention the two new
buttons and example `curl` invocations for the two new endpoints, matching the existing `/v1/compute`
example.

## Testing

- `jvmTest`: new `RoutingPolicyTest`-style cases (or additions to `RoutingEngineTest.kt`) asserting
  `rankRemote`/`rankLocal` return full lists in ascending-`gpuLoad` order, and that `chooseRemote` /
  `chooseLocal` equal `rank*().firstOrNull()` for representative fixtures.
- New `RoutingDiagnosticsTest.kt`: verify tier contents and `selected` flags match an independent
  `engine.select(...)` call for a few registry fixtures (multi-remote tie-break, local-only fallback,
  no-target error path — reusing fixtures from `RoutingEngineTest.kt`).
- Extend `ComputeApiTest.kt` with `GET /v1/registry/devices` and `GET /v1/routing/policy` endpoint
  tests against the same `agentWithMock()` fixture used by existing tests, asserting response shape and
  that the known 2-device `registry.json` fixture produces the expected tiers/decision.

## Manual verification (Android)

Re-run the same on-device flow used for `/v1/compute` validation: build APK → boot AVD → install →
start agent → `adb forward tcp:8080 tcp:8080` → `curl /v1/registry/devices` and `curl
/v1/routing/policy` to confirm the raw JSON, then tap the two new buttons in the app and confirm the
dialog text matches.

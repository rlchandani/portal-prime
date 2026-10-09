# Fleet management

`FleetAgentService` — an optional always-on WiFi service for managing a Portal over the network,
without reaching for a USB cable each time.

## What it's for

Run a few Portals around the house and you don't want to plug each one in to make a change. The
fleet agent lets a laptop tool talk to a Portal over WiFi to:

- **deploy and update apps**
- **push config**
- **browse files**
- **read logcat**

## Why an in-app service (not adb-over-WiFi)

adb-over-WiFi can't auto-survive a reboot on these non-root Android 9/10 Portals — the TCP port
is a root-only system property (see [Hardware limitations](../limitations.md)). An app foreground
service comes straight back: `ensureRunning` is called from app start and the boot receiver, the
same hooks that re-assert the screensaver, so the agent is reachable again after a power-cycle
with **no USB and no root**.

## Security

The agent exposes an HTTP API (`FleetHttpServer` / `FleetRoutes`). Every request must carry
`Authorization: Bearer <token>` — a per-device token from `FleetConfig`; anything else gets a
`401` before any work happens. The routes are a pure **consumer** of existing app subsystems
(catalog, installer, settings); they add no install or catalog logic of their own.

!!! warning "The per-device token is a secret"
    Provisioning records each device (name, IP, agent token) to a host-side inventory under
    `provisioning/fleet/`, which is **git-ignored** — never commit it (the repo is public).

## Enabling it

Off by default — an un-provisioned device never opens a port. Enable it per device:

```bash
./provision.sh --fleet        # provision.ps1 -Fleet on Windows
```

You're prompted for a friendly name (e.g. "Living Room Left") unless you preset `FLEET_NAME`.
After a reboot the agent comes back on its own — nothing to re-arm. The default agent port is
`8723`.


## Timed rendering diagnostics

Developer mode provides an explicit, temporary `/perf` capture for Immortal's Activity
and owned Dream windows. The normal app runs without a frame collector thread or frame
listeners. No capture is persisted or resumed after an app restart.

From `provisioning/`, with an existing registered device:

```bash
./fleetctl dev on --device Kitchen
./fleetctl raw POST /perf '{"action":"start","durationSec":60,"phase":"slideshow"}' --device Kitchen
./fleetctl raw GET /perf --device Kitchen
./fleetctl raw POST /perf '{"action":"stop"}' --device Kitchen
```

`durationSec` must be an integer from 10 to 900 and defaults to 60. `phase` labels
an observation; it does not navigate or filter windows. Supported labels are `all`,
`home`, `apps`, `tools`, `settings`, `slideshow`, `launch` and `idle`. Starting replaces
the previous session; stopping or expiry retains its final in-memory results. Only
start requires developer mode; authenticated reads and stop remain available.

Results include observed frame counts, callback drops, first-draw and steady-frame
budget exceedances, 0.25 ms histogram percentiles, timing components and memory sampled
on explicit reads. Percentiles in the overflow bucket return a lower bound of 500 ms
rather than a fabricated exact duration. Refresh budgets use the window's display.
At most 24 live windows and 128 host histograms are admitted; rejected counts are visible.

Expiry stops accepting frames at the monotonic deadline, removes observers/listeners
and quits the collector asynchronously. A screen-wake/lifecycle check handles delayed
main-thread timers after sleep. A `202 completion_unconfirmed` response means a command
began but its completion was not confirmed within the response deadline: inspect GET
`/perf` before retrying. `503 capture_command_not_applied` means it did not begin or failed.

These are **host Window timings**, not compositor-delivered FPS. They do not measure
Chrome's frames, arbitrary website readiness, Dialog windows, system surfaces, or all
WebView/compositor work. First draws are separated because initialization often exceeds
a steady frame budget. Read requests, an active collector and development builds add
measurement overhead; use comparable capture conditions when assessing improvements.

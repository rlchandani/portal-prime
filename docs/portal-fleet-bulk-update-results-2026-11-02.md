# Fleet bulk-update results — Portal 1.88

Exact optimized **1.88/code82** is installed in Family and Living Area.
Both complete installed APK readbacks match **25,861,122 bytes** and SHA-256
`2b23a0f807ba2be739bf547a0ebd97fee573bf63f66f97b388832b68b7f45b9e`.
The accepted signing certificate is unchanged. Source: `23eb995d`.

## Problem and behavior

The original update route always returned `ok:true`, even when an installer
reported failure. Its discovery waiter also ignored whether completion timed out.
Bulk responses now retain every selected package's ordered result and requested
count, add installed/failed counts, and succeed only when all report `installed`.
Selected packages are still attempted once; a failed entry does not hide later
results or cause an automatic retry.

Discovery and selection use one catalog snapshot. Malformed bodies, missing
selectors and unready catalogs are rejected before scanning. A timeout returns
`503 update_check_unconfirmed` before any install. The completed Store callback's
existing provider semantics are retained; independent provider reachability is
not proven by this change.

The host CLI additionally checks count/array consistency, distinct packages and
terminal results, protecting callers of older agents that still claim top-level
success for failed batches. Dry runs require a valid discovery array. Existing
opaque raw/file/text behavior is unchanged.

## Evidence

| Gate | Observed result |
|---|---|
| Original extracted Kotlin behavior | 10 focused tests, 8 failures, zero errors; includes new counter expectations |
| Original CLI | 5 focused methods, 28 failure entries, zero fixture errors |
| Final full JVM | **3,991 tests /417 suites**, zero failures/errors/skips |
| Builds | Debug and optimized/R8/vital lint passed; **146 seconds** |
| Compiled CLI | **28 HTTP methods in1.704s**, plus **23 Rust tests**, zero failures |
| Independent review | No scoped blocker in route/helpers or CLI legacy validation |
| Family Android10 helper control | **9/9** generated-result cases against actual final-source **debug APK**; invalid-method negative control exits2 |
| Family installed optimized API | **9/9** malformed/admission/method/dry-run/zero-target/actual CLI checks |
| Device preservation | Complete profile/layout/sources/screensaver configuration, dev mode and catalog app versions unchanged in both rooms; Family font/services unchanged |
| Delivery | Both installed version and complete artifact hash matched; normal Family photo frame visually reviewed |

One malformed-request sample improved from **1,898.012 ms** with the original
misclassified response to **14.194 ms** with `400 bad_json`. The final admission
samples were7.793–14.194ms. These are scoped completed-request observations, not
general network, UI, FPS or provider-speed guarantees.

R8 inlined both helpers and removed the companion. The resolver rejected direct
optimized-helper invocation; no keeps, test hooks, deoptimization or helper
fallback were added. The nine helper cases therefore establish **debug APK**
Android behavior only. Separate served-API checks exercised the installed
optimized APK, including its real zero-target response with the new counts.

## Boundaries and cleanup

No owner catalog app was installed by the tests. The non-catalog sentinel
selected zero targets; it is not a successful real-package update. Actual failed
or successful selected-app batches, real discovery timeout/cancellation,
provider-error semantics and whole-product native acceptance remain unverified.
The existing no-op behavior for packages absent from discovery remains.
There is no rollback or independent APK-identity guarantee for bulk results.

The host CLI is installed locally with an exact verified binary hash. Existing
CI already runs the affected JVM/compiled-HTTP gates; hosted CI was not observed.
Source and delivery state are committed locally, with no GitHub authentication,
publication, Home Assistant change, instrumentation, accessibility reconnect or
Android default Home assignment.

Owned device scratch, private registry and generated Python cache were removed;
workers are closed. Original private connections, rollback APK and preexisting
script cache were preserved. Owner screenshots/configuration remain private and
are excluded from public evidence. Living received final delivery/readback only.

Original observation clock: **23:38 UTC 2 November–00:08 UTC 3 November 2026**,
including cleanup; no reset.
[Sanitized structured evidence](../mocks/portal-widget-gallery-20261102/evidence/fleet-bulk-update-verification.json).

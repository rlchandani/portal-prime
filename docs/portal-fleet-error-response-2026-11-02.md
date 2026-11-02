# Fleet error response delivery — 2026-11-02

Source commit `d912e184`, branch `codex/fleet-error-response-20261102`.
This independent issue started at **2026-11-02T13:27:41.812889+00:00** and completed its
implementation, device acceptance and cleanup at **2026-11-02T13:43:45.141505+00:00**,
**16.06 minutes** into the original30-minute window.
No previous Weather, installer or performance slot was reopened.

## Actual defect and correction

On Family1.74, an unauthenticated POST with a declared128-byte body and zero body
bytes returned no response during a3,002ms observation. Closing only the client's
write side then produced401 in213ms. The handler had rejected the request, but
server cleanup waited on the unsent body before writing that rejection.

For handler responses with status>=400, the server now writes and flushes the
reply, half-closes its write side, then drains unread input. Successful responses
retain their original drain-before-reply behavior. Authentication, declared-body
validation, upload accounting and response/socket ownership are unchanged.

This follows the relevant staged-close guidance in
[RFC9112§9.5–9.6](https://www.rfc-editor.org/rfc/rfc9112.html#section-9.6).
The existing30-second socket timeout and four-worker pool are unchanged. A peer
that ignores the final response may still occupy a worker during drainage; this
is not a pool-exhaustion correction.

## Local gates

Four actual `serve()` fixture assertions failed before the correction and now
pass:401,413,500 and unknown-length streamed403 completion/resource release.
The initial third fixture accidentally reached unmocked Android logging; it was
corrected before collecting the authoritative four-assertion red receipt. The
source was unchanged during both red runs.

Final **3,849 JVM tests/404 suites** pass with zero failures/errors/skips.
Both signed debug and optimized APKs, R8, vital lint, version sync and signer
checks passed; the versioned combined gate took181s. A final full JVM run includes
the renamed existing drain test, which did not alter its assertions. **676 bundled
JS and37 deployment-helper tests** passed. Independent static review found no
concrete blocking regression; it does not establish native TCP behavior.

Optimized **1.75/code69**, 25,860,686bytes:
`e72bc9dac37a8d425ae3b84f31b84d436ce776ac82aa4d798486cf72e18d22ff`.
Signing certificate matches the accepted local baseline. This is local device
iteration, not a published release.

## Family device acceptance

One authorized ADB install succeeded and full installed APK readback matches.
All **7 actual HTTP checks passed** without client write-half-close: three stalled
unauthenticated401 requests, authenticated oversized413, wrong-method405,
fully supplied rejected body and ordinary authenticated200 identity.
Stalled401 samples:219.108,216.547,207.346ms; median **216.547ms**, compared with
no reply in the baseline3-second observation. That baseline is a lower bound,
not a measured30-second response. These are LAN response-completion samples;
no general Portal UI/FPS/performance claim.

An exact **70,001-byte** public scratch upload/readback passed, with the owned file
absent before and after. Complete registry profile and screensaver configuration
are unchanged; normal Dev mode remains on. Original Chrome task27174 is retained.
Its foreground is not claimed because natural screensaver state may change.
No account/login automation, photos, Home Assistant services, or Meta OS settings
were changed.

## Living deployment

The single native Fleet deployment exited0 with full installed APK SHA256 proof.
Authenticated readback confirms1.75/code69, unchanged complete registry profile
and screensaver configuration, and preserved Dev mode. No repeated Living native
interaction/performance run is claimed. Both rooms have the exact optimized artifact.

## Limits and next action

Only the named Family requests and scratch transfer were exercised; no repeated
Living interaction run or universal provider/phone/network acceptance. The prior
sealed Family app-owned installer session remains registered and was not reopened
or fixed by this transport correction. Weather Home/settings-return UI acceptance
and all previously parked issues retain their status.

The samples also show200–268ms completion for the selected small replies. A
separate bounded investigation can distinguish response-body arrival from EOF
and transport buffering before proposing a TCP optimization. This is an observed
candidate, not a proven Nagle root cause or an implemented speedup.

The subsequent [phase measurement](portal-fleet-response-timing-2026-11-02.md)
resolved that measurement ambiguity: rejection body median6.233ms, identity body
median24.102ms and normal HTTP identity completion median23.028ms. The earlier
200ms figures include EOF after the complete body. No further production change
was warranted by that result.

# Fleet response timing — 2026-11-02

The independent unit started at **13:45:54 UTC**, with a **14:15:54 UTC**
deadline. Family remained on exact optimized **1.75/code69**. No Portal,
companion, browser or CLI production source was changed, and no APK was installed.

## What the measurements mean

The earlier 200–268ms raw-socket response-completion measurements included waiting
for connection closure. They did not measure when a normal HTTP client had the
complete response body. This follow-up separates those phases.

Twelve read-only Family requests covered four cases, with three samples each.
The client socket options were unchanged. Values below are medians in milliseconds:

| Case | Status | Complete headers | Complete body | Connection EOF |
| --- | --- | ---: | ---: | ---: |
| Header-only OPTIONS | 204 | 5.266 | 5.266 | 5.915 |
| Unauthenticated identity | 401 | 5.896 | 6.233 | 215.082 |
| Authenticated identity | 200 | 22.782 | 24.102 | 227.300 |
| Paired settings profile | 200 | 27.795 | 31.098 | 236.850 |

Three normal Python HTTP client identity requests completed their 1,059-byte
bodies in **25.854, 23.028 and 20.700ms**, median **23.028ms**. They used the
declared response length rather than waiting for EOF.

The current Rust CLI was freshly compiled from unchanged source. Three actual
Family `info` invocations exited successfully in **387.805, 235.620 and 237.703ms**,
median **237.703ms**, including process startup. Each returned valid JSON and
the current version. The first sample includes an unisolated startup effect;
it is not evidence of a Portal cold-start defect.

## Decision

The CLI's `request()` deliberately reads through EOF before applying strict
response framing. Its existing checks reject bytes beyond a declared length,
protect incomplete downloads, and accommodate diagnostic procfs streams whose
reported file length is zero. Stopping blindly at Content-Length would weaken
that contract. No such change was made.

The finding establishes a response-body/EOF distinction and the CLI's wait.
It does **not** establish the cause of the delayed connection closure, a
browser/phone UI delay, or a performance benefit from TCP_NODELAY. There is no
evidence here that warrants a new Portal APK or a weakened download guard.
This candidate is closed as **scoped measurement verified, no production change**.

The prior rejected-body correction remains delivered: it removed the wait on
an unsent request before sending an error. Its historical completion receipts
remain intact, with this phase clarification linked alongside them.

Only read-only Family requests were made. No account login, playback, owner
settings, files, Home Assistant service or Meta configuration was modified.
Living was not retested. Source/runtime integrity tests retain their previous
acceptance scope; this unit compiled the CLI and measured actual requests.

The public selected receipt is
`mocks/portal-widget-gallery-20261102/evidence/fleet-response-timing-verification.json`.
Full response payloads remain private. Weather presentation approval, ordinary
YouTube sign-in/playback, physical phone acceptance and parked issues remain
separate.

# Photo HTTP redirects: source headers and bounded requests

Delivered on 3 November 2026: exact optimized **1.93/code87** installed in Family
Area and Living Area. This changes the shared `HttpURLConnection` photo paths.
It does not establish a blanket privacy guarantee for all app networking.

The original issue clock is **01:47:02–02:17:02 UTC**, including discovery,
research, implementation, review, checks, deployment, preservation, cleanup and
commit. Pixel control and inspection remain prohibited.

## Problem and implementation

Platform automatic redirects can move a photo request to another hostname or
port while retaining custom source headers. Family's actual optimized 1.92
Immich album reader sent a generated `x-api-key` to all ten alternate-hostname
and alternate-port destinations in the owned fixture. No real NAS credentials
were used, and no current real-account failure was observed.

`PhotoSourceRedirects` disables automatic redirects and validates each target
before opening its connection. Any nonempty source header map is restricted to
the initial request's scheme, case-insensitive hostname and effective port.
This deliberately treats cookies and custom API keys as sensitive. Empty public
header maps can still follow cross-origin redirects.

The helper rejects HTTPS-to-HTTP redirects, non-HTTP URLs, URL credentials,
fragments and missing destinations. It follows at most five redirects after
the initial request, for a maximum of six requests. GET/HEAD retain their
method; POST and custom methods follow only 307/308 so the caller can replay the
same method and body.

The migrated callers are Immich GET/POST metadata, WebDAV PROPFIND, approved Home
photo loading, face-focus image loading, slideshow image downloading and video
prefetch. Existing encoded-body, decoding and cache boundaries remain at the
callers. Each hop registers with its existing cancellation owner before
configuration or response evaluation; rejected/intermediate connections are
released. The final response remains caller-owned.

Independent review identified a video-prefetch registration ordering regression.
The final implementation restores assignment before the active/stopped check,
and the helper closes the transport if that check throws. The full build/test
gates and native candidate run used the rebuilt artifact after this correction.
Controller-specific pause interleavings were not tested on hardware.

## Verification

| Check | Original | Final |
| --- | ---: | ---: |
| Initial JVM regression methods | 8 failures in 10 methods | 16/16 pass |
| Actual Family combined output/request checks | 8/20 | 20/20 |
| Alternate-host/port destination requests | 10 | 0 |
| Generated keys received by those destinations | 10 | 0 |
| Same-origin GET redirects, 301/302/303/307/308 | 5/5 | 5/5 |
| Five-redirect successful chain requests | 6 | 6 |
| Sixth redirect chain requests | 7 | 6 |
| Loop requests | 21 | 6 |

The complete final **4,054 JVM tests in 422 suites** passed with zero failures,
errors or skips. Debug and optimized performance builds, R8 and vital-lint gates
passed in the final combined 170-second invocation. The initial JVM red run uses
the host JDK; its 308 behavior is not the same as Android's. Actual Android
same-origin GET 308 already worked in the baseline.

The native fixture loaded each exact optimized APK through a separate shell
`DexClassLoader` on Family's Android 10/API29. Actual APK disassembly and mapping
established the optimized album-reader binding and returned album fields;
class-loader provenance and hashes were verified. Twenty generated authenticated
GET cases captured exact source/destination requests on two owned LAN listeners.
Both alternate destinations were reachable in the baseline, ruling out a blocked
destination as the explanation for the final zero-contact result.

The currently installed 1.92 APK was unchanged during both fixture runs.
The candidate native process returned zero; its complete passing receipt,
server audit and scratch cleanup were saved. The outer tool session handle was
unavailable after compaction; no runner remained and no duplicate run was started.
The baseline outer command returned the expected failure status.

## Deployment and preservation

The accepted APK is `com.immortal.launcher.debug`, **1.93/code87**, 25,861,122 bytes.
Its SHA-256 is
`408976c6c44ece08ac65efd76354c52fb3be34f57a25e6d2714763273a29830c`.
The signer matches the existing installed lineage. The pre-review artifact was
retained privately and was never installed or used for native candidate checks.

Both room deployments completed and verified the full installed APK hash.
Fresh complete screensaver, settings profile, Home layout and source maps match
their baselines; names and development mode are unchanged. Family font scale,
accessibility services and the original Chrome activity/task are retained, and
the normal photo frame is foreground and visually checked. Living's presentation
was not visually inspected.

Both exact newly created Android scratch roots were removed and independently
checked absent. The owned fixture server reached terminal shutdown; both
listeners and the process are absent. Frozen token registry copies were removed,
private artifacts have restrictive permissions, and the worker is closed.
No Pixel commands were issued; earlier phone cleanup remains deferred.

## Limits and research

The direct platform video path still calls `videoView.setVideoURI(uri, headers)`;
it does not use this helper. Video-prefetch coverage must not be mistaken for
direct-streaming redirect coverage. Generic app HTTP, calendar and store paths
were not migrated.

Native evidence covers authenticated Immich album GET only. POST replay,
public cross-origin GET, cancellation and target policy have JVM checks; native
PROPFIND/POST/TLS, decoding, app-UID networking, actual account/CDN compatibility
and controller pause interleavings remain unverified. Cross-origin redirects
with any nonempty source headers are intentionally refused, including a
legitimate authenticated CDN if it requires that behavior. The hop count is not
an aggregate wall-time, response-memory or FPS budget. No ordinary NAS speedup,
cold slideshow or whole-product performance claim is made.

Primary sources read for this unit:

- [Android HttpURLConnection](https://developer.android.com/reference/java/net/HttpURLConnection)
  documents automatic cross-origin redirects and a redirect limit. Family's
  actual baseline loop made 21 requests; current documentation is not a
  substitute for this device result.
- [AOSP historical HttpEngine](https://android.googlesource.com/platform/external/okhttp/+/ba6e9e9/okhttp/src/main/java/com/squareup/okhttp/internal/http/HttpEngine.java)
  shows removal of `Authorization` across origins. It is supporting context,
  not proof of Family's exact firmware or protection for custom headers.
- [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html#name-307-temporary-redirect)
  defines method-preserving 307/308 redirects and explains historical
  POST-to-GET behavior for other statuses.

The public machine-readable receipt is
`mocks/portal-widget-gallery-20261102/evidence/photo-redirects-verification.json`.
Publication metadata, APIs, Home Assistant and approved/parked issue boundaries
remain unchanged. The whole-product goal stays active.

# Options scanner: preserve continuation tokens

Optimized **1.207/code201** is installed and completely hashed in both rooms.
The scanner now URL-encodes only the opaque `page_token` query value. Previously
`+` became a space, `&` split another parameter, literal `%2F` decoded into `/`,
and `#` became a URL fragment. This can corrupt continuation requests.

The correction adds `URLEncoder` and changes one expression in `AlpacaClient`.
Whole-source reversal restores the baseline exactly. First-page parameters,
stock/options parsing, filtering, ordering, page limit, cancellation checkpoints
and configuration remain unchanged. This preserves token transport; it does not
introduce a new input-validation policy. Earlier negative options/input-policy
discovery and the delivered stock-price fallback retain their original scopes.

## Acceptance and delivery

The frozen regression calls the actual `fetchOptionChain` and shared `Http`
reader with generated HTTPS connections in an isolated child JVM. Its six cases
produce four intended baseline AssertionErrors and two passing controls, then
six passes with the unchanged test. One JUnit wrapper represents these child
cases; they are not six additional JUnit methods.

The tests check the complete decoded query, exact first-page URL, both returned
contracts, generated authentication headers, connection retirement, zero
preference writes and the one-page limit. They do not assert a literal encoded
second URL or newly exercise cancellation.

Fresh checks passed: **4,744 JVM tests / 495 suites**, debug and optimized builds,
R8/vital lint, and JavaScript suites reporting 404 slideshow / 786 remote tests.
Build gates took 100.34 seconds. Independent source/test review found no blockers;
root rehashed its material bindings. All 23 main-source assets match baseline.
The generated DEX profile changes with the APK.

The sealed optimized APK is **25,887,623 bytes**, SHA256
`6dec140dcebe9ffc9bed73b60dca80a0b52e283a1513844f838b1e8798b95288`.
Both complete installed APK reads match. Each room received one install attempt;
Living's uncertain acknowledgment was resolved by six read-only observations.
The actual Family 1.206 APK is retained as rollback.

All **43 Family / 30 Living preference files**, file sets and four complete saved
maps/revisions remain exact. No internal WebView cache differences were observed.
Room names/DevMode remain. Family font/accessibility, original Chromium activity,
resumed frame and awake state remain; both normal frame requests return success.
Captured logcat has 1,622 / 1,939 lines and no fatal/ANR marker; this is a scoped
observation. Copied registry records were removed and owner records hash-match.

## Clock and limits

Original discovery **07:13:50 UTC**, cap **07:18:50**, target **07:28:50**,
hard deadline **07:43:50**, all on 5 November. The candidate was source-qualified
at **07:18:28.195550**. The definitive regression finished at **07:20:00.154990**,
**70.15 seconds after the discovery cap**. That overrun is retained; the clock
was not reset.

Generated JVM transport acceptance does not establish live Alpaca compatibility,
mounted scanner UI, Android/optimized caller execution or API28 acceptance.
No household provider/account, phone, GitHub push or public publication occurred.
The public manifest remains 1.83. All prior held clocks remain, including the
optimized photo harness gap; this is not additional screensaver visual acceptance.
The whole-product goal remains active.

Machine evidence: `mocks/portal-options-page-token-20261105/verification.json`.

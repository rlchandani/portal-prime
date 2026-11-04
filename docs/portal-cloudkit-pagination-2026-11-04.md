# CloudKit album progress and photo identity

Optimized Immortal **1.133/code127** is installed in Family Area and Living Area.
Both complete installed APK readbacks match **25,884,988 bytes**, SHA-256
`d06aba9bbfdef8dcfc14f0dc7ce6cd8606114952e6c5df846e796a6c622e58a9`.
The iteration signer, package and nine screensaver assets remain exact.

The public CloudKit album loader previously requested repeated or cyclic
continuation markers until its existing 50-page cap. It also appended every
usable master record, including repeated records within or across pages.
The slideshow consumes that list directly, so duplicate masters repeat photos.
This is a supported generated failure, rather than a reported live Apple incident.

The loader now records requested markers and stops before requesting one again.
After selecting a usable image, it preserves the first occurrence of each master
record name. Unnamed masters use their image URL as identity. Separate namespaces
keep named and unnamed identities distinct, and distinct named masters sharing
an image URL remain separate. An unusable first record does not consume identity.
Title, ordering, image/derivative selection, video filtering, forward empty pages,
partial results after a later failure, and the 50-page cap remain.

## Verification

The frozen JVM fixture invokes actual public `RemoteAlbum.fetch` and the
`PhotoSourceHttp` metadata reader with generated HTTPS responses. Its 12 child
cases cover repeated/cyclic cursors, forward overlap, same-page duplication,
distinct masters sharing a URL, unnamed identity, unusable first records, empty
forward pages, asset/video pairs, malformed/failed/missing later pages, an empty
terminal album and the ordinary cap.

The corrected baseline has five AssertionErrors and seven passing controls.
All 12 candidate cases pass, including independent direct-child and JUnit runs.
Only the wrapper is a JUnit method; child cases are reported separately.
All **4,392 JVM methods / 459 suites** pass with zero failures, errors or skips.
The 48 existing album, URL and response-budget checks pass. Debug, optimized/R8
and vital lint pass in **1m53s**.

The same frozen Java fixture loads actual baseline and candidate debug APKs on
Family Android10/API29. It calls public fetch on a background thread using real
Android JSON and a process-local generated connection handler, with **no INTERNET
permission**. Five failed baseline cases and seven controls become 12 passes.
Repeated-marker requests fall **50→2**, cycle requests **50→3**, and the ordinary
cap stays 50. Title/headers and connection disconnection are checked in every case.
Both fixture packages are uninstalled; the final fixture process is absent.

Baseline debug: **46,726,887 bytes**, SHA-256
`0a9b8df0bbbd8bb2d6ddc1d0284964129802407d590db29c81907d17bab51f88`.
Candidate debug: **46,726,883 bytes**, SHA-256
`f91d8cc51d0f874c22d110cdf17bcefc2c8b8d1ecc829314f1971442c19bb3c0`.
Independent source, fixture, runtime bindings, XML, native reports and APK review
passes 18 scoped checks. No keep rules are added.

An initial JVM attempt used the wrong resolver host and query record type;
it never established pagination RED and is preserved separately as setup-only.
One Java fixture compilation failed on a synthetic property reference before
native execution. Both corrections are included in the original issue clock.
Successful native compilation has four Java8 option warnings.

## Delivery and limits

Family's single install returned Success. Living's response disconnected;
seven read-only observations confirm the full installed APK hash from one
attempt, with uncertain acknowledgment and no retry.
All **43 Family and 30 Living preference files**, file sets, four complete maps,
room names and Dev Mode match their baseline. Family font/accessibility and its
original Chromium activity remain. The existing remote action resumed the
configured frame; the frame is resumed and the display awake. The Family frame
was visually inspected; its screen capture stays private. Living visual and
functional album acceptance remain open.

Bounded diagnostics and logcat responses contain **1,610/1,987** actual log lines
without fatal/ANR markers. These checks do not establish every runtime path.
Native evidence covers generated debug execution on Family API29. Live Apple
responses, optimized functional execution, API28, arbitrary cancellation, forced
fixture timeout and byte-for-byte photo rendering remain unverified.

Original registries, public1.83/code77 release manifest/catalog, native fleet CLI,
prior raw-restore helper and unrelated cache/alarm sources remain exact.
Copied registries and owned documentation scratch are removed at closure;
private rollback, source APKs and before/after snapshots are retained.
No phone, source photo-file read/import/transfer, provider/account action,
GitHub push or publication occurred.

This distinct unit retains **06:16:40–06:46:40 UTC November4**, covering discovery,
fixture corrections, implementation, checks, review, delivery and closure.
Earlier response-budget and Immich pagination scopes remain separate.
The bounded negative alarm audit retains its earlier Crypto discovery clock;
all parked and approval scopes retain their original clocks.
The queue preserves all 122 existing objects and their exact raw suffix, adding
one delivered task: **123 total, 75 delivered, 38 locally verified, 2 approval,
7 parked, 1 queued**. Weather remains default. The whole-product goal stays active.

Evidence: `mocks/portal-cloudkit-pagination-20261104/evidence/verification.json`
and `native/`.

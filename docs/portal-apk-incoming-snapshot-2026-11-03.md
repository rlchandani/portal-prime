# Incoming APK snapshot ownership — 2026-11-03

Exact optimized **1.121/code115** (`com.immortal.launcher.debug`) is installed and fully hashed in both rooms: **dd13ab0ca23169f620ab800d8ee32ad4c17661778e269da71f932ca9102499c7**, 25,879,086 bytes. The retained 1.120/code114 rollback is not reinstalled; the helper differential uses an explicit extracted-equivalent baseline. Public manifest remains **1.83/code77**; no publication.

Original clock: **12:12:15.980481–12:42:15.980481 UTC**, including discovery, validation, delivery and cleanup. No older issue receives a new slot.

## Problem and correction

Two ordinary external APK opens previously wrote the same `cacheDir/incoming.apk`. An earlier prompt could keep the label and package read from APK A while a later import replaced its addressed bytes with APK B. A failed later read or close could leave partial or different bytes in that earlier snapshot.

The active exported open-with Activity now delegates copying to `IncomingApkSnapshot`. Every import allocates its own temporary APK within the existing cache. Allocation and source acquisition failures return the existing failure result; read, write or close failure removes only that import's owned file. Source streams still close through nested `use`, and successful empty input retains the existing acceptance policy.

The Activity retains its successful snapshot. Destruction releases an idle owned file. A daemon worker marks consumption before launch, releases its own file in `finally` before clearing that flag, and a launch failure also cleans and rethrows. The session branch releases its snapshot after its existing synchronous copy/commit adapter returns or throws, including registration failure. The adapter copies and fsyncs source bytes before returning. These lifecycle and installer lifetimes are source-reviewed; the complete metadata/prompt and receiver/status bodies remain byte-identical to their captured original.

## Reproduction and checks

The original actual-onCreate JVM admission failed in the inherited Android Activity stub before copying or reading archive metadata. It is retained as an excluded admission attempt, not counted as product red. The first private Java compilation failed on protected access; its package correction compiled, then the stub-boundary runtime failed. No Activity, Compose or installation acceptance follows from that probe.

The root first extracted the existing copy operation without correcting it: same shared path, source acquisition, nested close order, partial-file behavior and failure return. Independent source review binds that extracted-equivalent baseline separately from the untouched rollback APK. Frozen literal tests then exercised this actual production helper against generated streams and a private temporary directory.

Ten frozen copy-helper tests reproduce six assertion-subclass failures and four controls, then all pass unchanged. Five original failures are `ArrayComparisonFailure`, one is plain `AssertionError`; all are assertion failures, with zero test errors or skips. Successive and empty imports, read and source-close failures preserve literal first bytes after the fix. Failed own files disappear while earlier snapshots and unrelated cache guards remain. Single-copy close count, null/open failure and foreign-cache controls remain.

| Gate | Actual result |
|---|---|
| Frozen helper baseline | 10 methods, 6 assertion-subclass failures, 4 controls, zero errors/skips; Gradle exit1 |
| Final JVM | **4,303 tests / 446 suites**, zero failures/errors/skips; all ten frozen methods unchanged and green |
| Builds | Debug, optimized/performance, R8 and vital lint; terminal0 in 2m 3s |
| Independent source and gate review | PASS with no blockers; exact Activity/helper/test/build, full gates and native results bound |
| Debug Family helper | 6/12 extracted-equivalent baseline to 12/12 candidate; 17 copies and opener calls per variant, 14 streams close once |
| Delivery | Both helpers exit0, exact full installed optimized SHA verified, existing screensaver return requested |
| Saved state | Four complete maps, room name and Dev Mode retained; Eleven preference states per room match: Family ten byte-identical files and one unchanged absence; Living seven byte-identical files and four unchanged absences. |

## Room observations and limits

Actual debug Family copy-helper checks improve 6/12 to 12/12: six corrected product checks, four retained product controls and two retained harness checks. Each variant makes 17 actual copy and 17 generated opener calls across ten generated case folders; all 14 generated source streams close exactly once. Two null sources and one generated open failure are retained. The baseline is the explicit extracted-equivalent debug APK `cf4b1514fb0151dbdcff80afbfa564a277f8d6bd0025c917f93a7212bfcebf92`, 51,334,221 bytes; the candidate is debug `288c54e8229ae423c6cf1f58fc8617825645c9813a36b559a7217157f46ba0ef`. The baseline differs from the original untouched rollback APK and is never described as original native Activity acceptance. On the observed Family Android10/API29 arm64 environment, the generated fixture invokes only the admitted debug helper through its actual APK classloader. Activity, metadata, installer, Context, provider, real network and owner-cache operations are zero. No optimized method attempt or call, no keep rules.

Both accepted native parents retire and owned scratch is removed with zero cleanup faults; this is normal observed cleanup, not an external-timeout or process-fault guarantee. The first native compilation succeeds with three Java8 obsolescence warnings. Initial author admission history is retained separately from accepted helper calls.

Family font, accessibility, original Chrome task, resumed frame, awake display and exact installed SHA checks pass. Root viewed the current Family screenshot: Family screenshot shows a black digital clock frame with beige 4:31, Tuesday, November 3, and the debug badge; no visible dialog or error. This is current frame acceptance only. This is only the current frame; incoming-APK prompt/installation UI and Living visual acceptance remain open.

Normal /diag and /logcat?lines=1500 requests return HTTP200; actual log outputs contain 1644 Family and 2015 Living lines with zero fatal or ANR markers. This is limited observation, not complete runtime health. Copied credentials are removed and original registry hashes remain unchanged. Public version guard returns the expected exit1 for local1.121/code115 versus public1.83/code77. Native Fleet CLI bytes remain unchanged. No household preference edit or restore was needed.

Generated-stream copy-helper JVM and debug native acceptance only; Activity lifecycle and daemon/session consumption are source-reviewed. Optimized helper, native Activity, valid package installation, prompt UI, actual daemon/system installer and API28 remain unverified. Cleanup is best effort; process death can leave cache files. No crash or power-loss durability, universal cleanup, source trust or successful installation claim. Existing session callback correlation, callbacks to destroyed Activities, daemon queue contention and earlier installer acceptance restrictions retain their established scopes.

Sunrise repeat/config review is a bounded negative audit; prior one-shot, DST and settings-write corrections are retained. Intercom ownership matches its earlier source audit and retains that earlier clock. Other older parked/exhausted scopes remain unchanged. The broader product objective is active.

The selected public verification `mocks/portal-widget-gallery-20261102/evidence/apk-incoming-snapshot-verification.json` records exact source, frozen tests, artifacts, admission history, room preservation and cleanup. Private source captures, logs and owner payloads remain outside the public repository. No phone control, HA change, GitHub authentication, push or publication.

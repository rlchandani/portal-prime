# APK confirmation keeps its own file

The local APK browser previously confirmed scan-time metadata while retaining a
public file path. Replacing or deleting that download afterward could change or
remove the installer input. This is separate from the earlier exported open-with
copy fix and the parked Toolkit APK reader.

Selection now prepares one private copy off the main thread using the existing
`IncomingApkSnapshot`, then reads confirmation metadata from that completed copy.
The original filename and row identity remain. Confirmation gives exactly the
prepared file to the unchanged installer. Cancel, disposal, preparation failure
and the installation coroutine release only their owned copy. Cancellation and
Compose ownership are source-reviewed; copying does not promise an atomic version
of a source that is concurrently modified during the copy.

Eight frozen generated-file JVM cases reproduce six failures/two controls, then
pass unchanged. The extracted equivalent baseline models metadata plus public-file
handoff, rather than executing the original Activity. Its deletion failure is
`FileNotFoundException`; the other five are assertion-family failures. The fake
missing-file parser case tests the helper boundary, not Android archive admission.
The original signed rollback APK is retained separately.

All **4,434 JVM tests / 463 suites**, debug/optimized builds, R8 and vital lint
pass. The unchanged generated native fixture improves six failures/two controls
to eight passes on Family/API29 using actual embedded debug APK code. All 48 file
guards and both retirements pass. Java/DEX and assertions stay identical; native
metadata failure uses an unchecked exception, while JVM covers IOException.
Independent source, artifact and native report reviews have no blocker.

Both rooms contain exact optimized **1.146/code140**, 25,886,647
bytes, SHA-256 `5e693e23c24d9cd16b2ece89a1cde69d4c8c25dc4d2d4965962a678fd231b0a0`. One install per room;
Living's uncertain acknowledgment is reconciled by six read-only observations.
All **43 Family/30 Living preference files** and four complete maps match. Family
font/services/original Chromium/resumed frame/awake state remain. Limited logs
contain no fatal/ANR marker; this is bounded observation. The compiler's saturated
2GB heap required a temporary4GB invocation; final checks passed in2m6s, with no
repository build-setting change. Excluded command/type/stalled runs remain private.
Delivery and preservation elapsed **18.27 minutes**, past the15-minute target
and within the original30-minute hard limit; commit/docs follow within that limit.

Native helper execution uses generated fixture-local files and metadata callbacks;
it does not exercise Android archive parsing, the rendered dialog, coroutine
lifecycle, valid APK installation, the daemon, API28 or optimized helper behavior.
Deletion remains best effort. No owner APK was installed or household source/media,
provider/account, phone, push or publication operation performed. Saved Shuffle/fit
remains; all ten screensaver entries are byte-exact. Public release stays1.83/code77.

Original issue clock **09:54:27–10:24:27 UTC November4**, including discovery, with a
15-minute target. Earlier task objects, Weather default and parked clocks remain.
Whole-product goal active. Portable generated fixture and scoped evidence live in
`mocks/portal-apk-browser-selection-20261104/`.

# Stocks: retain a valid daily price

A missing, null, nonnumeric or NaN latest-trade price now permits the existing daily
close fallback. Previously `JSONObject.optDouble` returned NaN, which prevented Kotlin's
null fallback and caused an otherwise usable quote to be omitted. One stock-only
expression now uses the established option-parser `takeUnless(isNaN)` pattern.
Valid latest prices, including zero, keep precedence; absent latest trade still falls
back and unusable prices still omit the quote. Transport, options, UI and settings remain.

Eight frozen tests call the actual parser with generated JSON and literal expectations.
The unchanged baseline produced four intended AssertionErrors and four passing controls,
with no setup errors or skips. The candidate passes all eight and the full **4,573 JVM /
476 suites**, debug, optimized, R8 and vital lint in **110.48s**.
Independent review was fully read; root rehashed **25 material
bindings**, all23 source assets and all476 XML before promotion. All23 source assets
match1.175; generated optimized profiles alone may differ with retained valid headers.

Exact optimized **1.176/code170** is installed in both rooms with complete APK readback,
one install each. Living acknowledgment was checked with **7 read-only observations**.
SHA256: `b57129ef929e39c430fd56ee6b667db7fef0c1b1961fc4c92afcf75ca016cb35` (25,886,951 bytes).
All43 Family and30 Living preference files and four complete maps are unchanged;
names, Developer Mode, saved Shuffle/fit and Family font/accessibility/original Chromium
Activity remain. Both frame requests succeed; Family frame is resumed and awake.
Captured fatal/ANR markers are zero. This is scoped evidence, not whole-product health.

Registry preparation reused the existing `restore-target` helper for only the two
explicit records, with no external device calls. Copied records are retired; owner
registry and preexisting scripts cache remain. Original **19:42:00 UTC** discovery,
**19:57:00 UTC** target and **20:12:00 UTC** hard stop include all work without reset.
Delivery, preservation and cleanup took **17.69 minutes**; docs and commit use
the same clock. Target met at closure: **False**.

Generated JVM snapshots verify parser behavior. Live provider/account behavior, native
widget display, optimized/API28 parser execution and broader screensaver acceptance
remain open. The earlier Stocks HTTP failure-to-empty observation is separate and
unimplemented. No phone, provider/account, household media or publication operation;
public1.83 remains. Evidence: `mocks/portal-stock-price-fallback-20261104/evidence/`.

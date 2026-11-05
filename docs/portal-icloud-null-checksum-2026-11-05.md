# iCloud photo derivative null checksums

Delivered optimized **1.194/code188** to both rooms, with complete installed-APK byte/hash readback and one install attempt each.

On Android, `optString` turns an explicit JSON-null checksum into the string `"null"`. A larger unusable derivative could therefore displace a smaller valid checksum. The legacy iCloud loader joins returned asset URLs by the chosen checksum; under normal valid-checksum responses, that wrong selection drops the photo. One guard skips JSON null before the existing getter. Literal strings, non-null coercion, screen target and derivative-size rules retain their existing behavior.

The **actual debug APK selector on API29** improved from **two mismatches/three controls to five passes**, using the same frozen Java/JAR/driver and framework JSON with verified class origin. Both null cases previously selected string `"null"`; the candidate selects the usable smaller checksum or no checksum. Literal string `"null"`, absent checksum and normal covering selection are controls. The fresh isolated probe recorded zero URL opens; both owned scratch paths were removed and processes are terminal.

All five host JVM cases passed before and after the guard: host `org.json` differs from Android, so the host baseline is **not RED**. Host and native literal-string vectors differ by one extra smaller derivative. All **4,677 JVM tests/487 suites** and debug/optimized/R8/vital build checks passed in **105.03 seconds**. Independent source review passed; root rehashed all19 bindings. Both APKs keep the baseline signer and all23 source assets.

Final43 Family/30 Living preference files, file sets and all four complete settings/source/layout maps remain byte-identical. Family font, accessibility, original Chromium activity, resumed frame and awake state are preserved. Both normal-frame requests passed. Captured fatal/ANR marker counts are recorded in the full JSON; these samples do not establish universal crash freedom. Copied registries were retired and owner registry hashes match.

[Generated delivery summary](portal-delivery-summary-1.194.md) uses the reusable offline helper. Full evidence and generated probe sources are in `mocks/portal-icloud-null-checksum-20261105/`. Native acceptance is for the selector, not a complete provider/album/download/cache/UI/pixel workflow or execution of the optimized selector. API28, Living native and physical-phone acceptance remain separate. No account, household source or public release was changed; public manifest1.83 remains.

Original start **2026-11-05 03:02:14 UTC**, discovery cap **03:07:14**, target **03:17:14**, hard limit **03:32:14**. Discovery finished23seconds over its cap. The clock was not reset; earlier held issues and the whole product goal remain open.

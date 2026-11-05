# Immich playable asset admission — 5 November 2026

**1.186/code180** is installed and fully hashed in both rooms. Malformed
Immich asset rows are skipped individually: non-object rows, missing/null/
non-string IDs and blank strings consume neither a media slot nor an identity.
Valid photo neighbors survive instead of the entire list falling back. Valid
IDs keep their bytes; video selection, cap, first-seen order, album/library
filters and pagination remain. The album picker is outside this correction.

Six frozen generated local HTTP regressions fail with actual AssertionErrors
against unchanged source; twelve existing controls pass. All eighteen pass
afterward, including preservation across a malformed continuation page.
All **4,626 JVM /481 suites**, debug, optimized, R8 and vital lint pass; build
107.38s. Independent review accepts the exact three-line parser correction.
All23 source assets and the photo controller remain exact from1.185.

The same frozen native Java/JAR loads the exact baseline and candidate debug
APKs through a verified DexClassLoader on Android10/API29. Five literal failures
and three controls become **8/8 passes**. This also proves Android's actual
JSON-null/numeric coercion, which differs from the host JSON implementation.
The probe calls only the private parser with generated data; no Context, live
provider, household album, network fetch or image decoding is accessed.

Both optimized installed APKs are25,886,947 bytes, SHA256
`ea88cd9fbad7b07627066452e69e814bad2f940bacc36e2f9259ff9d28b8c8a8`.
One install per room; uncertain Living acknowledgment is reconciled through
read-only observations. Final43 Family and30 Living preferences are byte-exact;
all four saved maps—including layout revision—match. Family font/accessibility,
original Chromium task, resumed frame and awake state remain. Final captured
fatal/ANR markers are zero. Owned scratch roots and copied registries are removed.

Generated HTTP/native evidence does not prove live Immich compatibility,
rendered photos, optimized parser execution, API28 or Living native behavior.
No UUID enforcement, ID trimming or complete URL-path validation change.
No phone/account/authentication/push/publication; public release remains1.83.
Held scopes retain their original clocks. Original00:33:37 UTC, target00:48:37,
hard01:03:37; no reset. Machine evidence:
`mocks/portal-immich-asset-admission-20261105/verification.json`.

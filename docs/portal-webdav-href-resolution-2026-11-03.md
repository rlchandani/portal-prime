# WebDAV photo-link resolution — 3 November 2026

Optimized **1.91/code85** is installed in Family Area and Living Area. WebDAV
photo listings now accept both absolute paths and absolute URLs on the configured
server. Previously an absolute URL was appended to another origin, producing an
invalid photo address; an absolute child folder could fail the whole listing.

XML-escaped filenames are decoded once before URL resolution. Percent escapes
remain intact, and a photo's download query no longer hides its file extension.
Links with another scheme, host or effective port, embedded user information,
fragments or malformed syntax are excluded. The existing image/video selection,
media cap, cancellation checkpoints and source configuration remain.

The distinction between XML escaping and URI encoding, and the two permitted
href forms, follow [RFC 4918, section 8.3](https://www.rfc-editor.org/rfc/rfc4918.html#section-8.3).
The change uses the existing reader rather than adding a second photo pipeline.

## Verification

| Check | Before | After |
|---|---:|---:|
| Initial HTTP regressions | 8 failures in 10 cases | 12 cases passed, including two added controls |
| Complete JVM suite | — | 4,026 passed, 420 suites, zero failures/errors/skips |
| Debug and optimized builds | — | Passed with R8 and vital lint; combined run 159 seconds |
| Family's optimized Android reader | 2 of 8 cases passed | 8 of 8 cases passed, zero invocation errors |
| Candidate server audit | — | All 9 expected PROPFIND requests, generated Basic auth present, zero foreign-origin requests |

The eight native cases cover ordinary absolute paths, absolute URLs, current
collection exclusion and child traversal, named/numeric XML ampersands,
one-pass entity decoding, preserved percent escapes and another-origin exclusion.
The JVM cases also cover query parameters, invalid XML references, malformed
links, media caps and video inclusion.

The native fixture loaded the exact installed 1.90 APK and then the byte-identical
optimized 1.91 candidate in separate shell `DexClassLoader` processes on Family's
Android 10 hardware. Mapping and actual APK disassembly established each R8 method
and argument order; classloader provenance and full APK hashes were checked.
The candidate was tested before installation, and both installed APKs subsequently
matched its complete hash.

The first fixture invocation stopped at a Python syntax error before any device
command. The fixture was corrected and compiled before the real baseline run.
Independent production/test review found no actionable blocker within this scope.

## Preservation and limits

Both complete settings profiles, layouts, photo-source maps and screensaver
configurations match their original baselines. Device names and dev mode are
unchanged. Family's font scale, enabled accessibility services and original Chrome
activity/task are retained; its normal manual photo frame is foreground and
visually checked. Living's return was acknowledged, but its presentation was not
visually inspected.

The fixture used generated metadata and generated Basic-auth values. It did not
change a saved WebDAV source, contact a real NAS account, fetch or decode photos,
or exercise app-UID networking and source-form behavior. Native acceptance covers
the reader and href-origin boundary, not HTTP redirect credential handling.
The existing regex XML parser and trailing-slash handling for collection URLs
with queries remain limitations. No FPS, latency or whole-product performance
claim is made.

Both owned Family scratch directories were removed and their absence checked.
The owned metadata server terminated, private registry copies were removed and
both workers were closed. No Pixel commands followed the user's prohibition;
the stopped phone check's own cleanup remains deferred.

Package: `com.immortal.launcher.debug`. APK bytes: **25,861,122**.
SHA-256: `6f678251acf096b5cf99337f41a25da17168a621f49881852b57e48b86b415e7`.
The signer matches the preserved 1.90 rollback artifact. This is a local preview;
publication metadata remains unchanged.

Original unit clock: **01:10:42–01:40:42 UTC**, including discovery, deployment,
cleanup and commit. Other exhausted investigations retain their original limits.

Structured receipt:
`mocks/portal-widget-gallery-20261102/evidence/webdav-href-resolution-verification.json`.

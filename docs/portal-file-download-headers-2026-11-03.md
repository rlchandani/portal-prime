# File-download filename headers

Delivered: exact optimized **1.94/code88** installed and fully hashed in Family
Area and Living Area. Family's native HTTP checks improved **1/4 to 4/4**.

Scope: the authenticated Fleet HTTP `/fs/read` download route. Original first
file audit **02:08:09 UTC on 3 November 2026**; the **02:38:09 UTC** deadline
includes discovery, research, implementation, review, all checks, deployment,
preservation, cleanup and commit.

The original route removed quotes from the filename but inserted other
characters directly into `Content-Disposition`. The server serializes response
headers as US-ASCII. A filename containing CRLF could become response header
syntax; Unicode filename characters were replaced during serialization.

`FleetFs.attachmentDisposition` sanitizes ISO control characters to underscores.
It supplies a printable ASCII fallback without embedded quotes or backslashes,
plus one `filename*=UTF-8''...` parameter retaining the sanitized Unicode name.
UTF-8 bytes outside RFC8187's attribute-character set are percent encoded with
uppercase two-digit escapes. Spaces use `%20`; plus remains literal and percent,
asterisk, apostrophe and semicolon are encoded. Empty names use `download`.

The protected GET route, file access boundary, content type, byte length and
stream writer are unchanged. This changes the attachment filename metadata,
without renaming or editing the source file.

Twelve regression methods tested the extracted original behavior: **11 failed,
one ordinary-name control passed**, with no test errors. They cover Unicode
surviving ASCII serialization, quoted/backslash names, CRLF, all ASCII/C1
controls, parameter delimiters, percent/space/plus encoding, UTF-8 octets,
nonempty fallback and exactly one legacy/extended parameter.

Primary sources:

- [RFC 6266](https://www.rfc-editor.org/rfc/rfc6266.html) defines the attachment
  filename parameters and recommends an extended filename alongside a legacy
  fallback.
- [RFC 8187](https://www.rfc-editor.org/rfc/rfc8187.html) defines UTF-8 parameter
  encoding, its attribute-character set and percent escapes.

## Final checks and deployment

All twelve regression methods and the full **4,066 JVM tests in 423 suites**
passed, with zero failures, errors or skips. Debug assembly passed. The initial
combined invocation failed at R8 after 227 seconds because the local disk filled;
that candidate was not promoted. Six completed, owned mapping snapshots were
archived losslessly as gzip, each checked against its original SHA-256 before
removing the uncompressed copy. Original mapping bytes remain recoverable;
current builds, Portal rollback APKs and phone artifacts were preserved.
The optimized/R8/vital-lint retry passed in **58 seconds**.

Independent source review found no concrete blocking defect; all four reviewed
source hashes matched the final build inputs. Native checks used the real
authenticated `/fs/read` route on Family's Android 10 hardware with four newly
owned, generated non-media files, each carrying the same 8,192-byte body.

| Native case | Original 1.93 | Final 1.94 |
| --- | --- | --- |
| Ordinary ASCII name | Pass | Pass |
| Unicode/emoji name | Name replaced with question marks | UTF-8 name retained |
| Quote and backslash | Name lost characters | Extended name retained |
| CRLF in filename | One synthetic `X-Immortal-QA` header | Zero synthetic headers |
| Body and declared length | All four correct | All four correct |

Both exact APK hashes matched after installation and both fresh complete
screensaver, settings-profile, Home-layout and photo-source maps matched their
baselines. Room names and development mode were unchanged. Family font,
accessibility services, original Chrome activity/task and foreground manual
photo frame were retained; its screen was visually checked. Living's presentation
was not visually inspected.

Artifact: `com.immortal.launcher.debug`, 1.94/code88, 25,861,122 bytes, SHA-256
`6e134cd75e93800d3b81e73cdbc9d7909c06587958de3bb1845ac550e3543d1e`.
Its signer matches the installed lineage; its matching R8 mapping is retained
in a verified lossless gzip archive.

The four exact generated files, ownership marker and temporary Android directory
were removed, with a separate absence check. Frozen token registry copies were
removed and the worker closed. No fixture server was started, no real photo/file
was modified, and no Pixel command occurred. The public receipt is
`mocks/portal-widget-gallery-20261102/evidence/file-download-headers-verification.json`.

Browser save-dialog behavior, arbitrary concurrent source-file changes,
whole-product networking, optical FPS and physical phone acceptance are outside
this unit. A standard filename hint does not establish every browser's local
filesystem naming behavior. Pixel remains prohibited. Direct video-stream
redirect research is separately parked with its original clock; no platform
streaming privacy completion is claimed. Publication metadata and Home Assistant
remain unchanged. The whole-product goal is active.

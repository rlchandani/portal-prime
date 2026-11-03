# LAN intercom PCM receive framing — 3 November 2026

The intercom receiver treated each TCP read as a complete PCM16 buffer and
ignored the number of bytes accepted by `AudioTrack.write`. TCP can split a
two-byte sample anywhere; a short write accepts only a prefix. The old loop
therefore lost samples or joined the wrong bytes. Zero and negative write
results also allowed it to consume later peer data.

The receive loop now carries at most one incomplete sample byte, submits only
complete frames and drains accepted prefixes before reading more data. Invalid
or non-progressing write results end playback through the existing exception
boundary. The fixed 2 KiB buffer, session retirement checks and caller-owned
playback cleanup remain in place. No settings, transport, microphone, UI or
session-ownership policy changes are introduced.

## Verification and delivery

| Check | Result |
|---|---|
| Actual `LanAudio.playFrom` JVM regressions | Nine unchanged methods: six original assertion failures → nine passes |
| PCM contract | Odd read fragments, short writes, combined fragmentation, odd EOF and preserved accepted bytes |
| Cleanup and controls | Zero/negative progress, read failure, contiguous writes and replacement-session retirement |
| Full JVM gate | 4,126 tests / 430 suites; zero failures, errors or skips |
| Build gate | Debug, optimized performance APK, R8 and vital lint pass in 1m 53s |
| Actual debug APK on Family Android10/API29 | 16/16 framing-helper checks pass with generated streams and a write ledger |
| Native edge cases | 2 KiB carry boundary, single-byte fragments, invalid counts and cancellation before/after reads and partial writes |
| Cleanup | No framework handles acquired; owned processes and device scratch removed |
| Room delivery | Exact signed optimized 1.105/code99 installed and full APK hash verified in both rooms |
| Preservation | Complete screensaver/settings/layout/source maps and Home/timer/Sunrise preference bytes unchanged |
| Family state | Normal frame visually inspected; font, accessibility services and original Chrome task retained |

The native fixture loads the actual debug APK's `LanPcmStream.copy16BitMono`.
It supplies generated in-memory streams and records exactly the bytes its writer
accepts. All nine meaningful complete-stream or accepted-prefix hashes were
independently checked. It creates no real AudioTrack, socket or microphone and
touches no framework service, app preference or network.

The first optimized-helper invocation produced no fixture result. R8 changed
its callback parameters to specialized concrete lambda classes, which the safe
interface-proxy fixture cannot invoke. That attempt and its verified normal
cleanup are retained privately as a fixture limitation. Optimized native helper
execution remains unverified; it is not counted as a product failure or a
baseline comparison. R8 and production code were not changed to accommodate it.
The before/after differential comes from the actual `playFrom` JVM tests.

The deployed optimized APK SHA-256 is
`f762f6ea741292c22c3d3f70d0e349ae9e21560433bbfa29779a832b6a4b081f`
(25,862,702 bytes). Exact signed optimized 1.104 rollback, the debug candidate and
both mappings remain retained privately. Copied Fleet registry records were
removed; original registry hashes match. Raw owner snapshots and credentials are
not published.

## Retained limits

Real AudioTrack acceptance counts, native `playFrom`, audible intercom quality,
socket/microphone behavior, full intercom UI, performance, API28 and Living visual
acceptance remain unverified. Generated native checks cover the helper in the
debug APK only. Cancellation checks do not prove all concurrent interleavings.
Normal native cleanup passed; timeout cleanup was not fault-injected.

The public manifest remains **1.83/code77**; the version guard deliberately
rejects local **1.105/code99**. No Google phone operation, Home Assistant change,
GitHub authentication, push or publication occurred.

The original **06:08:14–06:38:14 UTC** issue window includes the parallel source
audit, tests, builds, native verification, delivery, cleanup, documentation and
commit. The broader product goal remains active; design approvals, human sign-in
dependency and previously parked clocks are retained.

Structured receipt:
`mocks/portal-widget-gallery-20261102/evidence/lan-audio-pcm-verification.json`.

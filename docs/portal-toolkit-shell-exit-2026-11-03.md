# Portal Toolkit shell command exit status

Toolkit now marks a returned nonzero shell exit as an error and excludes that
command from the existing success-only history path. It retains the actual Dadb
response until classification, preserving command trimming, `allOutput.trimEnd`,
zero-exit stderr output and existing exception/disconnected/blank behavior. The
existing UI consumes `isError` for output coloring; an empty failure output still
has no new fallback label.

The frozen eight-case real `ShellViewModel` fixture uses public constructor/run,
transcript/isRunning and lifecycle APIs with inert mocked collaborators. Real
`AdbShellResponse` values carry generated output and statuses; no remote command
runs. Unchanged source produces **3 AssertionErrors and 5 passing controls**,
zero errors/skips. Failure witnesses show both `isError=false` and the generated
command entering mocked history. The same fixture then passes all eight cases.
Actual full gate: **203 JVM tests / 35 fresh suites**, zero failures/errors/skips,
plus debug assembly, **18:25:07.029074–18:25:28.118047 UTC**, exit 0.

Local Toolkit commit: `23073a5538191cde30b64058f558859d602f7c5b`, parent
`44d75050b85c2c14e72bc30b4e4fb1b554e1c68c`. Its exact three paths are
`ShellViewModel.kt`, the new `ShellExitStatusTest.kt` and the test-only Mockito
5.11.0 declaration. All remaining ViewModel bytes are retained. Independent
source/result review passes **14/14**; the original Toolkit checkout is unchanged.
The existing configured host-test workflow discovers the fixture; hosted CI was
not observed or dispatched.

Debug APK: 13,901,710 bytes; SHA-256
`faddc3a3e405991d1f72af9fd6fbd802a2080ef03ba881371d591f942d33c792`.
Assembly does not establish signing, installed identity or native behavior.
The RED compiler startup diagnostic is retained: compilation and all eight
assertions subsequently completed in that run. GREEN retains the native strip
and JVM bootstrap-sharing warnings. A guessed-SDK preflight failure occurred
before Gradle; it is setup history, not product RED.

Acceptance covers generated host responses and mocked history-add invocation.
Persistent storage, recall UI, actual shell effects, cancellation, overlapping
run admission, reconnect/concurrency and physical phone acceptance remain open.
No package/version, install, device/provider, auth, push or publication change.
The filesystem filled during checkpoint preflight; only three owned regenerable
MkDocs output trees and an archived compiler log's empty cache directories were
retired. Source, evidence, screenshots and APKs were retained.

Original discovery clock: **18:18:11–18:48:11 UTC, 3 November 2026**, including
file-browser, Logcat and screenshot source observations. Those unselected
predictions have no fixture or runtime acceptance and receive no reset. The
older APK read candidate remains parked under its expired original clock; its
normal output was withdrawn at that closure. The current normal debug APK
belongs to this separate Shell commit. Earlier receipts remain closure snapshots.

Queue **110: 69 delivered, 30 verified, 3 approval, 7 parked, 1 queued**. All 109
previous task objects/raw suffix and Weather default are preserved. Structured
evidence is the repository file
`mocks/portal-widget-gallery-20261102/evidence/toolkit-shell-exit-verification.json`.

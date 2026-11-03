# Saved camera identities — 2026-11-03

Legacy same-millisecond camera IDs could make View select the first stream and
Remove delete every camera sharing the selected ID. CameraConfig now returns a
deterministic unique identity for every retained row. The first occurrence keeps
its valid ID; replacement allocation reserves all valid IDs, including later rows.
Names, URLs, order, existing JSON coercion and blank-URL filtering remain.
Missing/null/unconvertible IDs receive stable replacements. Load stays read-only;
existing synchronized Add/Remove persist the normalized snapshot.

Twelve unchanged actual-config regression methods reproduce **10 assertion
failures, zero errors/skips**, then pass, with two retained controls. They cover
both duplicate removal directions, caller-equivalent lookup, multiple/identical
duplicate groups, reserved later IDs and Long boundaries, stable missing IDs,
read-only load and mutation persistence. The full gate passed **4,186 JVM
tests / 435 suites**, zero failures/errors/skips, plus debug/optimized
APKs, R8 and vital lint in **2m 1s**.

Actual debug and optimized APK CameraConfig on Family Android10 both improve
**14/21 → 21/21**, exactly seven corrections and fourteen retained checks.
Generated strict in-memory preferences test actual parsing and Add/Remove;
Android JSON preserves Long minimum/maximum, zero, negative and numeric-string
IDs. R8 merges the config into Http; actual mapping identifies the methods,
singleton and row fields. Independently checked names/order and URL hashes match;
all source, fixture, compiler, runner and APK bindings match. The lookup mirrors
the caller; no CameraViewerActivity, VideoView, RTSP, UI or network is invoked.

The initial private runner mapping initialization failed before fixture execution;
its evidence and successful scratch cleanup are retained and excluded. Final
four owned fixture processes are retired and scratch removed. Normal cleanup
passed; failure/timeout cleanup was not fault-injected. Independent final review
has no blocking finding.

Exact signed optimized **1.110/code104** is installed and fully hashed in Family
Area and Living Area. SHA-256: `90e0c67fdce9022e46a96e53d7857a06f86e2717ba6ccd3f16f756731a0f4bb6`
(25,862,702 bytes). Complete maps and Home/timer/Sunrise/Chime/Weather/packs/
camera preference bytes match originals. Family's current night clock with date was
visually inspected; frame host, font, services, original Chrome task and awake
display remain. Original registry hashes match and copied credentials are removed.
Debug/rollback hashes, source bindings and process outcomes are in the proof.

Out-of-band raw preference replacement and already-open ambiguous cross-version
Intents remain outside this contract. API28, live camera/viewer acceptance and
Living native/visual acceptance remain open. Public manifest stays **1.83/code77**;
the local version-sync mismatch is rejected. No phone/HA/GitHub auth/push/publication.
Original independent audit clock **07:18:54.250284–07:48:54.250284 UTC** includes
all work. The broader goal stays active: **84 tasks, 51 delivered, 23 locally
verified, 3 approval, 6 parked, 1 queued**. All 83 earlier records, default Weather
selection and other boundaries remain.

Strict documentation build passed with zero warnings. Roadmap checks at 390px
and 1600px preserve the default Weather selection and all earlier records, with
no page errors, external requests or page/fact-cell overflow. Both detail images
were visually inspected, proof HTTP bytes match, and browser contexts are closed.
The existing local deliverable preview continues serving.

Structured proof:
`mocks/portal-widget-gallery-20261102/evidence/camera-legacy-identity-verification.json`.

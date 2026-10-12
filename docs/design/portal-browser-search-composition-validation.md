# Browser search text composition

Apps and Settings replaced the focused search input on each input event. Restoring focus and the selection afterward did not preserve the keyboard's unfinished composition. Search fields now retain the connected input through composition, coalesce compositionend and its final input event into one next-task render, and reject stale events after navigation or a room change. Pairing expiry, hidden/pagehide cleanup and navigation remain immediate.

Blur updates the query without replacing the control being activated. Late composition/input events cannot detach that target before its click. The next action renders the committed query; refocusing restores ordinary immediate search behavior. Generic form fields, native settings/navigation, persistence, HTML, core, CSS and Kotlin are unchanged.

## Verification

- Original eight focused checks: five failures and three passes before the change.
- Final nine focused checks pass, including the review-discovered Catalog activation regression.
- Full browser suite: 127 tests pass.
- Isolated mobile Chrome, 402 x 874, synthetic Apps and Settings composition: before, original input detached/lost focus/one blur; after, input connected/focused/zero blurs during composition and one replacement after commit. Final committed query and Apps filtering remain correct. Catalog accepts a real browser tap while synthetic composition/late commitment events run.
- All fixture HTTP/WebSocket traffic intercepted; zero external requests and page errors, no user browser profile/cookies/real Portal credential access.

Final gates pass: debug and signed builds; 1,918 JVM tests / 224 suites, zero failures/errors/skips; 127 browser tests; 100 offline admission checks. Both signed artifacts and source were frozen before the single Family Area trial. Candidate APK SHA-256 `58f24679533dae876ca94619f2351a2c98f6f25fcc3196aca8fe207102e0bc95` (25,447,730 bytes); bootstrap SHA-256 `049e5fddc9ce3f57a582eab51febe5d5f1746994d228efa14e0887a01a25f8ad`.

Family Area retained the candidate after two signed installs. Complete bundled/SDK/reference profile checks pass (8,667 methods / 2,576 classes / 285 app methods / 75 app classes); exact native Home -> Apps -> Home passes all three destination checks. Final Home, installed APK hash, inactive collector, configuration and frozen source preservation pass. Core and CSS retain accepted bytes; the served new controller is 154,706 bytes / SHA-256 `299037a8ffe486cfcfa937c890799aa79056b2526a132b11c74951d94ff04187`. There was one admitted owned-Immortal Dream normalization, no rollback, and no settings/photo/note/audio, Living Area, phone or GitHub operations. Specific individual-method compilation is not proven.

Frozen app source SHA-256 `369daef3aa26c4316c0c86793308a7dceb1a6c6f4d0adf2a9333d2bfff5eaec7`, 865 files. Exact source scope is the controller plus the new JS test; this document is outside the app source fingerprint. Final commit, end-to-end elapsed time and lease/source cleanup evidence are recorded in the local issue ledger after commit.

## Scope of evidence

Synthetic composition events verify DOM/focus/event handling in actual Chrome, not the phone's OS keyboard or native Portal input method. Controlled browser checks do not establish device latency/FPS or a phone-app performance gain. Only Family Area may be deployed/tested; Living Area and phone remain frozen. The original issue clock is 2026-10-12T11:29:03Z with deadline 11:59:03Z, including build/verification/cleanup/commit, without reset. Parked candidates are not reopened. No GitHub operations.

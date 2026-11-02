# Browser keyboard confirmation — Portal 1.84

Delivered optimized **1.84 / code 78**, `com.immortal.launcher.debug`, to
Family Area and Living Area. Complete installed APK readback matches the candidate
in both rooms. Its SHA256 is
`591ba118a00ec96c4b44c11e913b5a405cdbdc2216469fc320d7ca162632f9f1`;
size 25,861,122 bytes. Accepted signer verified; packaged remote assets equal source.

The original source-audit clock remains **19:56:33–20:26:33 UTC** on 2026-11-02,
including restoration and cleanup. It was not restarted after the phone unit.

## Change and compatibility

Previously the browser showed success unless `applied` or `dispatched` was
literally false. Seven regressions reproduced successful or misleading outcomes
for missing/type-coerced confirmation, malformed JSON and transport uncertainty.
The text endpoint actually returns a Boolean `applied` result.

Only `sendText` opts into literal `ok:true` / Boolean `applied` validation.
A positive result keeps the existing success notice. An explicit negative result,
401 or missing accessibility preserves rejection handling. Malformed, missing,
unreadable, disconnected or timed-out confirmation instead says:
“Text sending is unconfirmed. Review the Portal before sending again.”

The existing browser draft remains unchanged, including after confirmed success.
No automatic resend, added polling, new layout, server protocol or credential
change. Existing busy/session ownership guards remain; late replies cannot notify
another selected room. Optional mutation callbacks leave other commands unchanged.

## Evidence

| Gate | Result |
|---|---|
| Original actual controller regression |7 failed / 9; positive and rejection controls passed|
| Final focused controller |12 passed; real 12-second deadline,401/503, Unicode, payloads, late-room result|
| Existing full remoteJS regression |711 passed before three added focused cases|
| Android gates |3,887 JVM / 408 suites; debug, optimized assembly, R8/vital lint; 88 sec|
| Isolated actual Chrome controls |24 cases / 24 POSTs; Send/Append/Backspace/Clear, six response kinds; Clear confirmation and cancellation|
| Mobile/responsive interactions |360/402/1200px; no horizontal overflow, page errors or outside requests|
| Family served PWA |Controllerbytes match candidate; injected incomplete reply and actual `ok:true,applied:false` rejection, exact retained draft, no replay|
| Both room delivery |Complete installed APK bytehash matches candidate|
| Preservation |Full profiles/layouts/screensaver unchanged both rooms; Family accessibility/font unchanged, normal photo preview restored, Chrome task 27174 retained|

Independent worker Sagan ran the actual Chrome fixture and reviewed the production
diff. Its stdout receipt is exec 21663 / chunk 44cff0; no filesystem log was created.
Coordinator retained other commands/results privately under
`/private/tmp/portal-browser-text-confirmation-20261102`.
Public structured evidence:
`mocks/portal-widget-gallery-20261102/evidence/browser-text-confirmation-verification.json`.

The first timeout test mistakenly advanced DOM-only timers; the imported transport
uses real timers. The corrected test awaits its actual 12-second deadline and passes.
Direct ADB launch of the non-exported preview was denied; normal restoration used
Home plus the existing remote screensaver action. Its acknowledgment has
`ok/result`, not `dispatched`; actual PhotoFramePreviewActivity confirmed restoration.
Living’s incomplete install reply was resolved by whole APK readback, with no resend.
Owned Chrome closed, temporary Fleet registry removed, independent agent closed.

## Explicit limits

The live Family command tested a non-editable field rejection, not successful
text insertion. Positive responses use controlled fixtures. ServedPWA acceptance
ran in owned desktop Chrome at a mobile viewport, not Android WebView or a physical
Pixel. No FPS, universal performance, settled pixels or whole-product completion
claim. Phone 1.7.5 is unchanged and still awaits physical installation. Living had
artifact/configuration checks only. No GitHub or Home Assistant actions.

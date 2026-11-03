# Browser pairing keyboard submission

Enter in the address or six-digit PIN field now submits the existing pairing
form. The native submit button stays explicitly associated with that form
when the shared sheet moves it into its sticky footer. Click and Enter use
one submit handler; the existing pairing validation and ownership code remains.

The final exact-source baseline has **four leaf assertion failures and two
passing controls** at **2026-11-03T20:06:30.984602+00:00–2026-11-03T20:06:34.941031+00:00**. Enter in either
field sends no request; invalid Enter gives no correction; the pending case
cannot start its first request. Click/retry and Close already work. Node counts
the parent too, producing seven records/five failures.

The same corrected, frozen **8,480-byte** fixture then passes all **six leaf
cases** at **2026-11-03T20:06:53.100900+00:00–2026-11-03T20:06:55.328991+00:00**; seven Node records
including the parent pass. It loads the shipped HTML and assets, opens the
public pairing dialog and uses real locator/keyboard actions. Only generated
HTTP 400 `bad_pin` responses are returned. All six contexts have zero page
errors, outside/unexpected requests and WebSockets. No Portal is contacted.

All **724 distinct JavaScript tests** execute and pass with zero failures or
skips. The debug build and JVM gate succeed with all 38/26 tasks respectively
up to date; **no fresh JVM execution/count** is claimed. The actual
46,803,078-byte debug APK contains the exact corrected controller.
No install or launch occurred.

The initial post-fix run hit a fixture locator timeout when the pending action
changed from “Pair Portal” to “Pairing…”. The stable selector was corrected
before the final fresh baseline. The initial JavaScript gate also exposed
the small DOM adapter's missing native `disabled=false` default. Existing
assertions were preserved. The adapter now forwards a submit-button click
to its associated form; it does not emulate Enter/default-submitter behavior.
These attempts are retained separately from accepted RED/GREEN.

Local source commit: `ef4048c38eec5ed65cf6828ac252006c52fc1502`, parent `3128a82ad7012be7cbf72862b90644f7024e8205`,
recorded **2026-11-03T20:09:17.925651+00:00**. The three paths are the controller,
small DOM adapter and new opt-in browser regression. Its SHA-256 is
`c38c4f6b97f32f60f1a9668ae1fa703aa39bc18257663556ba56df27e36bef6c`. The current controller SHA-256 is
`e5d9b6da9fcce4af37f25cade291fb78b809b879f84330f068e872d3a6da6e51`.
The independent source/results review message preceded the commit; its durable
report was still pending then. Fixture review likewise records its actual
post-execution freeze time; neither report is backdated.

Run the browser regression with `IMMORTAL_REMOTE_CHROME=1 node --test
scripts/tests/remote_pairing_keyboard_browser.test.mjs` when Playwright and
system Chrome are available. This opt-in test does not change hosted CI.

- Actual shipped HTML/controller in isolated system Chrome with fresh contexts and generated HTTP 400 bad_pin replies only. No Portal, phone, live pairing, LAN/CORS, real token, roster-success or Android WebView acceptance.
- Six browser leaf cases: Enter in PIN/address, click/retry, invalid PIN, pending Enter/retry and Close. Node also counts their parent; seven records do not mean seven independent cases.
- The first post-fix attempt hit a pending-label locator timeout; corrected locator was frozen before a fresh exact-source RED/GREEN. The first JavaScript gate exposed an adapter disabled-default mismatch. Both attempts remain retained and are not product failures or passing gates.
- All 724 JavaScript cases execute and pass. Debug and JVM commands succeed with cached tasks; no fresh JVM execution/count is claimed. The APK ZIP contains the exact corrected controller; no install or native delivery.
- The new browser fixture is opt-in via IMMORTAL_REMOTE_CHROME=1; no workflow/dependency change or hosted CI observation. Existing 12 pairing ownership controls remain unchanged and pass in the JavaScript gate.
- The existing submission logic, PIN validation/clearing, pending/current guard, cancellation, discovery and retry remain. No generic button or pairing/auth policy redesign.
- Old browser-text, profile/photo, NSD/native-input, provider, phone, publication and all exhausted/approval-stage scopes keep their clocks and holds.
- Almanac/store/speed contracts are duplicate-stopped under their original clocks; locale/install-observation reviews are bounded negatives. No crypto locale defect was selected: Kotlin lowercase() uses invariant mapping.

Original clock: **19:53:38–20:23:38 UTC, 3 November 2026**, including discovery;
no reset. Queue **114: 69 delivered, 34 verified, 3 approval, 7 parked, 1 queued**.
All 113 prior task objects/raw suffix and Weather default remain.
Toolkit and its original checkout are unchanged. Whole-product work remains active.

Structured proof: `mocks/portal-widget-gallery-20261102/evidence/browser-pairing-keyboard-verification.json`.

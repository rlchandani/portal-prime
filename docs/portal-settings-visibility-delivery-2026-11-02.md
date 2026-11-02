# Screensaver settings visibility delivery

Bounded unit: **2026-11-02 07:51:21–08:21:21 UTC**, including restoration.
Branch: `codex/settings-visibility-20261102`, from accepted timer commit
`1ca6a54bae86ca4f593445835bf52ac364c1cc56`. Local, unpublished iteration
**1.68 / code 62**.

## Result and policy

When the generic screensaver master is off, both the Portal and browser show
only `enabled`. Advanced does not reveal inactive children. Twenty-four
registered predicates now include the master condition while retaining their
existing nested, source and hardware conditions. Storage, defaults, constraints,
side effects, native visibility preparation and the apply boundary are unchanged.
The separate calendar domain and bespoke source/sleep editors retain their owners.

Hidden values remain stored and can receive validated writes. This is a
presentation correction, not a blanket prohibition on editing inactive settings.

| Evidence | Before, installed 1.67 | After, installed 1.68 |
| --- | --- | --- |
| Live ordinary screensaver schema, master off | 16 controls | 1 control |
| Actual PWA at 390 × 844, category excluded | 11 ordinary rows | 1 row; also 1 with Advanced on |
| Native two-pane Slideshow with Advanced on | Existing master-only policy | Master/status only, visually inspected |
| Re-enable | Saved values existed | Native children return; PWA shuffle style, 30-second interval and shuffle restored |
| Saved configuration | 36-field original snapshot | All 36 fields compare exactly after testing |

Family native navigation used Home → Settings → Photos & screensaver → Slideshow.
The non-exported settings Activity correctly rejected a direct shell launch;
normal on-device navigation then passed. Family was returned to Home page **1 of 2**.
No user photo, layout, source credential or persisted setting was replaced.

Live inactive metadata and complete profile each retain **33 screensaver controls**.
An equal-value `shuffle` write while off returned `applied: ["shuffle"]`.
Out-of-range brightness `99` returned an empty applied list and preserved preferences.
Calendar remained accessible while the photo frame was off.

## Verification

- Four real registered-domain JVM methods passed, including **532**
  enabled/disabled × Advanced × source/style combinations, preference retention,
  inactive validation and independent calendar checks. Original implementation
  produced four assertion failures before the fix.
- Full Portal gate exited 0: **3,800 tests / 399 suites**, no failures, errors or
  skips; debug and performance APKs, R8 and vital lint passed in **7m 6s**.
- Two shipped PWA-controller guards passed; **33 focused / 654 full JS tests**
  passed. An in-memory stale-control mutation failed both new guards. Fixture
  schemas are synthetic and do not establish server or native behavior.
- Independent source review found no concrete defect in the Kotlin predicates.
  See [consumer review](portal-settings-visibility-web-review-2026-11-02.md).
- Mobile basic/Advanced disabled states, calendar navigation and re-enable passed
  against the actual Family server, with no horizontal overflow. The browser
  screenshot also contained an Offline banner, despite successful management
  requests; one navigation-aborted companion request and a favicon 401 were
  observed. This unit does not claim whole-PWA health or resolve that discrepancy.

## Artifact and rooms

- Package: `com.immortal.launcher.debug`, optimized/non-debuggable.
- APK: **25,860,666 bytes**.
- SHA-256: `271d3b1dc5b4dab2e57fb686ad7c19bf015d126b22271e536044cadca1e8e343`.
- Signer SHA-256: `4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6`,
  matching the accepted local baseline.
- Family: one in-place install returned Success; installed version and APK
  byte/hash readback matched exactly, followed by the scoped native/API/PWA checks.
- Living: one install dispatch, uncertain acknowledgement during restart;
  observer reached ready after three observations. A separate byte/hash/version
  readback matched exactly. No second install was dispatched.

No wipe, uninstall, new pairing, GitHub authentication, push, publication or HA
service deployment occurred. Private state, credentials and uncropped captures
remain outside the repository under `/private/tmp/portal-settings-visibility-20261102`.

## Companion boundary and limits

Review found that the phone's multi-room preflight used ordinary display metadata
and could reject a valid child write for a disabled room. The separate companion
fix is committed as `d2ab33982af13693a8a984912e921cbf2f41802f` in the isolated
Toolkit branch. It requests per-domain `includeInactive=1` metadata within the
existing FIFO preflight, retaining ordinary display reads and validation.
Nine added tests include disabled-child and combined writes, range/unsupported/
active-only rejection, FIFO ordering, query encoding and authentication.
Three expected fail-first assertions, **33 focused / 564 full JVM tests across
74 suites**, and debug assembly passed. Main independently checked the diff and
final XML totals; no physical phone was installed or run.

The phone APK is 15,369,788 bytes, SHA-256
`e319fb1486e6ad06dfa4bb3418a469acc75ee815c1688ec1bafba06c4cec0d9b`.
Older installed phones can enable selected rooms, refresh, then edit children.
Portal master toggles and server-side inactive validation remain supported.
Do not infer complete phone compatibility from Portal/PWA acceptance.

No new FPS, latency, physical-phone acceptance, optical brightness, full settings
inventory or whole-product completion is claimed. Previous exhausted night,
handoff and performance work remains parked.

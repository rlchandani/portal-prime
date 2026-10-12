# Native public-target geometry validation

Issue 35 began at 2026-10-12 09:06:48 UTC, with an unchanged deadline of
09:36:48 UTC. This is a local test-tool fix; it does not change or deploy the
Android application.

## Defect and correction

`HomeUiDiagnostics.Bounds.visible` and `RemoteInput.readHomeUi` admit
positive-area, integer rectangles that intersect the screen. They can extend
outside it. The preserved Notes trial's `targets` function required full-screen
containment before checking the row's role. An irrelevant non-clickable,
partly visible label could therefore invalidate a separate clickable target.

The reusable `scripts/portal_native_geometry.py` first selects the exact public
key and role. Observation evidence may intersect the screen; action targets
must fit fully inside it. The helper does not clip or clamp action rectangles.
Duplicate targets, malformed rectangles, overlapping dock controls,
overlapping cross-key rail controls and inconsistent rail boundaries fail.
Screen dimensions come from the
diagnostic response.

The caller remains responsible for owned-window admission, collector-idle
checks, destination verification, stable repeated target reads, pre-command
revalidation, one-shot input claims and acknowledgments. Geometry alone does
not establish absence of clipping or occlusion.

## Evidence

- Three regression cases failed against the preserved old helper in the initial
  16-test suite. Review then identified cross-key rail overlap; both overlapping
  and coincident rail subcases failed before the correction. All 21 final
  geometry tests pass.
- One bounded baseline Home → Tools → Home sequence passed on Family Area using
  the unchanged accepted APK. All exported library rectangles in that
  observation were fully onscreen; the original Notes failure was not reproduced.
- One distinct Home → Tools → Home sequence passed using the intermediate helper,
  followed by one final sequence after the reviewed rail correction.
  The final helper SHA-256 is
  `04def1f48ce53588516bb53feb1114cfc2d22de8df57580b37fa9d13cbc8b192`.
  It retained the original owner, destination, target-stability and transport
  guards. Home was restored and the performance collector was inactive.
- APK installations, setting edits, note edits, Living Area operations and phone
  operations were all zero.
- Accepted app source remains unchanged: 856 files, SHA-256
  `a5f279798bc368e9c402e7c01b3193b72f29a84e020e96eac4bc5064164772e5`.
- Evidence is stored locally under
  `build/fleet/diagnostics/native-tools-bounds-2026-10-12/` in the primary checkout.
  It exports fixed public selectors and rectangles, not stored values or tokens.

## Limits

This proves the selector's source-contract mismatch and its correction in the
specified regression cases. It does not prove that this mismatch caused the
previous Notes failure, and does not revalidate or unpark that candidate.
No device performance improvement, frame-rate result or website-speed result
is claimed. Earlier admitted trial helpers and evidence were not modified.

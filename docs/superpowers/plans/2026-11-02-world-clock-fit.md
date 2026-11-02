# World clock fit — bounded implementation

Objective: the whole-product goal at
`~/.codex/attachments/4832b00e-0492-44a6-a521-a970bf2ea968/goal-objective.md`.
Prior turn made verified progress: native 1.65 delivery and CI regression gates.
This issue's slot starts **06:27:26 UTC**, expires **06:57:26 UTC**.

Preserve the approved card, ordered timezones, time/date formatting and existing
active-page minute refresh. The current 100sp time line and oversized gaps put
the next city label below the initial viewport. The shared shell scrolls content,
so this is poor preview spacing, not missing stored cities.

Use a bounded body for this widget only. Keep time text at 48sp with 52sp line
height; city/day text uses 20sp/26sp, 2dp top spacing and 8dp row spacing.
A pure capacity policy uses actual SP-to-DP line measurements, reserves a
24sp overflow line plus 8dp gap when cities remain, and admits complete rows only.
Show at most four ordered cities; report all remaining saved cities in the footer.
Other widgets retain their existing scrolling shell. No setting/store/API changes.

- [x] Reproduce current native geometry; snapshot original settings and preserve APK.
- [x] Add failing capacity tests; implement the policy and focused UI integration.
- [x] Run required JVM suite and debug/optimized builds; independent review.
- [x] Verify default/large-font native geometry, complete labels and overflow;
      check two/four cities and a taller card if safely available.
- [x] Restore all test settings/layout, verify candidate identity/signature and
      Family acceptance; deploy accepted artifact to Living with exact readback.
- [x] Prepare focused changes and actual evidence for local commit; park
      unverified cases at the cap. The Git commit result is the commit proof.

Main owns source integration, builds and hardware. A disjoint sidecar reconciles
companion provenance; it does not touch native source or devices. No GitHub
authentication/hooks/publication. No zero-lag or universal coverage claims.

Final outcome: [delivery receipt](../../portal-world-clock-delivery-2026-11-02.md).
Normal two/three/four-city and 150% live/cold cases passed on Family. The first
candidate's large-font clipping was corrected and reverified within this slot.
Taller-card and other named hardware edge cases remain explicitly unverified;
the existing layout was preserved rather than resized for this unit.

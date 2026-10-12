# Browser Apps search card retention

Issue 36 started at 2026-10-12 09:24:01 UTC, with an unchanged deadline of
09:54:01 UTC. Family Area is the only deployment/test target. Living Area and
the Android phone remain frozen.

## Behavior

Typing a search that leaves the visible Apps results unchanged previously
rebuilt every app card, icon element and action listener. The existing image
pool prevented repeat downloads, but the DOM work remained.

The controller now retains the current visible list, bounded to 24 cards.
Reuse requires the same Portal/page generation, inventory reference, list mode,
operation capability and ordered visible app objects. Fresh inventory,
different results, pagination, Portal/navigation changes, hiding the page and
page exit invalidate the cache. Operation status still updates through the
existing per-card record callbacks.

There is no change to the visual layout, API/persistence, pairing, settings,
native selectors, CSS or core client.

## Measured work

Five keystrokes (`P`, `Po`, `Por`, `Port`, `Portal`) with 24 unchanged visible
results:

| Measure | Before | After |
|---|---:|---:|
| Elements created | 1,250 | 165 |
| Listeners registered | 165 | 45 |
| Original cards retained | 0 | 24 |

The reductions are 86.80% and 72.73%, respectively. Both the existing VM/DOM
adapter and an isolated native Chrome fixture at 402 × 874 reproduced these
counts. The native fixture decoded all 24 PNGs, kept search focus/value, and
verified the correct mocked launch command. It intercepted all requests,
used a new ephemeral profile and fixture-only tokens, and contacted no live
Portal. These counts do not establish a device latency or frame-rate result.

## Gates

- Before implementation, two of the four initial card-retention tests failed.
  The initial direct DOM-object assertion caused unbounded failure formatting;
  the owned test was stopped after 79 seconds and replaced with boolean
  identity assertions. The corrected baseline completed in 79 ms.
- Read-only review identified invalidation after image-pool revocation. A
  delayed-inventory hide/return test failed before that correction and passes
  after it. Final source review has no remaining actionable findings.
- Full browser gate: 113 tests passed, zero failures/cancellations/skips.
- Android gate: 1,885 tests / 221 suites, zero failures/errors/skips.
- All 63 offline deployment guard tests pass. Two pending-output tests were
  isolated in temporary directories after the build populated the real output
  folder; the production guards were unchanged.
- Candidate debug and benchmark builds, plus the bootstrap benchmark build,
  succeeded. Candidate archived before the bootstrap build, whose target is
  the candidate's exact SHA.
- Local admission verifies signatures, exact bundled assets, frozen source,
  build-role flags, installer target and profile inventories:
  8,667 methods / 2,576 classes, including 285 app methods / 75 app classes.

Frozen app source SHA-256:
`e5345d87ba006d3e82716710f21e06853bc5e780d500dd0dbd0b96a782da5d7e`
(857 files). Candidate APK: 25,446,910 bytes,
`e3dc68a12b7a360c67d138ebb3656a06c0bd8d451ddf0b0030ab5e57f4d844b7`.
Bootstrap: 25,446,910 bytes,
`a224360890d60f4cc58e76c6fe37649c19775e60bd17a7f224ad61e64d43270b`.

## Native deployment

The single bounded Family Area trial passed. Three destination checks proved
Home → Apps → Home in the observed application window. Exact SDK and bundled
installed profiles were verified. The installed APK SHA matches the candidate.
Live JS/core/CSS responses matched the admitted bytes and SHA-256 values:
151,466 / 19,089 / 22,706 bytes, respectively.

The trial performed two APK installations (bootstrap and candidate), with zero
setting/note/audio edits, zero timed captures or collector starts, and zero
Living Area or phone operations. One guarded initial Home normalization used
the exact owned Immortal system-Dream proof. Final Home readiness, configuration
and source invariance and collector-idle state all passed. No rollback was
needed; the candidate is retained. Individual app-method OAT compilation and
live browser interaction latency are not claimed.

No browser pairing credential was extracted or used. Existing admitted evidence
is preserved. The isolated Chrome fixture proves the card behavior; the device
trial proves navigation, installed profiles and exact deployed browser assets.

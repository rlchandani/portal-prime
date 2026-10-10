# Filmstrip visible-image preparation validation — 10 October 2026

The corrected Filmstrip candidate passes generated-image correctness checks and the native build gate. Its physical performance is not yet accepted, and it is not installed on either Portal at this checkpoint. Both Family Area and Living Area have been restored to the tested Apps notification-loading build, benchmark SHA-256 `08be8c74d15c585cd4d423baff6b6305615e6e8493671e0953ac70202cfff063`.

## Implementation and retained behavior

Filmstrip retains its twelve DOM slots, existing CSS, shadows, classes, backdrop cadence and navigation. Image preparation admits only cards whose conservative exposure envelope can intersect the display during a transition, including wraparound. Unique preparation URLs are loaded as one cancellable batch; prepared URLs/images are retained. The existing timer/ownership admission gate still controls commit, pause, resume and disposal. The lower-resolution backdrop remains separate.

Only the Filmstrip implementation changes. Other slideshow modes are byte-identical to the Apps baseline. In particular, the rejected Ken Burns retirement change from an ancestor of the research branch is excluded. The benchmark APK differs from that baseline in exactly one entry: `assets/screensaver/mode-impls.js`; DEX and native entries are unchanged.

Native WebView layout can expand from a small initial measurement or change orientation before the slideshow's delayed layout recovery replaces the scene. The initial candidate used the initial viewport width and could expose an unprepared card during this interval. The corrected implementation prepares the conservative envelope for either full-display orientation before declaring ready. Invalid, missing or inconsistent screen bounds select full preparation.

This bound assumes the installed WebView reports the physical display envelope accurately. A subsequent increase in logical display bounds caused by density, resolution or external-display changes is outside this fixed-display contract. Browser evidence does not establish the installed WebView's reporting or physical visual fidelity.

## Executed correctness checks

- The initial bounded regression run failed six intended contracts out of 22 and then passed 22/22 after implementation; no cancellation or timeout was accepted as a regression result.
- Three added regressions cover viewport expansion during initial decoding, expansion after ready plus orientation/next/previous, and unknown or inconsistent screen bounds. The earlier candidate failed all three; the corrected candidate passes 25/25.
- The broader Node run records 102 tests: 101 pass, one optional skip, zero failures or cancellations. Related slideshow engine/source/label checks are included.
- A fresh isolated, offline Chrome 155 generated-image matrix records 324 exact baseline checks and 324 exact candidate comparisons over fixed portrait/landscape layouts and JPEG, PNG and WebP fixtures, including alpha/focus markers.
- A second matrix covers expansion during decoding and after ready: 108 exact baseline checks and 108 exact candidate comparisons. There are no changed pixels in the corrected comparisons. The layout-recovery deadline is controlled so that the existing scene must remain correct before replacement.
- A negative control using the earlier candidate produces pixel differences in 36 of 108 resize comparisons, while its baseline controls remain exact. This establishes that the comparison detects the resize regression.
- Initial preparation calls decrease from 13 to 4 in the fixed-layout fixtures and from 13 to 8 in the expansion fixtures. These are decode-call counts, not a measured startup speed or memory improvement.
- Evidence-output tests verify exclusive private-file creation and refusal of overwrite/symlinks.

These checks sample software-rendered phases. They do not prove continuous animation, device GPU performance, actual touch latency, outgoing-scene dissolves or household-photo fidelity. Fixtures are generated; household image bytes, source settings and UI text are not exported.

## Native build and preserved artifacts

The corrected native check/build succeeds. The unchanged Kotlin unit task is UP-TO-DATE; its result files contain 1,463 tests and zero failures, errors or skips. This is verified cached native coverage, not a fresh execution of the Kotlin suite. The JavaScript tests and browser matrices above are fresh executions.

| Artifact | SHA-256 |
| --- | --- |
| Corrected mode asset | `2271b57c8c4cc809222a659a59bd5741192c63af7ee0ed89e48dc75cac632543` |
| Corrected browser probe | `6e62c5bb851647f1a29204d05a1baf63da842c2020a51a46f7fbf679ac6fb05a` |
| Corrected benchmark APK | `54ad8bb16c1082f2062467421c013d80409f4d722f8ca9cf1339d29d5148b45a` |
| Corrected debug APK | `8bfe21a7764caad7ba19e5d34f1c97f7a67ba045150bebaa3bca9560c90a9e46` |

Private immutable evidence is retained under the ignored `build/fleet/diagnostics/filmstrip-preparation-integration-2026-10-10/` directory. Its `corrected-native-build-proof.json` records the artifact hashes, exact comparison totals and lack of hardware acceptance.

## Withheld first device comparison

Candidate commit `c436d15` completed the first baseline/candidate round on both rooms: 16 individual captures, comprising four warmups and twelve timed entries. Capture ownership, decoded scheduler-promotion attribution, known-window checks, cleanup and complete configuration/schema preservation passed. Each entry included a 35-second normal playback hold.

Independent review then found the viewport-expansion bug. The performance comparison is withheld, not accepted. Both devices were restored to the baseline with collectors inactive and configuration/schema preserved. The second baseline deployment completed; no second baseline capture or second candidate deployment began. The old evidence root and cohort names must not be reused for the corrected candidate.

## Next acceptance gates

Run a newly frozen A1/B1/A2/B2 protocol on both rooms using the corrected artifact, with one warmup and three timed entries per room/cohort. Verify every installed APK identity, restore temporary mode changes, preserve source/settings in memory and stop only captures owned by this protocol. Compare the retained numeric measurements without treating unpaired household content as a causal experiment.

Window frame durations are not delivered FPS. Scheduler promotion is not physical first-photo presentation. Startup spans overlap, stack samples are not CPU time, and application memory excludes a separate Chromium renderer. These limits must remain explicit in any performance conclusion.

The broader performance goal remains active: Home/Apps smoothness, settings and swipes, actual touch/tab readiness, browser/website readiness, first usable photo/document, pinned-app lifetime, cold behavior, prolonged uptime/memory and complete phone/web validation require further device evidence. See [the Apps comparison](portal-apps-badge-activation-validation.md) for the current installed navigation change.

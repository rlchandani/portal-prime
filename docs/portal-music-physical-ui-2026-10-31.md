# Family Area native music UI verification

The silent native music components passed **223/223 assertions** on Family Area's
actual Android 10 / API 29 Portal+, at 2160 × 1440 and its measured 180 dpi density.
This verifies UI behavior; audio/provider setup remains explicitly deferred.

**November 1 follow-on:** the [native slider touch fix](portal-music-touch-native-2026-11-01.md)
changes the inner gesture area from 18 to 54 px while preserving the small visible
thumb. Previously ignored edge taps improve **0/16 → 16/16**, and **112/112**
focused checks pass. It also verifies the queue's correct non-tab native
checkable/checked state. The historical run and qualifications below remain
scoped to its original artifact; the follow-on closes those sampled interaction
gaps, with dragging, real audio and panel FPS still outside acceptance.

**Subsequent drag verification:** [429 focused checks](portal-music-drag-native-2026-11-01.md)
pass across 20 native gestures. Both themes and font scales 1.0/1.8 cover forward
and reverse paused seeking/volume, plus disabled controls. Native values progress
during the drag and each enabled gesture emits one UI action after release.
This closes those sampled dragging cases; playing-timer interaction, real audio
and panel FPS remain outside that gate.

| Gate | Passed | Native captures | Elapsed |
| --- | ---: | ---: | ---: |
| Six-case dark/light, ready/offline and 1.8 font matrix | 206/206 | 50 | 112.024 s |
| Native dark-theme search and collection navigation | 17/17 | 9 | 22.169 s |

Transport, paused seeking, queue selection, player retention, full/compact
continuity, playlist and album navigation, unavailable controls and larger text
were exercised through observed native bounds. Search entered `Slow`, filtered
the supplied playlists, and opened a collection and track. Light-theme search
was not separately re-exercised. These are silent fixtures using the real native
components, not a live music provider.

Eight representative scenarios were visually reviewed: dark and light full
player, selected queue, playlists, larger text, compact, Players and offline.
No visible overlap was observed in those samples; all 59 captures were not
individually reviewed. Ten sanitized captures are available in the
[physical review](../mocks/portal-premium-player-20261030/family-music-review.html).
The [receipt](../mocks/portal-premium-player-20261030/evidence/family-music-native/checks.json)
records the scope, assertions and restoration.

## Implementation and restoration

Product source remained at `cc8bc9de`. This issue added a physical test helper
and a test-only native snapshot `selected` field; it did not change the
production UI or produce a new performance improvement. The Android test APK
built successfully. The helper checks the exact device, APK, density, current
window, fresh native bounds and command budget before input. Its private raw
evidence is kept outside Git; the public review contains synthetic artwork and
metadata only.

Earlier assertion failures exposed test assumptions: API 29 tab enablement is
owned by the parent node, the queue row reports `selected=false` despite its
visible current-row treatment, and enlarged text does not grow every line's
bounds by an identical ratio. The final receipt retains the false queue
selection flag and separately verifies the current paused marker. It does not
claim that accessibility flag was fixed.

The original optimized APK `199aa8a1…` and original test APK were restored.
All **97 settings and 22 photo-source fields** matched their original values.
The original Dream was restored, the owned observer stopped, its cache files
removed, and the performance collector had zero active listeners.

## Remaining limits

This run does not accept live audio, provider authentication, speaker-group
commands, actual photo integration, slider touch-envelope geometry, panel FPS
or natural room-light comfort. Photos uses a test placeholder; the preview
header/debug badge is test chrome. Home supplies the normal app header and dock.
The larger performance and slideshow goal remains incomplete.

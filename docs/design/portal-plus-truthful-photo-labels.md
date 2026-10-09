# Truthful slideshow labels — 9 October 2026

## Cause and change

Polaroid selected a caption at random from a hard-coded list, including “the trip”,
“home” and “summer '24”. Memories similarly selected invented headings/subtitles
such as “This Weekend / recently added” and “On Our Travels / places you've been”.
Neither renderer used photo metadata for those claims.

Polaroid now shows its photo composition without fabricated caption text. Memories
uses the neutral heading “Photo memories” with no event/date/travel subtitle.
The existing paper layout, photo selection, animation and timer cleanup are preserved.
The legacy source bridge has no verified per-photo caption field, so absence of
metadata stays absence of a caption.

The separate native PhotoCaption path is unchanged: it reads a photo's EXIF date/GPS
where available and hides missing information. Metadata is evidence supplied by the
file; it does not justify inventing a semantic event such as a trip. No AI classifier,
caption generation, cloud upload or new work in the slideshow frame loop was added.

## Validation

- The new deterministic tests run the actual `modes.js` and `mode-impls.js`, including
  later scheduled Polaroid drops and Memories collages. Both failed on the old code
  and pass with the fix. Cleanup leaves no owned mode timers queued.
- All seven JavaScript source/label checks pass, plus the existing deduplication check.
- All 858 JVM unit tests pass; debug app and Android test APK builds succeed.
- An isolated real Chrome renderer at 2160 × 1440, using generated fixture images,
  verifies six Polaroid cards with no captions and three Memories collage images
  with only the neutral heading. This is a rendering check, not Portal FPS evidence.
- The signed app APK SHA-256 is
  `c1ecb4ccefbeaf93346e21e6bb20ab9f1e664b519964fdb6fd7ed7348f60c520`.
  Both Family Area and Living Area deployments verified the installed APK bytes.
- On the physical Portals, fresh preview sessions reported 12 photos ready:

| Room | Polaroid startup | Memories startup | Restored Shuffle startup |
| --- | ---: | ---: | ---: |
| Family Area | 694 ms | 148 ms | 337 ms |
| Living Area | 705 ms | 292 ms | 462 ms |

These are logged scene-readiness times, not animation/compositor FPS or a controlled
performance comparison. No household photo content was downloaded. Full screensaver
settings snapshots match the originals after restoring each room's saved Shuffle
mode and 30-second interval. Current-process short logs contain no fatal/ANR/OOM
markers; that check does not replace a long stability test.

The rollout also brings Living Area up to the previously validated WebView recovery,
bounded derivative preparation and retained Apps navigation fixes. Broader measured
performance work remains active. The experimental desktop Filmstrip compositor
prototype is not included in this build.

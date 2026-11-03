# Bundled sound player cleanup — 2026-11-03

The hourly chime, timer ring, room ping and golden-hour tone now attach each
`MediaPlayer` to the existing generic `OneShotSpeechOwner` before setup. Missing
assets and setup exceptions release the player; completion/error callbacks share
that same exactly-once terminal path. Cleanup waits until active setup/start exits.
Successful playback keeps ownership until a terminal callback.

The original actual-method regression suite reported **12 methods, 10 assertion
failures, zero errors/skips**. Its fully executed matrices reproduced **35 missing
release cases and eight duplicate-release cases**. All twelve unchanged methods
now pass. Mute, original resource IDs, configured volume, timer default volume and
queued **1,400 ms** intervals retain their passing controls. The complete gate
passed **4,150 JVM tests / 432 suites**, with zero failures/errors/
skips, plus debug/optimized APKs, R8 and vital lint in **1m 57s**. Independent
source/test review found no blocking issue.

Exact signed optimized **1.107/code101** is installed and full APK hash verified
in Family Area and Living Area. Candidate SHA-256:
`9438d6b056ffbba353acde8f1018c17a38767645c7b8e3b72e6545e278106703` (25,862,702 bytes).
Debug and rollback hashes/signers are retained in the structured proof.
Complete settings/profile/layout/source/screensaver maps and Home/timer/Sunrise/
Chime preference bytes match their baseline. Family current night-clock frame was
visually inspected; font, accessibility services, original Chrome task, resumed
frame host and awake display are retained. Original registry hashes match and the
owned copied registry is removed.

The tests use inert process-local player/resource/context/handler doubles. Native
`MediaPlayer`, real sound/audibility, framework callback-thread behavior and a
physical resource census remain **unverified**. No real sound was triggered by this
verification. API28 and Living visual acceptance remain open. This does not change
alarm scheduling, quiet policy, TTS, audio quality or claim a performance gain.

Original parallel discovery **06:27:46–06:57:46 UTC** includes this unit; its clock
was not reset after the file-read unit. Public manifest remains **1.83/code77**;
version-sync guard rejects the local mismatch. No phone/HA/GitHub auth/push/
publication. The whole-product goal remains active. Roadmap now tracks **81 tasks:
48 delivered, 23 locally verified, 3 approval, 6 parked, 1 queued**; earlier records,
default Weather selection and other boundaries are retained.

Structured proof:
`mocks/portal-widget-gallery-20261102/evidence/chime-player-cleanup-verification.json`.

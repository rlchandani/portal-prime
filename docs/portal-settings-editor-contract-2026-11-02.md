# Native settings editor contract — 2026-11-02

Backlog item 3, based on `4b701641`, in the existing isolated
`codex/portal-hi-widget-fixes-20261102` branch. Bounded slot:
**06:56:24–07:26:24 UTC**, including cleanup.

The obsolete test prohibited inline text settings because its comment said the
native renderer emitted no control. The existing renderer already used
`ApprovedStringSetting`. The replacement makes editor selection a pure metadata
policy used by the real renderer and checks the actual registered domains,
including Fleet name and dashboard URL.

Existing components, saved values, validation, side effects, visibility,
secret handling, rich navigation and the untouched-Fleet-name draft rule are
preserved. This is maintenance and regression protection; no new user-facing
setting or visual redesign.

## Verification

Nine focused tests exercise editor kinds, integer/time precedence, both text
entry kinds, navigation metadata, registered fields and derived-field projection.
Metadata fixtures throw if the policy reads their supplied getters/setters.

| Check | Observed result |
| --- | --- |
| Initial unimplemented policy | 9 tests, 9 assertion failures; compilation succeeded. This is a functional red, not a reproduced production UI bug. |
| Correct policy | 9 tests, zero failures/errors/skips. |
| Deliberate inline-text-to-hidden mutation | 9 tests, 4 assertion failures; compilation succeeded. The correct source was restored before the final gates. |
| Final full JVM gate | 3,796 tests / 398 suites; zero failures/errors/skips. |
| Debug and optimized APK gates | Combined command exited 0 in 3m58s; both APKs, R8 and performance vital lint passed. |
| Version/signing checks | Gradle and manifest agree on 1.66/code 60; optimized signature verified against the accepted local signing identity. |
| Independent source review | No concrete production regression; coverage wording narrowed to classification/projection. |

Private run logs live in `/private/tmp/portal-settings-editor-20261102/`.
The mutation was never built into or installed as an APK.

The final command was `:app:testDebugUnitTest :app:assembleDebug
:app:assemblePerformance --no-daemon --console=plain --max-workers=1`, with the
Kotlin compiler in process and a 4 GiB Gradle heap. `bash
scripts/check-version-sync.sh` passed.

| Local artifact | Bytes | SHA-256 |
| --- | --- | --- |
| Debug | 51,265,262 | `7e880e1bdde3ae7b9cc728363ef1697b3ea11ee5d2a120697e6c6b5d7cb78eca` |
| Optimized | 25,860,666 | `53b236e780e88d0eaa4f5539645a284f618abf9911d986eb0665555fda4d652d` |

Both use `com.immortal.launcher.debug`, 1.66/code 60. Optimized signer SHA-256:
`4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6`.
These are local maintenance builds; the installed World Clock artifact has the
different `c38b048a…` hash recorded in its delivery receipt.

## Acceptance limits and delivery

These tests do not compose `SettingControl`. Removing the body of a renderer
branch without changing its classification would not be caught by these nine
tests. Source review checks the current wiring; automated Compose interaction,
secret masking, worker execution and live dialog behavior remain separate work.
After review, three test names and the plan were narrowed to state this scope.
Those name-only changes are outside the review's recorded test-file hash; the
final test source SHA-256 is
`1b01aac840baa2666fba0eb47721e7b3579baefe161179cb3491228955acc0f0`.
The production policy and renderer still match the reviewed hashes.

No device configuration or APK installation has been performed for this
maintenance increment. Both rooms retain the previously verified
**1.66 / versionCode 60** World Clock artifact. There is no public release,
GitHub authentication, push or Home Assistant service deployment.

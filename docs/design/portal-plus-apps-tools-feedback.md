# Portal+ Apps, Tools and Google Search feedback

Validated on the connected Portal+ (Android 10 / API 29, 2160 × 1440,
180 dpi) on branch `codex/portal-plus-approved-ux`, October 5, 2026.

## Resulting behavior

- Apps uses each installed app's original artwork without Immortal's additional
  card, colored icon plate or Home icon replacement. Built-in app entries use
  plain glyphs in the launcher text palette. An app's own image can contain its
  own background. Home favorites and Store keep their existing presentation.
- Home and Apps share the complete cached installed-app inventory. Concurrent
  cold requests join one package/icon scan; package invalidations remain
  independent of that scan. Installed apps, folders and built-ins appear
  together. Recents queries run only when Recents is requested, outside the
  All apps opening path.
- Loading, error and empty-result messages use the data source actually being
  displayed. A nonblank query searches the installed-app inventory, including
  when entered from Recents.
- All twelve Tools entries use one in-tab workspace: fixed Tools return,
  one title and a bounded body. List scroll position survives opening and
  closing a tool. Form Cancel returns to the Tools list. Speed test remains
  Advanced-only.
- Cameras edits its saved-camera list and add form within Tools; View opens
  the existing native full-screen stream. Lamp edits brightness, warmth and
  red mode within Tools; Preview opens the full-screen lamp. The Android
  wrappers remain available to existing callers.
- Home saves the selected tab in its Activity state bundle. Tool route state
  and the containing tab restore together; a new explicit navigation intent
  overrides the restored tab.
- Both Google Search packages are excluded by the shared curation policy:
  `com.android.quicksearchbox` and
  `com.google.android.googlequicksearchbox`. This removes their entries from
  Home, Apps, app folders, Recents, remote app lists and app selectors. Their
  curated folder, label and icon mappings have been removed. Internal Apps
  and Settings search continues to work.
- The startup picker excludes these retired packages while retaining its other
  system-app choices. `BootLaunch.packages` also excludes them when reading old
  saved selections, so they cannot run after reboot. Reading leaves the original
  startup file untouched.

## Verification and artifact boundaries

The final debug APK SHA-256 is:

`966d71764f5ead17af3d25b478bdbcc9450add7193869efb332ca85faacbb912`

It was installed with `adb install -r`; the installed package's APK was read
independently and its hash matched. The existing debug package and its data
were retained.

| Gate | Observed result |
| --- | --- |
| `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest --no-daemon` | Build succeeds; 521 unit tests, zero failures/errors/skips |
| Shared inventory concurrency regression | Fails before the fix, passes after; one scan and the same published snapshot for concurrent Home/Apps requests |
| Google Search curation regression | Fails before removal, passes after; both package IDs hidden across device IDs and labels; browsers and Gmail remain available |
| Legacy startup regression | Both tests fail before the read-boundary fix and pass after; old Google selections are ignored without rewriting the file, and boot does not resolve them while continuing to resolve Browser |
| Browser navigation projection | 143 checks pass; zero browser errors; includes original 101 navigation/widget/photo checks, Apps artwork, all Tools routes and Google Search/internal-search checks |
| Native navigation sweep | 95 assertions pass, covering all twelve tool routes, common workspace title/Back, retained scroll, Camera/Countdown Cancel, Lamp Preview return, app long press, revisits and Google Search absence |
| Final APK Apps/startup follow-up | 33 assertions pass, covering search from Recents, full inventory on revisits, same launcher window, Google Search absence, Advanced consent, eight startup-picker viewports, retained Browser choice and preservation of all nine configuration files plus the startup file |
| Native launcher recreation | Wrong return to Home reproduced before the saved-tab fix; final APK retains Lamp controls after active recreation and while Preview is foreground; its numeric popup sets brightness to 70%, red mode hides Warmth, and both preview values return to the restored Tools workspace |

The 95-assertion sweep ran on debug APK
`4d09ed13391b69b7feef0bb8227d2deee83fb58d0b2c709160840a4dc0766be9`.
The final APK adds the subsequently reviewed Apps
loading/error/empty-result predicates and retirement handling for the startup
picker and saved startup list. Tools, artwork and tab-navigation
implementation were unchanged. Apps and launcher recreation were checked again
on the final installed artifact. Private local evidence retains the logs,
source fingerprints, result lists and device captures. Native observers exited
with instrumentation code 0.

Advanced was temporarily enabled through its real consent UI to exercise Speed
test and the startup picker, then restored. The original widget, countdown, alarm, camera, timer,
timer-list, layout, settings and notes preference files match their original
bytes. No camera, alarm, note or timer was saved during the sweep.

Independent static review found and resolved the missing containing-tab
restoration, premature empty-state conditions and legacy Google startup
selections. Its final focused review
reported no remaining actionable P1/P2 findings.

## Limits

The browser remains a sample projection, not a live Portal or emulator; sample
glyphs are labeled and supplied artwork is preserved. Frozen sign-off mocks
were not edited.

The sweep verifies navigation, controls and return paths. Live RTSP streams,
intercom audio, TTS, recording and bandwidth transfers were not exercised.
These results do not establish final frame-time acceptance, exhaustive Settings
acceptance, or an Apple-equivalent performance claim. The broader quality gates
remain in [portal-plus-quality-gates.md](portal-plus-quality-gates.md).

The installed APK also includes the pre-existing uncommitted `TvFocus.kt`
candidate in this working tree. This feedback's commit leaves that candidate
and the earlier audit documents for their separate performance work.

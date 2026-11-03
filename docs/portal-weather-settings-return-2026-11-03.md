# Installed Weather settings return — 3 November 2026

Weather scope approval is recorded. The separate adaptive-layout candidate remains
held on `codex/weather-native-refinement-20261102`: its original native acceptance
found clipped daily lows. This check did not reopen that geometry issue or install
the candidate.

This bounded check used Family Area's existing optimized **1.88/code82** over
wireless ADB. Its full installed APK SHA-256 was verified before and after:
`2b23a0f807ba2be739bf547a0ebd97fee573bf63f66f97b388832b68b7f45b9e`.
Source delivery is `23eb995d`; this follow-up changes only acceptance records.
The unit started at **00:07:45UTC**, with a **00:37:45UTC** deadline including
cleanup. Device work finished early; no investigation clock was reset.

## Observed results

| Actual native interaction | Result |
|---|---|
| Dismiss the existing photo frame to Home | Home appeared. |
| Weather Open → Weather settings → selector | Existing Off/Hourly/Daily options appeared. |
| Select Hourly | Complete profile readback confirmed `immortal.weatherWidget=hourly`. |
| Return to Home | Current conditions and daily H/L appeared, but no hourly labels were visible. Vertical swipe probes did not establish a visible forecast row. |
| Home gear → Appearance → App appearance → Clock | Auto/12-hour/24-hour selector appeared. |
| Select 24-hour | Complete profile readback confirmed `immortal.clockFormat=24`. |
| Return to Home | The same Home activity record `de592c1`, task27218, returned; Home showed16:13 and Today showed sunrise06:55/sunset16:49. |
| Apps → Home | Both pages appeared in the same Home host. The hourly row remained absent; later Weather Open tap probes did not navigate. No cause or general input-failure claim is inferred. |

These are coordinator-observed interactions on the installed APK, not automated
Compose tests. The 24-hour Home/solar return is observed. **Retained hourly
12→24→12 acceptance remains incomplete**: neither a populated hourly row nor its
format transition was established. The separate current-header/solar observation
does not satisfy that criterion.

An independent source review found no deterministic Weather-only resume skip on
the shared selected widget page. Source permits populated daily data with an empty
hour list; an old mode or visibility/input issue was not ruled out. A host
Open-Meteo request returned168 hourly entries for2–8November, but that different
request does not establish the device's retained data, forecast object or request
count. No speculative production patch was made.

## Restoration and limits

Only the clock format and forecast mode changed temporarily. Both were restored
through the declarative registry to the original Auto/Off. Final complete settings
profile, layout, sources and screensaver configuration exactly match baseline.
Font scale and enabled accessibility service list match; the installed APK is
unchanged. Original Chrome task27174 remains listed. The original manual photo
frame is foreground and its ordinary photo/clock presentation was visually checked.

No app installation, instrumentation, UiAutomation, accessibility reconnect,
account operation, provider setup, Home Assistant change or Living Area operation
was performed. A direct shell start of the non-exported photo preview was rejected;
the existing paired screensaver action restored it successfully.

No new build/test-suite run, speedup, FPS, zero-request, retained forecast-object,
full two-way hourly-format, font/theme, daily-geometry or whole-product acceptance
is claimed. The public image is a Weather-only crop; personal photo captures and
configuration remain private. Parked issues retain their original boundaries.

Structured evidence:
`mocks/portal-widget-gallery-20261102/evidence/weather-settings-return-verification.json`.

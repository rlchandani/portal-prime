# Native Weather refinement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for native integration; one disjoint policy/test sidecar is authorized. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the approved Weather layout and prove its quality on Family.

**Architecture:** Keep the existing background current/forecast producers keyed only by temperature unit. A pure layout policy chooses compact/medium/large content from actual constraints and font scale; the Compose renderer presents it using the existing palette. Saved identities and placements remain authoritative.

**Tech Stack:** Kotlin, Compose, JVM tests, existing Fleet settings/layout APIs, API29 Family.

**Spec:** `mocks/portal-widget-gallery-20261102/weather-review.html` and its linked Portal variants; user approved this exact scope on2November2026.

## Global Constraints

- Preserve automatic location; no manual picker, new data provider, Google service or HA music deployment.
- Current conditions, hourly/daily/off, missing forecast, initial loading and unavailable states are in scope.
- No added network requests or producer restart from resize; retain inactive-page pause.
- Native visual and performance acceptance on Family before final artifact to Living.
- Normal and150% text, both themes; restore whole settings/layout/font-scale baseline.
- Original30-minute unit includes cleanup, deadline15:35:14UTC. Parked issues stay parked.
- No GitHub authentication/hooks/push/publication. Fresh branch starts at48d712c3.

## Review Focus

- Very narrow/short saved sizes: preserve settings action and legible current state; compact hides forecast.
-150% text: reduce item density and allow contained body scroll rather than clip controls.
- Unknown weather code: truthful unavailable condition; never fabricate a specific condition.
- Missing selected forecast: preserve current temperature and show explicit forecast unavailable.
- Resizing/mode/clock-format return: same data owner; labels update from retained data.

### Task1: pure adaptive policy (disjoint sidecar)

**Files:** Create `app/src/main/java/com/immortal/launcher/WeatherWidgetLayout.kt`; test `app/src/test/java/com/immortal/launcher/WeatherWidgetLayoutTest.kt`.

**Interfaces:** `WeatherWidgetSize { COMPACT, MEDIUM, LARGE }`; `WeatherWidgetLayout(size, forecastItems, paddingDp)`; `weatherWidgetLayout(widthDp:Float,heightDp:Float,fontScale:Float)`; `weatherWidgetCondition(code:Int):String`.

- [x] Write tests for compact below320dp wide or300dp/fontScale high; large at440dp wide and390dp/fontScale high; remaining medium. Counts0/3/5; padding16/22/22dp. Clamp invalid/nonfinite dimensions or font scales conservatively to compact.
- [x] Watch focused tests fail before production implementation. Expected: unresolved policy symbols.
- [x] Implement policy and full WMO code labels, unknown →“Condition unavailable”.
- [x] Run focused JVM tests; expected all pass. Parent integrates without redoing sidecar.

### Task2: Compose presentation and native acceptance (main)

**Files:** Modify `ApprovedHomeWeather.kt`, version/build metadata and local receipts/roadmap only as warranted.

**Consumes:** Task1 interface; existing Weather.Current/Forecast and hourLabels(use24Hour).

- [x] Capture native1.77 baseline, original complete profile/layout/screensaver/font scale, screenshot and bounded gfx/memory scenario.
- [x] Render quiet Weather header/right glyph, readable temperature, condition, city/high-low,0/3/5 forecast cells and full-width Weather settings action. Existing palette/font; no timed animation.
- [x] Separate initial current loading from completed unavailable result; selected missing forecast remains explicit. Keep resize outside data producer keys.
- [x] Run focused/full JVM, debug/optimized/R8/lint/version/signer gates and independent source review. Expected green exact artifact.
- [ ] Verify resize/modes/settings return/both themes/150% native UI and compare equivalent gfx/memory/request behavior; expected no material performance regression or clipped action.
- [ ] Restore baseline; verify full hashes/config, deploy exact accepted artifact to Living if native acceptance passes; otherwise retain candidate and report exact incomplete scope. Commit verified work and close original unit within deadline.

**Self-review:** Approved scope covered; manual location and unsupported fields excluded. Layout policy is presentation-only and no prefs/API ownership change is needed. Fresh code review and final state restoration are mandatory. Deployment requires actual acceptance, not screenshots of the mock.

## Unit outcome

Original slot closed without release acceptance.3882 tests/407 suites and both builds/R8/vital lint passed. Native hourly is clean; daily lows still clip, large placement and150% text remain unaccepted. Exact1.77 APK and all temporary config were restored; Living unchanged. See the native receipt. No deadline restart.

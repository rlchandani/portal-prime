# Immortal: premium Portal UX study

Research date: October 4, 2026. Prepared for the current Immortal source tree and the connected Portal+. This is a design recommendation and interactive browser prototype, awaiting the owner’s choice before Android implementation.

## Recommendation

Keep the familiar iPhone settings language: grouped rows, trailing values, switches, disclosure arrows, concise explanations, and a clear way back. Use an iPad-like sidebar and detail pane on the Portal’s wide display. Improve the home with a deliberately limited set of useful widgets, favorite apps, and consistent navigation.

The recommended combination is **Everyday home + the shared grouped settings design**. Gallery is a strong alternative if photography is the primary purpose. Control fits a kitchen or a dedicated smart-home station. “Premium” should mean clear hierarchy, predictable behavior, comfortable type, restrained materials, and polished states as well as attractive screenshots.

A literal phone layout is a poor use of a landscape Portal. Material’s adaptive list-detail structure is useful underneath; Apple’s recognizable control language can remain on the surface. These approaches are compatible. There is no research evidence here that replacing every existing iPhone-style control would improve this product.

The recommendation assumes a balanced home because no priority choice was received during the research. All three directions remain available to compare.

## What the source and device establish

The project is a single Kotlin/Jetpack Compose Android application, with a launcher, photo screensaver, tools, curated app store, phone remote, and fleet agent. Existing integrations are a substantial strength. The redesign should organize them rather than replace them.

The inspected Portal+ is running Android 10 / API 29 with a physical 2160 × 1440 display. The installed debug app is version 1.64, version code 58. USB debugging was available during the earlier device inspection; the photo screensaver and local phone-remote HTTP endpoint were running. No device configuration was changed. These facts do not establish that every integration is configured, or that every interaction works on every Portal model.

**Verified source observations:**

- `HomeActivity.kt` uses an independently scrolling widget column and app grid with approximately 38% / 62% width. Its comment explicitly describes a single app grid with no dock.
- Editing can start through gestures. The home explicitly has no Done button; tapping empty space exits editing. This is a discoverability issue to investigate, not an observed user-test failure.
- `ImmortalSettingsActivity.kt` has 22 sidebar destinations across five groups. The existing grouping and search are valuable; the improvement is a smaller, more task-oriented first level with precise search destinations.
- `SettingsComponents.kt` and `SettingsRenderer.kt` already centralize many row patterns. The declarative registry in `settings/SettingsDomains.kt` serves persistence, on-device settings, and the phone remote.
- There are ten settings registry domains. The proposed eight navigation categories are **presentation groups**, not a proposal to replace those ten domains.
- The three Inter font XML resources ask the `com.google.android.gms.fonts` provider for Inter. Portal firmware lacks Google services. Bundle licensed font files for a predictable native result; the source alone does not prove which fallback font is currently displayed.
- The existing glass component uses tints and borders. Continuous live blur should not be required to reproduce the browser study on Android 9/10.
- Home Assistant’s control widget uses `HaClient` REST calls and reconciliation. MQTT publishing is a separate integration. Show their statuses separately rather than imply one connection enables both.

The earlier project inspection passed 408 JVM tests. That is useful baseline evidence about current logic; it is not visual acceptance or a usability study. The browser mock runs separately and does not exercise Android permissions, an APK installer, real photo credentials, or real household devices.

## Research findings and their design consequences

### 1. Use Apple familiarity with a landscape structure

Apple’s Settings guidance recommends good defaults, fewer settings, and placing frequently used task options near the task. Its Layout guidance emphasizes clear hierarchy, alignment, and adaptation to available space. Apple’s Sidebars guidance also acknowledges their space cost. Android’s canonical list-detail layout provides a concrete large-screen pattern with compact back-stack behavior.

**Apply to Immortal:** retain the recognizable rows; show eight top-level categories on wide screens; keep the selected detail pane visible; remember the last category. On compact native screens, show a category list followed by a detail screen with Back. The browser’s narrow view uses horizontal category navigation to make this study easy to explore; that is not the final native navigation specification.

**Avoid:** shrinking a portrait iPhone settings screenshot until it fits the Portal, hiding every frequent setting in Advanced, or treating the phone remote as a pixel-for-pixel Portal clone.

### 2. Make home a useful starting point

NN/g distinguishes an at-a-glance dashboard from a portal or gateway. Its dashboard guidance stresses focused information, position, size, and grouping. Immortal serves both roles: household information and launching apps. It needs a hierarchy that makes both understandable.

**Apply:** time and date are immediately recognizable; the next event is concise; photography has a clear destination; six proposed favorites are separate from the full app library. Home, Apps, Photos, and Tools form a stable navigation layer, with Settings consistently available.

**Tradeoff:** adding a dock and separate favorites changes the launcher model and adds persistence work. Six favorites and four navigation destinations are design hypotheses, not numbers prescribed by the research. A smaller Portal or TV remote may need fewer visible favorites.

### 3. Design for an arm’s length and across the room

Android Compose recommends a minimum 48dp interactive target and whole-row toggle behavior. Amazon’s smart-display guidance recommends 68dp targets for hubs at arm’s length, versus 48dp for handheld devices. Its style guidance also distinguishes viewing distance, device density, and scalable layout.

**Apply:** target approximately 56–68dp for primary native controls, retain a 48dp floor, and use generous whole-row settings targets. Make time and primary status readable from across the room. Verify the exact sizes on the real device.

**Important distinction:** 2160 × 1440 physical pixels, browser CSS pixels, Android dp, and font sp are different units. The browser study is 1440 × 960 logical pixels at a 3:2 ratio; it is not a claim that one CSS pixel equals one Android dp. Amazon’s numbers are useful reference guidance, not a Meta Portal requirement. Its recommendation of only a few targets applies to skill scenes and should not be transplanted literally into an app launcher.

### 4. Reserve glass for the functional layer

Apple’s current Materials guidance places Liquid Glass in a distinct functional layer: navigation, toolbars, and controls. It advises against spreading it through content surfaces, and calls for sufficient backing or dimming over rich imagery.

**Apply:** lightly translucent navigation and transient controls; stable opaque settings and reading cards; limited shadows, a small corner-radius family, and one purposeful accent per context. The photo frame can be visually rich while settings remain quiet.

**Native consequence:** use inexpensive tint, borders, gradients, and precomputed scrims on API 28/29. The browser’s isolated backdrop blur demonstrates the visual intent. It is not a performance requirement. Continuous GPU blur is not worth making a discontinued device feel sluggish.

### 5. Prefer visible actions and recognition

NN/g’s recognition guidance favors visible choices and cues over recall. Its progressive-disclosure guidance keeps common actions in view while moving rare complexity one level deeper.

**Apply:** Customize on home, a visible Done in editing, explicit Move and Remove alternatives, labeled folders, named connection states, and Preview near appearance choices. Keep long press and dragging as shortcuts, not the only route.

**Settings search:** results should identify the setting and its category, open the correct category, and highlight the row. This study implements that behavior. Do not force the user to search a long panel again after selecting a search result.

### 6. Polish empty, pending, and failed states

Apple’s Feedback guidance places status near the affected object and reserves alerts for important situations. Search guidance supports clear scope and suggestions that reduce typing.

**Apply:** preserve usable cached photos and weather when offline; label the weather’s saved update time; disable store downloads when unavailable; explain why a smart-home action failed. Empty calendar and music cards offer a relevant next step. Source setup has connection details, validation, album selection, and a final confirmation.

**Native requirement:** a smart-home command should have pending, confirmed, and failed states. Existing optimistic HA control needs rollback or reconciliation to actual state. The mock toggles sample state immediately and does not simulate every network race.

### 7. Accessibility is part of the premium experience

Apple emphasizes legibility, alternatives to gestures, and accommodating accessibility preferences. WCAG 2.2 contrast guidance specifies 4.5:1 for normal text and 3:1 for qualifying large text. WCAG’s 24 CSS-pixel target criterion is a web conformance floor with exceptions; it is not the preferred target for this display.

**Apply:** labeled controls, strong visible focus, whole-row switches, keyboard-accessible dialogs, touch alternatives to drag, light and dark settings, restrained motion, and useful empty/error messages.

The proposed 150–220ms transitions are a product starting point, not a timing standard. Honor reduced motion in native Compose. Browser support and sampled contrast calculations do not certify accessibility; TalkBack, focus order, scaling, and all image-overlay combinations require native acceptance.

### 8. Keep spatial organization stable

Home Assistant’s Sections layout groups cards in a grid and supports contextual visibility. Its dense placement options highlight a tradeoff: efficient packing can disrupt spatial expectations.

**Apply:** predictable grid slots and explicit editing. Avoid automatic masonry rearrangement whenever a card disappears. Hide unavailable controls deliberately, and preserve useful cached content during outages.

## Three home directions

| Direction | Visual structure | Best use | Principal tradeoff |
| --- | --- | --- | --- |
| **A · Everyday — recommended** | Scenic photo hero, four compact information/control cards, six favorites, floating navigation | A shared family display with mixed daily tasks | Requires a favorites model and thoughtful widget sizing |
| **B · Gallery** | Full-screen photography, large time/date, one upcoming event, four favorites | A living-room photo frame that sometimes launches apps | Less information immediately visible; image legibility needs scrims |
| **C · Control** | Room controls, music, concise agenda, supporting photo, favorites | Kitchen or a home-automation station | Adds room grouping and should only dominate when integrations are configured |

All three share the same settings, source setup, apps, store, and editing patterns. They are alternative priorities, not three themes that every installation must ship. The browser comparison intentionally uses the same sample data to make hierarchy differences easier to judge.

## Settings navigation: preserve the existing features

| Proposed category | Current destinations preserved | Added or reorganized content |
| --- | --- | --- |
| Appearance | Display, Wallpaper, Appearance | Preview and consistent light/dark presentation |
| Photos & screensaver | Screensaver, Clock Face | Source status, source setup, albums, advanced photo options |
| Home & widgets | Weather, Calendar, World Clock, Countdown, Quick Buttons | Customize home and favorites |
| Sound & wake | Chime, Sunrise & Wake, Multi-room Audio, Welcome Greeting, Touch Sounds | Clear status and related controls together |
| Connections | Home Assistant, Phone Remote | Separate HA control / MQTT publishing status; links to media setup |
| Privacy & accessibility | Existing system accessibility remains reachable through Android settings | Proposed larger text, reduced motion, and calendar privacy; shared-album choices |
| Device | Sleep Timer, Start on Boot, Device Name, About & Device Health | Update status beside version |
| Advanced | All Android Settings | Specialist fleet and diagnostic entry points |

This accounts for all 22 current sidebar destinations. Cross-links such as photo services in Connections should lead to the same source setup, not duplicate its state or credentials. Some items in the prototype are proposed additions, and several destinations preview navigation with a dialog rather than reproduce their full existing editors.

The registry remains the source of truth. Bind rows to existing specs, respect constraints and `onApplied` hooks, and use `NavSpec` plus dedicated Activities for rich source forms and pickers. Do not create a second preferences path or an in-file sub-screen state machine in production. The browser’s screen state is only a disposable design prototype.

## Visual system to take into native design

| Element | Proposed direction | Acceptance condition |
| --- | --- | --- |
| Typography | Bundle Inter regular/medium/bold; strong size hierarchy; consistent numerals | Works offline on Portal firmware; no GMS font dependency |
| Reading surfaces | Opaque grouped cards with restrained dividers | Text remains legible in light/dark modes and scaled text |
| Home palette | Deep eucalyptus for Everyday; photography for Gallery; slate blue for Control | Accent communicates selection or action consistently |
| Materials | Tint and subtle border on navigation; scrims over photos | No dependency on expensive continuous blur |
| Icons | Coherent vectors, labels for ambiguous actions, actual app identity where useful | Recognizable with touch and TV focus |
| Spacing | Small consistent rhythm; large gaps between task groups | Primary actions stay reachable without unwanted scroll |
| Motion | Brief purposeful state transitions; no idle jiggle outside explicit editing | No lost input; honors reduced motion |
| Focus | Clear focus ring distinct from selected state | Remote users can predict where the next direction goes |

The prototype uses the browser’s system font, not Apple SF assets or a claimed native Inter rendering. Landscape photos come from the project’s bundled fallback assets; the study uses no captured family photos. Vector icons are reused from the existing local mock library. This is an independent Immortal design, not Apple or Meta branding.

## What is interactive in the browser

- Switch among three home directions and seven review screens.
- Use actual navigation within the prototype, including Tools.
- Try populated, disconnected, and first-use states.
- Change light/dark settings presentation and sample switches/sliders.
- Search settings; open and highlight a relevant row.
- Connect a sample photo source: invalid input feedback, successful preview, album selection, and confirmation.
- Add, remove, and reorder widgets through Move; return home with Done.
- Search apps, open a folder, and preview launching an app.
- Preview app-store Get → Adding → Open without a download.
- Use keyboard focus, arrow navigation between buttons, modal focus containment, and Escape.

The photo setup form is intentionally local and uses dummy input. No request sends its contents anywhere. The suite does not install apps, control lights, start the native screensaver, save native settings, or change the connected Portal.

**Limits:** state lasts within a prototype page until reload; changing the concept or scenario resets that page. Some secondary choices demonstrate navigation and feedback only. Native gestures, TalkBack, IME behavior, room-to-room audio, device performance, and real integration failures require later verification. The narrow browser layout is illustrative, not a complete phone-remote redesign.

## Validation tasks after choosing a direction

Use the connected Portal at arm’s length, then from across the room. Include a person who has not learned the current UI, and a remote-only pass if Portal TV is in scope.

| Task | Proposed route | Evidence to collect |
| --- | --- | --- |
| Launch a familiar app | Favorite directly; Apps for the rest | Time, mis-taps, whether Favorites is understood |
| Change the clock face | Settings → Photos & screensaver → Clock face | Findability, Back behavior, correct preview/apply |
| Add an Immich album | Source → connection → album → confirm | Error recovery, credential entry, recognition of successful setup |
| Remove a widget and leave editing | Customize → Remove → Done | Whether entry and exit are discoverable |
| Find a less frequent setting | Search by common phrase → highlighted row | Search vocabulary, correct destination, focus |
| Recover from Wi-Fi loss | Cached home → connection help | Whether stale data is clear and useful; failed actions understandable |
| Use a TV remote | Navigate, open, change, Back | No focus traps, unreachable actions, or reliance on long press |

Suggested initial success targets are hypotheses: no hidden exit from editing; common home actions within one or two taps; successful settings discovery without instruction; no lost feature access; no degradation of launch or scroll responsiveness. Establish real baseline timings before claiming measurable improvement.

## Recommended implementation order, after approval

1. Shared foundation: offline typography, consistent spacing, row hierarchy, selected/focus states, and explicit edit exit.
2. Grouped settings: presentation categories over the existing registry, exact search destinations, preserved last pane, clear source status, and unchanged domain validation.
3. Chosen home: favorite model and navigation, adaptive widget slots, safe migration of existing `UserLayout`, accessible editing.
4. Journey polish: source connection progress/recovery, app-store compatibility and download/install status, empty/offline states.
5. Device acceptance: API 28 and 29, Portal+ 3:2 and smaller/portrait models, remote focus, font scale, TalkBack, Dream lifecycle, actual frame/scroll performance.

The existing unit suite and relevant build checks should run after implementation. Catalog validation applies if catalog data changes. Screenshots and manual interaction on the Portal are required in addition to JVM tests. No Android implementation is included in this study.

## Sources reviewed

These sources were opened during the online research. Statements above distinguish source guidance from the proposed Immortal design. None is a usability test of Immortal.

1. [Apple HIG — Materials](https://developer.apple.com/design/human-interface-guidelines/materials): functional-layer glass, material choice, and readability over content.
2. [Apple HIG — Layout](https://developer.apple.com/design/human-interface-guidelines/layout): visual hierarchy, grouping, alignment, and adaptive layout.
3. [Apple HIG — Sidebars](https://developer.apple.com/design/human-interface-guidelines/sidebars): navigation hierarchy and large-screen space tradeoffs.
4. [Apple HIG — Settings](https://developer.apple.com/design/human-interface-guidelines/settings): good defaults, concise settings, and task-context options.
5. [Apple HIG — Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility): readable content, accessibility preferences, and interaction alternatives.
6. [Apple HIG — Motion](https://developer.apple.com/design/human-interface-guidelines/motion): purposeful motion and respect for accessibility.
7. [Apple HIG — Searching](https://developer.apple.com/design/human-interface-guidelines/searching): search scope, location, and useful suggestions.
8. [Apple HIG — Feedback](https://developer.apple.com/design/human-interface-guidelines/feedback): feedback placement and appropriate interruption.
9. [Android — Canonical Compose layouts](https://developer.android.com/develop/ui/compose/layouts/adaptive/canonical-layouts): list-detail, supporting panes, adaptive grids, and compact navigation.
10. [Android — Accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults): 48dp targets, toggleable rows, labels, and semantics.
11. [Amazon Alexa — Responsive components](https://developer.amazon.com/en-US/alexa/alexa-haus/apl-components): hub versus handheld targets and labeled interaction.
12. [Amazon Alexa — Styles](https://developer.amazon.com/en-US/alexa/alexa-haus/apl-style-guide): smart-display density, viewing distance, typography, and spacing.
13. [Nielsen Norman Group — Progressive disclosure](https://www.nngroup.com/articles/progressive-disclosure/): common tasks in view and advanced complexity one level deeper.
14. [Nielsen Norman Group — Recognition vs. recall](https://www.nngroup.com/articles/recognition-and-recall/): visible options and cues reduce memory burden.
15. [Nielsen Norman Group — Dashboards and preattentive attributes](https://www.nngroup.com/articles/dashboards-preattentive/): glanceable hierarchy and the distinction between a dashboard and a portal.
16. [Home Assistant — Sections view](https://www.home-assistant.io/dashboards/sections/): grouped grids, conditional visibility, and layout-order tradeoffs.
17. [W3C WCAG 2.2 — Contrast minimum](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html): normal and large-text contrast thresholds.
18. [W3C WCAG 2.2 — Target size minimum](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html): web conformance floor and exceptions.

## Local source references

- [HomeActivity.kt](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt): home layout, edit mode, and Home Assistant controls.
- [ImmortalSettingsActivity.kt](../../app/src/main/java/com/immortal/launcher/ImmortalSettingsActivity.kt): current settings grouping and navigation.
- [SettingsComponents.kt](../../app/src/main/java/com/immortal/launcher/SettingsComponents.kt): shared row typography and controls.
- [SettingsRenderer.kt](../../app/src/main/java/com/immortal/launcher/SettingsRenderer.kt): declarative row rendering.
- [SettingsDomains.kt](../../app/src/main/java/com/immortal/launcher/settings/SettingsDomains.kt): domain specs and side effects.
- [Inter font resource](../../app/src/main/res/font/inter.xml) and [Type.kt](../../app/src/main/java/com/immortal/launcher/ui/theme/Type.kt): downloadable provider and theme binding.
- [Glass.kt](../../app/src/main/java/com/immortal/launcher/ui/components/Glass.kt): current tint and border material implementation.

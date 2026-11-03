# Quick controls setup — functional design review

**Proposal ready; native implementation is not approved or installed.**
Branch: `codex/quick-controls-review-20261102`, base `9b25d985`.
Bounded unit starts18:50:58UTC and ends no later than19:20:58UTC.

[Open the functional review](http://127.0.0.1:8891/quick-controls-review.html).
It contains only Quick controls and its setup destinations. The wider widget
gallery, Weather candidate and other settings are separate.

## Problem and proposed decision

The current widget heading opens Display; Customize → Settings opens Quick
buttons, which configures the app switcher. Neither controls the widget's tiles.
The tiles actually depend on launchable apps and the saved dashboard address.

**Both entry points should open one Quick controls setup page**, proposed native
page ID `quick-controls`, in the existing Home & widgets category and two-pane
settings host. The page contains:

- Home dashboard: saved-address status and navigation to existing page `ha`.
- Apps: read-only Jarvis/Home Assistant launchability and a Manage apps action
  that hands off to the existing Apps tab.
- A shared-state explanation and a preview of the shortcuts shown on Home.

The dashboard owner retains its current title, Home Assistant dashboard, and
registry field `home_dashboard.url` / Dashboard address. Its existing editor
saves the address; no second credential form, preference or URL field is added.
Native `HomeDashboardConfig.normalizeUrl` remains authoritative.

This is my proposed destination and layout, not a recorded user approval.
The dark/light colors, sidebar width, heading/history shape and row dimensions
come from the existing approved native palette and settings shell. Browser
fonts and scrollbar layout differ; these captures are not a pixel-match receipt.

## Source-backed behavior and deliberate scope

| Device state | Shortcuts |
| --- | --- |
| Launchable Jarvis | Hey Jarvis appears first |
| Dashboard address saved | Home dashboard replaces all four HA app shortcuts; HA app is unnecessary |
| No dashboard; launchable HA app | Lights, Scenes, Climate, Home |
| Neither dashboard nor supported app | Widget remains, with setup guidance |
| Inventory still checking | Proposed setup labels say Checking; known dashboard can remain available |

All four HA shortcuts currently open the same ordinary app launch intent.
They do not independently control lights, scenes or climate. The mock preserves
that behavior and explains it; changing the four tiles is outside this decision.
Full HA is preferred to minimal when both launch intents exist. A saved dashboard
address proves configuration, not connectivity or sign-in.

The Checking label is a presentation proposal for the new setup page. Current
Home discovery starts with an empty set. The empty-state copy also proposes
mentioning dashboard setup, which the current widget message omits.

Jarvis, dashboard and Apps handoffs are labelled simulations. Apps is not an
invented settings page; it leaves Settings for the existing Apps tab. Advanced
API/entity editors remain in their current owner and are outside this focused
mock. Advanced on/off does not change shortcut precedence.

Examples and URL edits stay in memory, reset on example selection, and send no
device commands. Browser URL validation illustrates common native rules; it is
not a substitute for the JVM URI parser or proof of complete parser equivalence.
The mobile browser stacks category choices for review; Portal keeps one left
pane and one right pane.

## Functional and visual checks

Final local browser run: **43 interaction assertions and12 layout combinations
passed**. Layout combinations use390×844,1600×1000 and2160×1440 viewports,
both themes and standard/150% text. No horizontal overflow or overflowing row
content was found; the last app shortcut remained clickable. Two-pane layout
was verified at the desktop and simulated Portal viewports.

Checks cover both real widget entry actions; category selection; Back/Forward
and forward-branch retirement; Save/Cancel/Escape; preserved escaping/query/
fragment; blank-to-disable; representative malformed URL rejection; five
inventory states; same-app handoff truthfulness; and keyboard focus restoration.
Zero page errors or external requests were observed during the final run.
This is scoped browser QA, not native performance or full accessibility acceptance.

Independent source review identified lost keyboard focus and malformed
IPv6/DNS-host acceptance. Each was reproduced in the browser and corrected.
An earlier prototype overflow let the footer intercept a shortcut; minimum
flex heights corrected it. One combined fixture retained Customize state and
incorrectly expected the next toggle to enable editing; its scenario reset was
corrected. A receipt export attempted an unsupported tool-VM dynamic import;
the final run returns the report and exports it through the browser/file tools.
Fresh bounded source review found no remaining significant regression.

Actual browser captures were visually reviewed:

![Browser mock at the simulated Portal viewport](https://raw.githubusercontent.com/starbrightlab/immortal/main/mocks/portal-widget-gallery-20261102/evidence/quick-controls-portal-review.png)

![Mobile browser review at150% text](https://raw.githubusercontent.com/starbrightlab/immortal/main/mocks/portal-widget-gallery-20261102/evidence/quick-controls-mobile-review.png)

## Native acceptance after design approval

Use existing `ApprovedSettingsHistory`, navigation and rows. Both widget entry
points must reach the same page; existing dashboard and Apps destinations must
remain authoritative. Reuse one immutable discovery result for status and preview,
with background launch-intent checks on active resume. Do not duplicate discovery
for the embedded preview, add periodic polling or start dashboard/voice work
merely by opening settings.

Family acceptance must cover configured/unconfigured dashboard, app availability,
Advanced on/off, duplicate-widget shared state, both entry points, editor
Save/Cancel/Back, settings history and Apps return. Compare native normal/150%
text captures and both themes with this review; verify no clipped or colliding
rows. Preserve saved placement, credentials and complete settings.

Performance checks must establish zero new HTTP/voice work from viewing setup,
bounded discovery on active resume, and no idle polling or duplicated preview
loads. Record actual Family UI/request/resource results; do not infer them from
Mac browser timing. Build/unit gates and full installed APK readback precede
promotion. Deploy the exact Family-accepted APK to Living, without repeating
functional QA there. Any exhausted investigation remains parked.

| Target | Current status |
| --- | --- |
| Source contract | Reviewed against current checkout |
| Browser mock | Built, interacted with and visually reviewed |
| Native setup/routes | Awaiting user design approval; unchanged |
| Family visual/function/performance | Not run for this proposal |
| Living deployment | No APK change or deployment |
| Companion/browser remote schema | No field/API change proposed |

Source anchors: `HomeActivity.kt:704,2960,2983,2990`;
`HomeDashboardActivity.kt:27`; `JarvisHandoff.kt:50`;
`HaSettingsActivity.kt:267`; `SettingsDomains.kt:1317`;
`HomeDashboardConfig.kt:27`; `ApprovedSettingsShell.kt`;
`ApprovedPalette.kt`; `SettingsComponents.kt:95`.
Private execution and QA evidence:
`/private/tmp/portal-quick-controls-review-20261102/`.
No GitHub, Home Assistant deployment, Portal write or install was performed.

# Native feature integration

How the fork feature set merged since Release 1.52 should be wired into Immortal so users can
actually reach and configure it. This is the plan of record; it supersedes the deferred
"integration PR / ForkHome" approach.

## Why this doc exists

The features landed as self-contained PRs, but their **entry points were deferred**. On a real
device today, most of them are unreachable: no home tile launches the tool Activities, and the
settings menu has no link to the new settings Activities. The registry code inside each feature is
mostly correct — the gap is purely *integration*.

## Why not ForkHome

`ForkHome` (from the original #85) is a ~3,200-line **parallel home screen**: its own grid, its own
tool-folders, dashboard pages, chips, and widgets. It re-implements subsystems Immortal already
has. Adopting it would duplicate the home grid and re-introduce a second navigation model — the
opposite of the guidance in `AGENTS.md` ("reuse existing subsystems"; "one nav model — Activities").

We integrate the **Immortal-native** way instead, and borrow ForkHome's good *ideas* (ambient
dashboard, timer/countdown chips, a status strip) into the existing subsystems over time — not the
monolith. Same call we made on the portal-wake wake-word app: take the design, not the second app.

## The two surfaces that own integration

1. **Settings → the declarative registry** (`settings/`). A `SettingSpec` defined once drives the
   on-device UI, the phone remote, and persistence. Rich sub-screens hang off a domain via a
   `NavSpec` (renders as a nav row → Activity). The registry **models scalars**; collections and
   free content live in their own Activity (a `GroupSpec` is their planned remote home).
2. **Launching tools → the unified home grid** (`HomeActivity`). It already supports built-in tiles
   (`BUILTIN_CALLS`/`STORE`/`UPDATES`), widgets, **folders** (`Curation`/`UserLayout`/`HomeGrid`),
   and drag-reordering. New tool Activities become **built-in tiles**, grouped by default into a
   **"Tools" folder** — the native analog of ForkHome's tool-folders, with zero new home code.

## Per-feature plan

| Feature | Kind | Reach it via | Remote |
|---|---|---|---|
| Chimes (`chime`) | scalar settings | `NavSpec` "Sounds" row → `ChimeSettingsActivity` | ✅ domain |
| Digital clock (`digitalclock`) | screensaver | screensaver **Dream picker** + `NavSpec` → `ClockSettingsActivity` | ✅ domain |
| Welcome overlay (`welcome`) | scalar settings | `NavSpec` in screensaver settings → `WelcomeSettingsActivity` | ✅ domain |
| Sleep (`screensaver` fields) | scalar settings | `NavRow` in screensaver/immortal settings → `SleepSettingsActivity` | ✅ domain |
| Sunrise / wake-light (`sunrise`) | scalar settings | `NavSpec` "Wake-up light" row → `SunriseSettingsActivity` | ✅ domain |
| Back-gesture (#99) | service | `BoolSpec` toggle in `immortal` that deep-links Accessibility settings | ✅ domain |
| SystemSounds | scalar | `BoolSpec` in `immortal`/`screensaver` | ✅ domain |
| RTSP cameras | **collection** | Tools tile → `CameraViewerActivity` (list + add) | later: `GroupSpec` |
| Kitchen timers | **collection** | Tools tile / home chip → timer UI | later: `GroupSpec` |
| Countdowns | **collection** | Tools tile / `NavSpec` → `CountdownSettingsActivity` | later: `GroupSpec` |
| Voice notes | **content** | Tools tile → `AudioNote` UI | n/a |
| Lamp | action | Tools tile → `LampActivity` | n/a |
| Bedtime stories | action | Tools tile → `BedtimeStoryActivity` | n/a |
| Intercom / ping | action | Tools tile → `IntercomActivity` | n/a |
| Wake-light overlay | auto (alarm) | fired by `SunriseReceiver` — no tile needed | n/a |
| Almanac packs (feast/name days, prayer, transit, daily content) | data | ambient dashboard on the photo frame; toggles in `screensaver` | via screensaver |
| Sky tools (ISS, aurora, starfield, sky colours) | background/data | render behind the grid / on the frame; toggles in `screensaver` | via screensaver |
| Converter | tool | Tools tile → converter Activity | n/a |

## The "Tools" folder

Extend the home grid with a small registry of built-in **tool tiles** (a `TOOL_KEY` prefix, mirroring
`BUILTIN_*`), dispatched in `HomeActivity`'s tile `when(key)` to `startActivity` each tool. Curate
them into a default **"Tools"** folder (same mechanism as the existing "Settings" folder in
`Curation.folders`) so they stay tidy but remain reorderable and removable via the existing layout
system. No new grid, no `ForkHome`.

## Registry guardrail (done)

`SettingsDomainTest.everyPersistedConfig_isRegisteredAsADomain_orExplicitlyExcepted` now fails the
build if a new `*Config` is neither backed by a `SettingsDomain` nor listed as a documented
collection/content exception. This is the check that would have caught Camera/Timer/Countdown/Notes
shipping with no registry home.

## Sequencing (small PRs, in order)

1. **This PR** — the global tripwire + this doc.
2. **Settings reachability** — `NavSpec`/`NavRow` entries for Chime, Clock, Welcome, Sleep, Sunrise
   (they're already registry domains; they just need to be linked). Smallest, highest-value.
3. **Tools folder + tiles** — Camera, Timers, Countdowns, Notes, Lamp, Bedtime, Intercom, Converter.
4. **Screensaver Dream picker** — surface the digital-clock Dream alongside the photo frame.
5. **Ambient dashboard** — surface the almanac/sky content on the frame (borrow the ForkHome idea).
6. **Toggles** — back-gesture enable, SystemSounds.

Each step is independently shippable and testable. None require `ForkHome`.

## Release gate

Do not cut a release until at least steps 2–3 land — otherwise the merged features ship dark on the
device (they are reachable on the phone remote for the registered scalar domains, but not on the
Portal itself).

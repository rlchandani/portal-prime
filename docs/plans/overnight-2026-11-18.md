# Overnight 2026-11-18 → 19 (owner asleep: "just go and build everything")

Constraints: quiet hours 22:00–08:00 — no audio on any Portal (STREAM_MUSIC 0 during tests, restore),
no ringing/chimes, minimal screen wakes; never force-stop the launcher; never capture Home/screensaver
(family photos) or camera feeds; GitHub `main` only; max 2 concurrent Gradle builds; Mini perf parity
is a ship gate. Ship via OTA after review + device verification (both Portal+ and Mini).

Running: Floating Jarvis v1 · Screensaver phase 1 · Portal Mini rotation + provisioning.
Queue (start as builders free): background alerts for all sensors → Mini responsive layouts
(LayoutClass, handover §7.2) → Who's there? v1 (face-only, visible glance) → Calling phase 2 →
Control Center on the phone (Portal Toolkit) → Mini Meta-service debloat (reversible, separate).

Morning report: what shipped (versions), perf tables (Portal+ and Mini), what needs the owner.

## For the morning report
- Mini: rotation works (left turn); right turn needs our controller (building). Owner: pair the Mini
  from Portal Toolkit; right-turn test; optional HA on the Mini.
- A YouTube OAuth client id/secret from portal local.properties was printed in a local agent session
  (not saved, not pushed). Rotate only if session logs are shared.
- Apps-tab Calls tile still opens Meta calling (fix in mini-layout branch).

## Morning report (2026-11-19 ~06:45)
Shipped overnight (all OTA, all 3 Portals unless noted): 1.254 Mini layouts + rotation · 1.255 Floating
Jarvis + alerts for every sensor + Back fix · 1.256 Night photo / Split glance / Digital clock · 1.257
Who's there? v1 (off) · 1.258 OTA install fix · 1.259 Calling 2a (groups, messages, phone, Grandma) +
household-key security fix (Family/Mini pending perf agent restore → done).
Local, not shipped: perf-followups (Settings/Music opens faster, baseline profile) — waiting on the
system_server leak + Jarvis tap investigation.
Found: Family system_server 1.86 GB PSS (Dalvik 478 MB, 1.2 GB swap) after 13 days → probable cause
of Family call/Jarvis slowness; investigating; owner decides reboot.
Owner to-dos: pair the Mini from Toolkit (key + mic) and Gemini key on the Mini · right-turn test ·
Mini music volume back to 9 · Who's there? enrolment · real 3–4 Portal call · Pixel Toolkit install ·
voice test of floating Jarvis · Cloudflare relay decision · reboot Family?

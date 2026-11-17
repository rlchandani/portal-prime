# Household features, round 2 (owner chose 2026-11-17)

Order (one or two builders at a time — 3+ concurrent Gradle builds OOM this Mac):
1. Shared timers · 2. Morning briefing · 3. Doorbell / camera pop-up · 4. Chores & stars ·
5. Room-to-room video drop-in (research first).

Common rules: Kotlin/Compose, small files, immutable state, JVM tests for logic, Hearthpane copy,
ApprovedHomePalette tokens, ≥64 dp targets, light/dark. Household sync over the existing signed
HouseholdBus (UDP 50122) + session-authenticated `/remote/<name>` feature routes for the phone.
Quiet hours respected (no sound 22:00–08:00 unless the owner's setting says otherwise). Phone
(Portal Toolkit) gets matching controls. Only `main` on GitHub; ship via OTA.

## 1. Shared timers
- Start a timer on any Portal (Tools › Timer, Home widget, Jarvis later) or the phone; it appears
  on every Portal (and the phone) with name, room it was started in, time left; any device can
  pause/add 1 min/cancel. When it ends it rings on every Portal (respecting quiet hours → visual
  only) and notifies the phone; "Stop" anywhere stops it everywhere.
- Wire: `timer_set` / `timer_update` / `timer_done` household messages carrying absolute end time
  as a duration from send (clock-skew safe, like follow-me), plus `/remote/timers` (GET list, POST
  start/pause/resume/add/cancel/stop). Dedupe by timer id; last-writer-wins by sequence.
- Integrate with the existing timer feature in Tools (find it) rather than a parallel one.

## 2. Morning briefing
- At each Portal's wake/alarm time (Sunrise & wake / alarm settings) or on tap ("Good morning"
  card on Home, 06:00–11:00), a full-screen card: greeting, weather now + today, today's calendar
  (next 3), reminders/shared list count, first routine, quiet "music?" suggestion. Optional
  spoken summary via TTS (off during quiet hours; uses the existing chime voice).
- Settings › Alarms & Wake › Morning briefing: on/off, auto at alarm vs. on tap, spoken on/off.

## 3. Doorbell / camera pop-up
- Uses the Home Assistant connection already in the app (cameras viewer exists). The owner picks
  a doorbell event entity / binary_sensor (press) and optional motion sensors + their camera.
- On trigger: every Portal wakes and shows the live camera (HA camera stream/snapshot loop) as a
  card with Dismiss and (if available) Talk/Unlock-asks-first; phone gets a notification.
  Quiet hours: visual only, no chime. Cooldown per camera.
- Detection via HA websocket subscribe_events/state_changed (one Portal or each Portal — decide;
  avoid duplicate alerts by household message dedupe).

## 4. Chores & stars for kids
- Builds on picture routines (phone-managed): chores with picture, assignee (kid), points/stars,
  schedule (daily/weekly). Kids tick on any Portal; stars sync household-wide; weekly goal +
  reward; parent approval optional (phone). Home widget shows each kid's stars.

## 5. Room-to-room video drop-in (research gate)
- First establish feasibility on Portal Android 10: can a third-party app open the front camera
  (Camera2) and the far-field mic while Meta's services run? WebRTC (library or WebView
  getUserMedia) over LAN with the household key for signalling. Privacy: explicit accept on the
  receiving Portal by default ("Living Area wants to drop in"), visible indicator, never silent.
- Report a go/no-go with evidence before building.

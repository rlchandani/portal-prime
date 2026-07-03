# Ambient & sound

Small touches that make a Portal feel like part of the room rather than a screen on a shelf: gentle
audio cues through the day, and a gradual wake-light in the morning. Both are **off by default** and
configured from the [Settings](launcher.md#settings) screen.

## Sounds — chimes & spoken time

`ChimeConfig` / `ChimeScheduler` — the **Sounds** settings screen (`ChimeSettingsActivity`). Every
cue is off by default; switch on only the ones you want.

- **Hourly chime** — a soft tone on the hour. Volume is adjustable (default 60%).
- **Spoken time** — the Portal says the time aloud on the hour ("It's three o'clock"), through
  Android's text-to-speech, with a voice picker.
- **Golden-hour tone** — a tone at sunrise and sunset, with a choice of morning or rooster sound.
- **Ping the other room** — a doorbell used by the Wi-Fi [Intercom](tools.md); its volume is a touch
  louder by default (85%).
- **Quiet hours** — a nightly window that mutes every cue. This one is **on by default**
  (22:00–08:00), so nothing chimes overnight until you say otherwise.

## Sunrise wake-light

`SunriseConfig` / `SunriseScheduler` — the **Wake-up light** settings screen
(`SunriseSettingsActivity`). At the set time, the screen brightens gradually from a deep ember to
full daylight over a ramp, optionally finishing with a soft chime — turning a bedroom Portal into a
sunrise alarm.

- **Time** and **days of the week** (default 07:00, Mon–Fri; leave the days empty for a one-shot
  next-occurrence alarm).
- **Ramp** — how long the brighten takes (default 20 minutes).
- **Chime at the end** — a gentle tone when the ramp completes (default on).

It re-arms itself across reboots, so it keeps working after a power blip.

## See also

- The screensaver's [welcome-back overlay](screensaver.md#welcome-back-overlay) and
  [ambient almanac](screensaver.md#ambient-almanac-calendar-packs) are the other "feels alive"
  touches, but they live on the photo frame.

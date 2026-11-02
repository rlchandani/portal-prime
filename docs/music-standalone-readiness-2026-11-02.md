# Music without HA: current route readiness

This bounded review combines current source, official requirements and actual
Family Area browser observations. It does not deliver a replacement player.
The full native player outcome in [the music goal](music-player-goal.md) remains
required; an official website is not equivalent to that custom UI and queue.

## Actual Family observations

| Observation | Evidence / result |
| --- | --- |
| Hardware context | Android 10; `MemTotal` 2,848,792 kB, about 2.7 GiB; existing Meta Chromium browser |
| Browser engine | Actual normal-browser probe reports Chrome 138, despite Meta's unrelated package version string |
| Public capability probe | Media Session, Web Audio, AudioWorklet, AudioDecoder, WebSocket, MediaSource and IndexedDB present |
| Declared formats | MP3, AAC and Opus return `probably`; this is format declaration, not a decoded-stream test |
| Silent Web Audio check | Actual user gesture: running context, clock advanced, 48 kHz, worklet present; no sound generated |
| Official website | Actual `music.youtube.com` Home, search, Explore and Library UI rendered; Sign in visible |
| Ownership | Complete Family registry profile and screensaver configuration unchanged after checks |

The probe used loopback HTTP through one owned ADB reverse mapping. Loopback
was a secure context; those results do not establish every API's availability
on a plain HTTP NAS origin. SharedArrayBuffer was absent in that context.
The temporary probe tab, reverse mapping, device XML and owned server were
removed/stopped. Both original browser tabs were preserved. One YouTube Music
tab is deliberately left open for the normal human sign-in requested through
the pending question; the original slideshow foreground was not restored.
No account credential, cookie, provider token or track was obtained or played.

## Fit to the requested product

| Route | What it can supply | Current evidence / missing work |
| --- | --- | --- |
| Official website in normal browser | YouTube-owned library, playlists and queue without a local server or HA | Actual page/engine readiness passed. Premium login, audio, Android session, background/screensaver continuity and performance remain unverified. |
| Standalone Music Assistant outside HA | Closest fit to existing native queue/player/room adapter | Official Docker option exists. Needs an authorized supported host, YouTube source setup, version compatibility, actual output/ownership and full native acceptance. None is newly installed. |
| Official Android YouTube Music app | Potential direct account playback | Android 10 exceeds Google's stated Android 8 minimum, but operation/login without Google services is unverified; app was not installed. |

Current native local playback observes another Android app's media session.
It can display track/artwork/progress and advertised play/pause/next/previous/seek.
It does not expose that app's library, queue, lyrics, shuffle, repeat or favorites.
The notification listener is enabled on Family, but the browser still needs to
publish a usable session with a nonblank title and observed playing state.
JavaScript Media Session presence does not prove that Android behavior.

`MusicPlayerHost.kt` falls back to local sessions when multi-room is disabled
or its host is empty. Family's saved state meets that condition. A configured
MA client takes precedence, including while offline. MA library browsing opens
the server UI; the native library/playlist components have no production callers.
The Music tile opens the native player overlay. Older comments naming a direct
YouTube WebView target are stale; `WebAppActivity` remains a separate embedded
browser and destroys its instance when closed.

## Current compatibility and recommendation

The current `MaMusicClient.kt` greeting check accepts only **2.10.7 / schema65**.
Official MA documentation now advertises **2.10.9**. A 2.10.9 greeting would be
rejected by that exact version check even if its schema were 65. Its actual API
schema and adapter compatibility have not been inspected. Do not install the
latest server and promise that it works with this client, or weaken the guard
without protocol evidence.

For the full approved native experience, a standalone server outside HA remains
the best architectural fit. Its host and account dependencies still need to be
resolved; moving the server does not repair the parked output/performance work.
For an immediate server-free option, proceed with official-browser account and
playback checks, explicitly retaining its smaller feature scope.

The next browser acceptance needs normal human Google sign-in, then one
controlled Premium track and native media-session/controls proof, followed by
Home/screensaver continuity checks with measured outcomes. Those checks are not
yet performed. No confidence percentage or general performance success is inferred.
The old MA receiver/audio/performance slots remain parked.

## Sources and scope

- [MA installation](https://www.music-assistant.io/installation/): standalone Docker, supported host/network and memory requirements.
- [MA YouTube source](https://www.music-assistant.io/music-providers/youtube-music/): Premium, cookie plus PO helper, best-effort integration and maintenance.
- [Google Premium benefits](https://support.google.com/youtubemusic/answer/9266556?hl=en): official browser route; account-dependent benefits.
- [Google supported browser sign-in](https://support.google.com/accounts/answer/7675428?hl=en): embedded/automated sign-in restrictions.
- [Google OS requirements](https://support.google.com/youtubemusic/answer/6313540?hl=en): Android 8+; no Portal/no-GMS acceptance claim.
- [MA Sendspin](https://www.music-assistant.io/player-support/sendspin/): built-in web receiver in technical preview, not a tested replacement here.

Source review: `MusicSessionPolicy.kt`, `MediaSessionReader.kt`,
`MusicPlayerHost.kt`, `MusicBrowseUi.kt`, `MaMusicClient.kt`, `WebAppActivity.kt`.
Independent source review reached the same conditional local-session conclusion.
No Kotlin change, APK build/install, HA action, new server installation, actual
YouTube playback or Living interaction occurred.

Slot **11:53:49–12:23:49 UTC**, including cleanup. Private probe, screenshots,
native XML and complete baseline receipts:
`/private/tmp/portal-music-standalone-review-20261102/`.

## Bounded exact-protocol source follow-up

An independent source review mapped the consumed authentication, player/queue,
volume/group commands, correlated partial replies and update events. The
[official API page](https://www.music-assistant.io/api/) matches selected
`player_queues/items` arguments and directs clients to each running server's
generated `/api-docs`. Its unversioned HTTP examples do not establish the actual
2.10.9 WebSocket greeting/schema, authentication, command results or events.

Both exact-version PyPI endpoints returned404:
[`music-assistant`](https://pypi.org/pypi/music-assistant/2.10.9/json) and
[`music-assistant-server`](https://pypi.org/pypi/music-assistant-server/2.10.9/json).
No exact artifact or model dependency version was obtained, and no external
package was executed. No narrowly justified compatibility patch follows from
these sources; the current guard remains unchanged. Do not repeat the same
probe without a new exact artifact or authorized server-contract observation.
This remains research evidence, not newer-server or account playback acceptance.

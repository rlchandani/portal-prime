# Music without Home Assistant — October 19, 2026

The user rejected music services on Home Assistant and authorized undoing this project's HA changes. The former HA-hosted route is cancelled. Standalone alternatives below are research findings, not a newly authorized installation or a completed music release.

## Verified removal

- Music Assistant `d5369777_music_assistant` and YT Music PO Token Generator `d5369777_ytm_po_token_generator` are absent from the authenticated Supervisor installed-app inventory. Removal requested deletion of their configuration folders. Ten pre-existing apps remain installed; their final running/stopped states match the pre-removal inventory. No command targeted another HA app, dashboard, automation or integration. A transient stopped broker observation recovered without a broker command.
- No configured `music_assistant` HA integration remains. TCP ports 8095, 1704, 1705 and 4416 no longer accept connections on the former music host. An uninstall response reported an error despite eventual removal; the inventory/port checks provide the outcome evidence.
- Family Area's registry now has multi-room disabled, an empty server host and no selected MA player. Its old stock receiver was force-stopped without deleting its data. Actual music readback is Not configured and has no MA source. Unrelated recorded Immortal preferences and screensaver component are unchanged. The obsolete credential field was not rewritten; it is inactive and its server was removed. No APK/code was rolled back, phone pairing was changed or Living Area device was touched.
- Private, noncredential receipts: `/private/tmp/portal-music-ha-removal/home-assistant-cleanup.json` and `family-cleanup.json`. The existing tested work remains committed on the music branches. No new server or music client was installed during this change.

## Alternatives

| Route | What it provides | What remains to prove |
|---|---|---|
| Standalone Music Assistant in a separate NAS/Linux Docker container | Independent music server; existing native Immortal queue, metadata and room adapter can connect directly. HA can be stopped or removed without owning this service. | Container host capability/network/storage; server-side YouTube account setup; actual Portal audio handoff, interruption and performance gates. |
| Official YouTube Music website in a normal Portal browser | Your Premium account, library, playlists and YouTube's own queue with no local music server. | Normal Google sign-in, sustained playback, full-screen presentation, browser MediaSession controls and screensaver/background continuity on this hardware. It supplies no native MA queue or synchronized Portal group implementation. |
| Official YouTube Music Android app | Potential direct account playback without a music server. | Android 10 meets the published Android 8 minimum, but official operation/sign-in without Google Play Services is not established. It is not the first recommendation. |

Music Assistant officially supports a simple standalone Docker installation, with persistent `/data`, host networking and its own interface on port 8095. A separate container is outside HAOS/Supervisor and needs no HA integration. It still needs an always-on host and a supported network. Do not put the Linux server onto the old Android Portal. [Official installation](https://www.music-assistant.io/installation/).

The YouTube source supports the account library and playlists, but is explicitly best effort rather than an official YouTube streaming API. It still requires Premium, a server-side login cookie and a compatible PO helper; Docker-hosting the helper is documented. The documentation and the previous 2.10.7 app's helper version disagree, so version compatibility must be checked against the chosen release before any new setup. Moving the host does not remove this maintenance or repair the parked audio/performance failures. [YouTube source documentation](https://www.music-assistant.io/music-providers/youtube-music/).

Google explicitly documents visiting `music.youtube.com` in a smart-display browser. This establishes the route, not Meta Portal compatibility or screen-off continuity. [Premium benefits](https://support.google.com/youtubemusic/answer/9266556?hl=en-GB). Google may reject sign-in in embedded browsers, so prefer a normal browser to a custom login WebView. [Supported browser sign-in](https://support.google.com/accounts/answer/7675428?hl=en).

The official embedded YouTube API does not offer a drop-in native audio-only replacement: its policies prohibit audio separation and hidden/background players. A Premium subscription does not establish an exception for our custom player. [Developer policies, III.I](https://developers.google.com/youtube/terms/developer-policies).

Family's current inventory has Meta's `org.chromium.chrome` and `com.facebook.portal.webview`; current Firefox, Google Chrome and the official YouTube Music app were not found. Their Meta build version strings do not establish a modern Chromium engine. No browser sign-in or playback was tested in this cleanup. [Official app OS requirements](https://support.google.com/youtubemusic/answer/6313540?co=GENIE.Platform%3DAndroid&hl=en-GB).

## Recommendation

For the full native player previously selected, standalone Music Assistant is the closest fit and preserves the existing app work. Choose a separate service outside HA, then validate its host capability directly rather than asking the user for a NAS model. This is an architectural recommendation, not proven audio/performance acceptance.

For the least infrastructure, first test the official website on Family Area in a supported normal browser. Accept that its library/queue remain YouTube's UI and that background/saver/media-control behavior needs a bounded device check. Do not promise those capabilities or install another HA component to compensate.

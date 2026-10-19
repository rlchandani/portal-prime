# Immortal music server readiness — MA + existing Snapcast path

Readiness and execution record, updated 2026-10-18 Pacific. The native adapter targets MA 2.10.7 / schema 65. The existing signed-in Home Assistant installation was confirmed as **HAOS 18.3 / Core 2026.10.2 / Supervisor 2026.10.2**, running on the user's Synology. **Stable Music Assistant 2.10.7 has been installed and reports Running.** YouTube Music Premium is confirmed. Provider authentication, actual Portal playback is still pending. Snapcast server and Family client connectivity are verified. No Google login cookies have been obtained or copied into Immortal.

The existing path is **Music Assistant → Snapserver → stock Snapcast Android player (`de.badaix.snapcast`) → Portal audio**, with Immortal reading Snapcast metadata/topology and forwarding transport to MA. Native Immortal controls can use this path without introducing native Sendspin. Local references: [setup guide](../docs/guides/multi-room-audio-setup.md), [control client](../app/src/main/java/com/immortal/launcher/SnapcastControlClient.kt), [MA transport](../app/src/main/java/com/immortal/launcher/MaControl.kt).

## Conditional installation plan

**Common prerequisites:** an always-on supported 64-bit CPU/OS, 2 GB RAM minimum (4 GB+ when co-hosting), and MA/HA/players on one flat LAN. Record the stable version. Docker needs host networking, persistent writable `/data` and read-only music at `/media`. UI defaults to `http://<host>:8095`; streaming starts at 8097 but can advance. Check startup logs for the actual LAN address/ports. [Official MA installation](https://www.music-assistant.io/installation/).

**Chosen host:** use Music Assistant through the existing HAOS app system on the NAS. Its app catalog already contained the official stable package at 2.10.7; no extra repository or Container Manager project was needed. Installation and Running status were verified in the signed-in HA UI. Resource allocation and startup logs remain to be recorded before a final performance claim.

The HA integration is optional for Immortal's direct MA connection and does not install the server. The Portal needs the HA machine's reachable LAN address rather than an internal app address. [MA installation](https://www.music-assistant.io/installation/), [HA integration installation](https://www.music-assistant.io/integration/installation/).

For NAS music on HAOS, network storage with **Media** usage makes the share available under `/media/<name>`. Docker uses its bind-mounted path instead. [HA network storage](https://www.home-assistant.io/common-tasks/os/#network-storage), [MA filesystem sources](https://www.music-assistant.io/music-providers/local-files/).

## Supported Snapcast setup

In MA, **Settings → Player Providers → Add → Snapcast** starts the built-in Snapserver. This is the simplest fit for the current single-host Immortal settings. Official docs support Snapcast clients and describe Snapdroid/Snapweb connection at `http://<MA-host>:1780`; Immortal's catalog supplies the stock Android player.

Keep initial defaults: **48 kHz / 16-bit, FLAC**, stream name `default`. Higher rates/24-bit need a compatible external server and clients. External Snapserver must be **0.27.0+**, but **0.30.0 is explicitly unsupported**. Start with default buffering; a roughly one-second control-to-audio delay can be expected. Snapcast pause may not report a literal paused state.

| Endpoint | Purpose |
|---|---|
| MA 8095 | UI/API; Immortal's existing MA port is configurable |
| MA 8097 or actual logged port | MA stream server |
| Snapserver 1704 | Audio connection |
| Snapserver 1705 | JSON-RPC control/topology; existing Immortal relay fixes this port |
| Snapserver 1780 | Snapweb/browser endpoint |
| 4953–5153 | Additional range called out by MA's Snapcast documentation |

Check relevant LAN reachability and port conflicts during setup; this is not an instruction to expose services to the internet. [Official Snapcast provider](https://www.music-assistant.io/player-support/snapcast/).

## First real playback checklist

1. **Server ready:** record host, MA version, actual UI/stream ports and Snapserver mode/version. Container/app must remain running with persistent data and a correct LAN address.
2. **Authentication ready:** finish MA first-run setup. HA ingress can bootstrap administration; standalone installation presents an initial account setup. MA API credentials must be kept out of logs. Google cookies belong only to the server-side YouTube source. [First run](https://www.music-assistant.io/first-run/).
3. **One real source:** configure the selected YouTube Music Premium source with its server-side login cookie and supported PO-token helper. Confirm at least two finite-duration tracks appear and are playable; wait for source scanning. For a local-folder test, check title/artist/duration and artwork rather than assuming library import succeeded. [Filesystem sources](https://www.music-assistant.io/music-providers/local-files/).
4. **One real speaker:** stock Snapcast is installed/running on Family; server topology confirms one connected Portal+ client. Explicit MA player mapping was verified against the Snapcast client ID/IP and selected on Family.
5. **Actual sound:** select that exact MA player and play one known track. Hear audio from the Portal; confirm advancing position and matching metadata. Exercise play, pause/resume, next/previous and volume at a modest level. A green connection badge alone does not pass this check.
6. **Immortal linkage:** require a valid `Server.GetStatus` response and a topology entry matching the Portal's LAN IP/client ID. Verify MA resolves that same client to the intended player. Check the displayed track and transport affect that room, including while another room plays different music.

Existing-code constraints to verify during subsequent implementation/setup:

- [MaWebSocket](../app/src/main/java/com/immortal/launcher/MaWebSocket.kt) uses raw `ws` at `/ws`, not TLS `wss`. A reverse-proxy HTTPS URL is not interchangeable with the current direct LAN connection.
- [MaControl](../app/src/main/java/com/immortal/launcher/MaControl.kt) uses the same configured host as Snapcast; separate MA/Snapserver hosts are not represented by that baseline setup.
- The current legacy and persistent MA adapters require an explicit API token and a correlated `authenticated:true` reply. Socket reachability alone is insufficient. Username/password token minting is deliberately unsupported until its deployed response contract is verified. Installed server `/api-docs` is the version-specific reference. [Official API](https://www.music-assistant.io/api/).

## Required evidence before queue/group claims

- **Queue:** resolve the active queue for the selected player/group. Inspect `player_queues/all` and `player_queues/items`; grouped queue IDs can differ from player IDs. Test empty and two-track queues, current index/elapsed time, next/previous, seek, shuffle/repeat, and every offered edit/jump action. Read back authoritative state after each mutation and verify audible results. Paginate; keep each request at **500 items or fewer**. [MA queue API guidance](https://www.music-assistant.io/api/).
- **Groups:** require at least two connected Snapcast clients. Confirm both play the same queue, join/leave resolves the correct leader/members, and both audible output and per-room/group volume match the requested target. Re-read mappings after regrouping; test that unrelated rooms remain unaffected. Temporary groups hold the leader's queue; permanent sync groups have different ownership. Universal groups do **not** promise synchronized audio. [Official group behavior](https://www.music-assistant.io/faq/groups/).
- **Recovery/quality:** verify reconnect and paused/idle handling against actual server behavior. Record negotiated output format and check for audible dropouts under ordinary use; metadata quality labels alone do not demonstrate output quality or synchronization. No performance result is claimed by this research.

Unresolved: allocated host resources, YouTube cookie/PO-token source setup, actual stream-server port, live MA queue/command API behavior, Portal playback and multi-room evidence. LAN MA host/port and Snapcast topology are verified. The host choice and HA installation type are resolved; MA is installed and running. Queue/group/playback quality claims remain **unvalidated**.


## Actual installation checkpoint

- HAOS app path selected and installed MA stable 2.10.7. Actual LAN `/info` returns version 2.10.7/schema 65 on port 8095; this matches the implemented adapter pin.
- MA built-in Snapcast provider reports Running. JSON-RPC `Server.GetStatus` confirms Snapserver 0.34.0. Provider initial buffer 1000 ms/chunk 26 ms and FLAC/default settings were preserved.
- Family stock Android client is installed, uses the same verified LAN host with stream/control ports 1704/1705, and auto-start is enabled. Real topology reports one connected Portal+ at 25% volume. This does not prove real music, synchronization or interruption recovery.
- An MA integration API token was created through its Profile UI and applied to Family's existing registry in memory, without token output/file storage. Username/password controls are inactive in the new connection UI; their stored values are preserved. The exact Family MA player was subsequently selected from an authenticated server enumeration, using the connected Snapcast client identity/IP.
- YouTube PO helper and cookie authorization are not configured. Premium alone does not establish provider authentication. No Google cookie is saved in the Portal app or chat. Follow the current deployed provider documentation before its account setup.


### Live MA API checkpoint

The actual saved integration token authenticated successfully with MA. `players/all` returned available players; the unique Snapcast player matching Family's connected client ID/IP was selected through the existing registry. Immortal's real `/remote/music` state changed **connecting → ready**, with **0 queue entries / 10 discovered rooms / no problem**. Native connected-idle inspection is recorded in private local artifacts. No play/queue/group mutation was sent; this does not establish YouTube authorization, audible output or latency/performance acceptance. Initial standalone probe used numeric IDs and received no correlated reply; switching it to the string IDs already used by the production client produced the verified response. No production protocol change or token output was required.

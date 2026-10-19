# Immortal music server readiness — MA + existing Snapcast path

Research only, 2026-10-18 Pacific. The native adapter is currently tested against MA 2.10.7 / schema 65; live compatibility is still pending. **No MA server is installed; the user asked for help choosing a host.** No NAS/account/device operations, authentication, builds or product-file changes were performed. This checklist is for subsequent approved setup and validation, not a record of completed installation.

The existing path is **Music Assistant → Snapserver → stock Snapcast Android player (`de.badaix.snapcast`) → Portal audio**, with Immortal reading Snapcast metadata/topology and forwarding transport to MA. Native Immortal controls can use this path without introducing native Sendspin. Local references: [setup guide](~/.codex/worktrees/portal-music-player/portal-prime/docs/guides/multi-room-audio-setup.md:7), [control client](~/.codex/worktrees/portal-music-player/portal-prime/app/src/main/java/com/immortal/launcher/SnapcastControlClient.kt:19), [MA transport](~/.codex/worktrees/portal-music-player/portal-prime/app/src/main/java/com/immortal/launcher/MaControl.kt:18).

## Conditional installation plan

**Common prerequisites:** an always-on supported 64-bit CPU/OS, 2 GB RAM minimum (4 GB+ when co-hosting), and MA/HA/players on one flat LAN. Record the stable version. Docker needs host networking, persistent writable `/data` and read-only music at `/media`. UI defaults to `http://<host>:8095`; streaming starts at 8097 but can advance. Check startup logs for the actual LAN address/ports. [Official MA installation](https://www.music-assistant.io/installation/).

**Synology branch:** first confirm the exact NAS model and DSM version support Container Manager, that its CPU meets MA requirements, and that RAM/storage are adequate. Check the package's [Applied Models](https://www.synology.com/en-us/dsm/packages/ContainerManager); no model has been supplied or checked. Container Manager [Project](https://kb.synology.com/en-global/DSM/help/ContainerManager/docker_project?version=7) accepts Compose YAML; its [Network](https://kb.synology.com/en-global/DSM/help/ContainerManager/docker_network?version=7) supports the host driver.

Illustrative project, **not deployed**; replace the version and NAS paths:

```yaml
services:
  music-assistant:
    image: ghcr.io/music-assistant/server:<stable-version>
    restart: unless-stopped
    network_mode: host
    volumes:
      - /volume1/docker/music-assistant/data:/data
      - /volume1/music:/media:ro
```

Use host/local music mounts; ordinary bind mounts do not need the extra privileges required for MA to mount SMB/NFS inside its container. [MA Docker instructions](https://www.music-assistant.io/installation/#docker-image).

**HA-host branch:** if the chosen host runs **HAOS**, install the Music Assistant Server app from its app store (older interfaces call these add-ons). A standalone HA Container installation needs a separate MA container. The HA integration is optional for Immortal's direct MA connection and does not install the MA server. Add it only after music and player providers work. Use the HA machine's LAN address rather than an internal add-on address. [MA installation](https://www.music-assistant.io/installation/), [HA integration installation](https://www.music-assistant.io/integration/installation/).

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
2. **Authentication ready, performed later by the user:** finish MA first-run setup. HA ingress can bootstrap administration; standalone installation presents an initial account setup. No credentials were requested or handled here. [First run](https://www.music-assistant.io/first-run/).
3. **One real source:** add a filesystem source pointing at a small known music folder, or another user-approved provider. Confirm at least two finite-duration tracks appear and are playable; wait for source scanning. For a local-folder test, check title/artist/duration and artwork rather than assuming library import succeeded. [Filesystem sources](https://www.music-assistant.io/music-providers/local-files/).
4. **One real speaker:** later, confirm the existing stock Snapcast player is installed/running and connected to the chosen server, appears in MA, and has a stable client identity.
5. **Actual sound:** select that exact MA player and play one known track. Hear audio from the Portal; confirm advancing position and matching metadata. Exercise play, pause/resume, next/previous and volume at a modest level. A green connection badge alone does not pass this check.
6. **Immortal linkage:** require a valid `Server.GetStatus` response and a topology entry matching the Portal's LAN IP/client ID. Verify MA resolves that same client to the intended player. Check the displayed track and transport affect that room, including while another room plays different music.

Existing-code constraints to verify during subsequent implementation/setup:

- [MaWebSocket](~/.codex/worktrees/portal-music-player/portal-prime/app/src/main/java/com/immortal/launcher/MaWebSocket.kt:24) uses raw `ws` at `/ws`, not TLS `wss`. A reverse-proxy HTTPS URL is not interchangeable with the current direct LAN connection.
- [MaControl](~/.codex/worktrees/portal-music-player/portal-prime/app/src/main/java/com/immortal/launcher/MaControl.kt:46) uses the same configured host as Snapcast; separate MA/Snapserver hosts are not represented by that baseline setup.
- [Login test](~/.codex/worktrees/portal-music-player/portal-prime/app/src/main/java/com/immortal/launcher/MaControl.kt:62) treats a credential-free socket connection as success. Current official docs require authenticated API requests: verify login/auth **and a permitted command result**, not only HTTP upgrade/reachability. Installed server `/api-docs` is the version-specific reference. [Official API](https://www.music-assistant.io/api/).

## Required evidence before queue/group claims

- **Queue:** resolve the active queue for the selected player/group. Inspect `player_queues/all` and `player_queues/items`; grouped queue IDs can differ from player IDs. Test empty and two-track queues, current index/elapsed time, next/previous, seek, shuffle/repeat, and every offered edit/jump action. Read back authoritative state after each mutation and verify audible results. Paginate; keep each request at **500 items or fewer**. [MA queue API guidance](https://www.music-assistant.io/api/).
- **Groups:** require at least two connected Snapcast clients. Confirm both play the same queue, join/leave resolves the correct leader/members, and both audible output and per-room/group volume match the requested target. Re-read mappings after regrouping; test that unrelated rooms remain unaffected. Temporary groups hold the leader's queue; permanent sync groups have different ownership. Universal groups do **not** promise synchronized audio. [Official group behavior](https://www.music-assistant.io/faq/groups/).
- **Recovery/quality:** verify reconnect and paused/idle handling against actual server behavior. Record negotiated output format and check for audible dropouts under ordinary use; metadata quality labels alone do not demonstrate output quality or synchronization. No performance result is claimed by this research.

Unresolved: host choice, NAS model/DSM/CPU/RAM, HA installation type, source availability, network topology/ports, chosen MA/Snapserver versions, current authentication/API compatibility, and actual Portal playback. With no server yet, queue/group/quality claims remain **unvalidated**.

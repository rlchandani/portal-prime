# PCM receiver preview.3 — bounded connection/output split

Status: source and checks in progress; the current validation ledger is authoritative. This preview is not production activation or a final release.

The previous kill-on-focus approach disconnects a Music Assistant room. MA 2.10.7 removes disconnected members immediately and may restart a shared stream when membership changes; a stable UUID alone cannot preserve that membership across disconnects. This preview retains one native FilePlayer connection and drains 48 kHz / PCM16 / stereo bytes while a separate Android AudioTrack output is suppressed. Audio-focus Gain, reconnect and boot do not authorize new output.

Exact Snapcast native submodule: `208066e5bb3f77482a62301283a8075912a7e22c`. Its FilePlayer supports `filename=stdout`, emits raw PCM in 50 ms chunks, applies volume before writing, and flushes each chunk. Native stdout carries PCM; logs use stderr. The Java reader uses a 9,600-byte transfer buffer and the AudioTrack buffer is capped at 65,536 bytes. This is a memory bound, not a measured latency or synchronization result.

Apply the [preview.2](../preview.2/) patch to exact public Snapdroid commit `28529b546c48b7eeec5f0ae90b7ec330c4828dcb`, provide the pinned public native input, then replace the paths preserved under `source/` here. Source licensing stays upstream GPL-3.0-or-later for the combined receiver, with MIT notices for the additions. No APK, binary, keystore, credentials or private device registry is committed.

The launcher v2 contract distinguishes decoder/connection lifetime from unreleased hardware output. Stopped confirmation requires that hardware release is confirmed, focus is abandoned and playback intent is false. A monotonically increasing drained-frame counter is diagnostic only. The launcher Start path remains denied by default; no production mapping/migration or Calls/Jarvis ownership hook is activated.

Review found two concrete races: buffered PCM from an old connection could reach a replacement output, and a delayed failure could capture the newer output epoch. The scoped fix uses per-connection PCM fencing and a failure token captured by the output session. The dedicated reader lock cannot block main-thread suppression or connection retirement.

## Exact source references

- [Pinned Snapcast FilePlayer](https://github.com/snapcast/snapcast/blob/208066e5bb3f77482a62301283a8075912a7e22c/client/player/file_player.cpp)
- [Music Assistant 2.10.7 player controller](https://github.com/music-assistant/server/blob/2.10.7/music_assistant/controllers/players.py)
- [Music Assistant 2.10.7 Snapcast provider](https://github.com/music-assistant/server/blob/2.10.7/music_assistant/providers/snapcast/__init__.py)

YouTube provider authorization, audible playback, playing group continuity, real Meta Calls/Jarvis handoff, synchronization/latency and performance acceptance remain separate required gates. The new physical probe sends receiver Start/Stop/focus requests; it does not send Music Assistant playback, queue, group or volume commands. Temporary preservation of the exact verified Snapcast UUID in a fresh owned candidate is test setup, not a production migration implementation.

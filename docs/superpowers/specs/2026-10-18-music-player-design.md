# Native music player design

Approved scope: the music recommendation and functional mock accepted by the user's “do it as a goal” instruction. Preserve the mock's full, compact, photos, queue/lyrics, source-specific and setup/error behaviors.

## Architecture

- One immutable `MusicPlayerSnapshot` describes source, track identity, playback, capabilities, queue, lyrics, players and connection state.
- A shared `MusicPlayerController` owns source subscriptions and sends commands. UI presentation state never owns or restarts the audio session.
- Existing Android media sessions and MA/Snapcast remain playback foundations. Full library browsing uses an owned MA detail view for the first release.
- MA uses one bounded version-aware connection for the richer player, with request correlation, events, reconnect and confirmed state. Legacy transport remains available until the richer adapter is validated.
- Full player content stays inside the existing HomeActivity. Minimize and photos switch presentation without launching another audio app/activity.
- Progress ticking is isolated to the visible progress element; it pauses while hidden or not playing.
- Artwork download limits apply while reading, before allocation. Decode dimensions first, sample before bitmap allocation, reject malformed/chunk-overflow data, ignore stale results and keep a bounded cache.
- Settings use the declarative registry and domain side effects. Rich server/player forms share the existing settings navigation/validation system.
- Server and provider credentials stay in existing protected configuration; no logging or committing tokens, passwords, user preferences or private payloads.

## Behavioral rules

- Commands have explicit capability checks. Live radio cannot expose fictional seek/duration or track skipping; local sessions cannot expose MA groups/queue unless supported.
- Queue items use stable server IDs. Lyrics are optional; a missing lyric response never blocks playback.
- Room/group changes remain pending until source state confirms them; unsupported group combinations stay unavailable.
- Offline, auth/version mismatch, no player, not configured and call-owned audio have distinct truthful states.
- Interruption recovery resumes only playback owned by this source that was actually playing, if the user did not pause/change it during interruption.
- Track source/identity/artwork remain coherent. Late cover/queue results from a prior source/track are discarded.
- Long names, large queues and missing artwork fit a bounded responsive layout. Use original Meta neutral palette, adequate contrast, labelled icons and 48 dp minimum touch targets.
- Preserve retained-dashboard/pinned return policy. No background colors or navigation flashes introduced by the player.

## Performance targets and proof

The goal document contains the measured acceptance targets. They are initial targets, not current results.

Historical Portal+ facts: API 29, QCS605, Adreno 615, 2,782 MiB RAM; available memory on October 11 was 649.7 MiB. Do not assume that available-memory figure is current. Music Assistant runs on a suitable NAS/HA host, not on the Portal.

Validate actual audio on Family Area before promising playback. Do not assume native Sendspin, room synchronization, codecs or upstream Portal Go tests prove this Portal+ configuration.

## Boundaries

Server host and deployment access are not yet known. Earlier device-test processes are gone but restoration is not verified; check safe access before any installation/test. Accepted source and frozen companion worktrees stay unchanged.

Phone companion changes require a separate approved branch in its repository; existing frozen work is not a starting point to overwrite. Browser parity can be implemented within this Immortal branch.

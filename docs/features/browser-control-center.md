# Browser control center

Scanning **Control from your phone** opens `/remote/ui`, a bundled browser control
center with the same **Home, Remote, Photos, Apps, Settings** organization and
neutral light/dark palette as Portal Toolkit's native companion. No CDN, package
install, build step, or third-party web service is needed. Immortal serves the
HTML shell and three static files from its APK.

## Pairing and rooms

The existing QR `#pin=NNNNNN` flow and six-digit PIN exchange are retained. The
single-use PIN fragment is removed from browser history before pairing. Pairing
creates the same `RemotePairing` session used by the previous remote. Companion
management endpoints require that paired session; a Fleet bearer token cannot
substitute for it.

Existing browser storage is reused:

- `immortal_remote_devices`: `{name, base, token}` roster, normalized and
  deduplicated by endpoint; the current local session wins during roster merge.
- `immortal_remote_active`: legacy active index, retained for compatibility.
- `immortal_remote_active_base`: stable endpoint selection, preferred over an index.
- `immortal_remote_profiles`: existing named settings profiles.

Choose a room from the header, or **Settings → Rooms & options → Manage Portals**. **Add a Portal**
accepts an address, pairing link, or a Portal discovered through `/remote/devices`.
Bare local addresses default to port 8723; explicit HTTPS/VPN addresses use the
endpoint the user enters. Roster read/write continues through `/remote/roster` so
pairing one Portal can recover the other saved rooms. Sync failures are reported
by room. Forgetting removes the browser's saved pairing and syncs the surviving
roster; it does not revoke other browsers' sessions.

Session tokens appear only in existing browser storage, authenticated roster
payloads, and `Authorization: Bearer` headers. They are never placed in URLs,
HTML attributes, text content, or logs. Source credentials are sent only to the
selected Portal. Stored source passwords/API keys returned by an older server
are discarded immediately; blank editor credentials retain the saved account
only under the server's endpoint/account checks.

An unreachable Portal offers **Retry** and visible automatic retry every 30
seconds. A 401 stops polling and presents **Pair again**, requiring a fresh PIN.
The browser creates no public relay and uses the existing server LAN/CORS policy.

## Feature parity

| Area | Shipped browser workflow | Native-only or API limitation |
| --- | --- | --- |
| Home | Start slideshow; show Home; capability-gated layout, scenes, Jarvis, snapshots, activity; media | A dispatched navigation command is not proof of rendered screen completion. |
| Remote | Coalesced pointer movement, tap, discrete scroll, keyboard set/append/backspace/clear, Back/Home/Apps | Gestures and text require Immortal accessibility; a text field must have focus on the Portal. |
| Playback | Current media, previous/play-pause/next, advertised seeking, actual stream volume/mute | Volume bounds and fixed-volume state come from `/remote/nowplaying`; no media session means no transport controls. |
| Photos | Recursive all-photo listing, public-folder selection, 24-item pages, authenticated thumbnails and retryable preview | Scan limits/truncation are surfaced; older servers use the shallow folder listing with its 500-photo cap. |
| Photo writes | File picker, streamed per-file import, captured-room background transfer across tabs, confirmed file/byte ledger, cancellation, individual failure details, delete confirmation | Requires advertised import/write access. JPEG/PNG/WebP/GIF, maximum 20 MiB/file. A cancelled in-flight request can have reached the server: refresh to check. |
| Collections | Activate displayed folder/all-public-photo collection, preview slideshow on Portal | Photos stay on Portal storage; private Android paths, hidden paths, symlinks and traversal are rejected by the server. Discovered/stored folder whitespace remains part of the exact identity. |
| Apps | Search/paged installed launch; catalog compatibility/install/update; current operation status | Install acceptance is not installation success. Confirmation/dialog requirements remain on the Portal. Self-update is not a companion operation. |
| Settings | Registry categories/search, advanced-tier filtering, grouped sections, right selectors, bounds/wrap/defaults, saved-credential fields | Only controls/options returned by the Portal are offered; on-device-only picker Activities are not fabricated. |
| Group settings | Explicit This Portal/Selected rooms/All rooms scope, mixed-value indication, per-room preflight and apply/readback results | Ordinary controls require a non-empty target selection. Names, credentials, connection keys, URL/password metadata and MQTT string controls show Active Portal only and use the captured active session, including in mixed reset/profile patches. Failures are not silently broadcast or counted as success. |
| Photo sources | Folder, Immich, album picker, SMB, WebDAV, remote album, web image, built-in photos; calendar feed including removal | Connections and secret fields apply to the selected Portal only. Advanced sources remain reachable behind the browser's advanced toggle. |
| Layout | Stable-ID widget reorder/resize/add/remove, app and grid ordering/holes, existing folder assignments, save and Portal preview | Full document is preserved. New third-party widgets must first be bound on the Portal. Saves include the loaded `expectedRevision`. |
| Scenes | Ordered key/launch/media/settings/source/wait actions; edit/reorder/delete; weekday and local-time scheduling | Up to 20 scenes, 16 steps, 5-second total wait budget. Android scheduling is inexact. Saves/deletes include the reviewed SHA256 scene-list revision; stale revisions retain the draft and require review. A server without revisions must be updated before editing. Settings/source validation remains server-owned. |
| Snapshots | Export; bounded JSON import; target review; same-device restore with reviewed current revision; partial-domain failure details | Credentials, session tokens, source connections, private app data, photos and third-party widget data are excluded. Restore is not transactional across Android services. |
| Jarvis | Reported readiness/microphone/listener state, advertised models, explicit wake opt-in, configuration readback, Portal launch | Requires updated Jarvis provider. Credentials and microphone permission stay in Jarvis. Status does not prove room-distance audibility. |
| Saved presets | Run, create, edit, reorder, delete all existing navigation/app/text/wait/screensaver-or-calendar config macros | Existing preset API reports start only, not completion. Complex legacy configuration steps retain an editable JSON form. |
| Browser profiles | Existing profiles retained; named capture/apply/delete using explicit scope and per-room/domain results | New captures omit secrets and credential-bearing URLs. Profiles are browser settings sets, separate from device-bound snapshots. |
| Device tools | Explicit explanation in Settings | USB/ADB provisioning, screen mirror, APK sideload/uninstall, filesystem tools, full backups, diagnostics, OTA and Google Photos Picker require Portal Toolkit/device tooling; they cannot execute in a LAN browser. |

## Phone layout

Phone portrait at 360, 390 and 412 pixels is the primary layout. The compact
brand keeps two lines, the room picker retains a comfortable tap target, and
five fixed tabs include the bottom safe area. Photos uses a full-width Import
action, two paired secondary controls from 360 pixels, concise count/free-space
metadata and two thumbnail columns through 420 pixels. Narrower screens stack
secondary controls safely.

Settings places search, advanced, category and registry controls first in DOM
order. **Rooms & options** follows the controls and contains explicit group
scope, source/profile/device management, browser appearance and the device-tool
limitations. The current edit scope is shown above the registry. The disclosure
stays open while changing scope or selecting targets.

Modal sheets have separate fixed header/actions and internally scrolling
content. Their height follows the visual viewport when a phone keyboard opens.
Buttons and fields use 48-pixel targets; icon/stepper/tab controls retain at least
44 pixels. Switches keep a 50×30-pixel visual track inside a native label with a
44-pixel minimum hit height; padding taps forward once and disabled inputs stay
inert. Landscape, fold/tablet and desktop layouts remain responsive fallbacks.

## Request and image lifecycle

Connection reads status and media first. Layout, scenes, snapshots, Jarvis, photos,
apps and settings load only when their visible page/editor needs them. The
catalog loads only after selecting **Catalog**. Polling stops while hidden;
foreground polling does not enumerate/decode the photo library repeatedly.
Requests have AbortController cancellation and finite timeouts. Selecting a new
room invalidates the session generation, aborts page reads, dismisses pending
confirmations and cancels queued imports plus the in-flight upload request. The
per-room ledger retains confirmed, not-sent and unconfirmed file outcomes when
returning to that room. Tab navigation within the same paired room keeps imports
running without fetching an off-page gallery; completion refresh/toasts recheck
the captured session after awaiting and name the original target. Page/request generations also
reject late page loads, superseded refreshes and mutation results.

Image requests use a bounded queue: at most 24 thumbnails/icons in a page and four
concurrent loads. Thumbnail bytes have a 512 KiB ceiling. Object URLs are revoked
on page/device changes, hiding, and preview closure; late completions are revoked
without installing them in the next room's DOM. Media artwork is versioned and
limited to one current cover. Uploads send native File/Blob bodies directly to
fetch, one file at a time, without materializing a whole image collection as byte
arrays. Progress counts bytes/files confirmed by the server, not guessed network
percentages.

Registry writes reserve a shared FIFO position for every target endpoint before
network work starts. Each job captures its session and values, then runs fresh
schema preflight plus POST in that endpoint’s lane. Separate applies and active
room changes share the lanes, with at most four settings jobs in flight total;
waiting on an older write does not consume a network slot. Failed jobs release
their lane for subsequent edits.

Operations use stable operation IDs and server-advertised retry/cancel capability.
Running, failed, interrupted, partial-step errors and uncertain install fences
remain distinct. The UI never interprets a 202 acceptance or `dispatched` result
as verified visual completion.

## Files, routes, and local checks

`RemoteHtml.PAGE` remains the compatible public shell. `RemoteRoutes` serves an
exact allowlist with MIME types and `nosniff`, and caches asset bytes once per
provider. Unlisted/traversal paths do not reach AssetManager. Existing API auth,
settings definitions, backend behavior and CORS are unchanged.

| APK asset | Public route |
| --- | --- |
| `remote/control-center.css` | `/remote/assets/control-center.css` |
| `remote/control-center.js` | `/remote/assets/control-center.js` |
| `remote/core.mjs` | `/remote/assets/core.mjs` |

Run browser logic/controller regressions without a browser or network:

```sh
node --test app/src/test/js/remote-control-center.test.mjs app/src/test/js/remote-browser-workflows.test.mjs
```

The test DOM adapter executes the shipped controller script with synthetic API
responses. It verifies interaction/state behavior, not rendered browser layout.
The pure feature tests cover migration, captured target sessions, stale reads,
bounded/late thumbnails, partial room errors, 401, finite timeouts, native Blob
transport, operation honesty, exact photo identities, layout identity, and safe
profile capture. Controller regressions cover lazy/hidden reads, expired/offline
recovery, capabilities, folder editing, late mutations/listings, selected-room
results, name broadcast protection, source validation, schedule draft merges, phone Settings DOM order, fixed sheet
actions, confirmed app-list refresh, persistent folder choices and explicit
shallow-capability fallback. Review regressions reproduce and guard credential/
connection/name transport targeting (including resets, legacy profiles and peer
metadata), imports across tabs, cancellation races, late completion toasts, and
scene editor/delete conflicts during concurrent schedule changes. Missing scene
revisions block unguarded replacements. Label-padding/direct-track click tests
verify one change/write per activation and disabled guards using the offline DOM
adapter’s HTML default-action model. The actual Save-handler regression verifies
that deferred All-rooms 45-second writes settle before later 60-second writes
after switching active rooms; both servers finish at 60. A six-target regression
checks FIFO admission beyond the first four slots, captured values/sessions,
and the shared concurrency ceiling. The 16 pure feature tests and 36 controller
tests passed locally (52 total).

Focused Kotlin route tests:

```sh
./gradlew --offline :app:testDebugUnitTest \
  --tests com.immortal.launcher.RemoteStaticAssetsTest \
  --tests com.immortal.launcher.RemoteRoutesTest \
  --tests com.immortal.launcher.RemoteCompanionRoutesTest --no-daemon
```

This worker verified those local checks (13 focused Kotlin tests, zero failures). The parent owns generated-data browser
render/workflow QA (mobile, landscape, tablet and desktop), the full Android
suite/build, actual APK asset checks, live Portal tests and performance records.
No rendered/browser-device completion is claimed from local controller tests.

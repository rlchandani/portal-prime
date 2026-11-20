# Household sync and peer route security audit (2026-11-19)

Scope: every network input of Hearthpane (`com.immortal.launcher.debug`, base 1.270 / 264):
the HouseholdBus (UDP 50122), the household-signed Portal-to-Portal HTTP routes, the phone's
`/remote/*` session routes, the fleet bearer-token routes, and every client that reads a peer's
reply. Prompted by the reminders review, which found a peer reply read into memory unbounded.

Wire formats are unchanged. Every fix moves or tightens a check on the receiving side, so
Portals and phones on older versions keep working.

## Shared helpers added

| Helper | What it does |
|---|---|
| `BoundedRead` | Reads a peer reply up to a byte cap, checked while streaming (one byte past the cap stops it), and within a total time budget on top of the per-read socket timeout. A declared `Content-Length` over the cap is refused before reading. Every Portal-to-Portal client uses it. It replaces four separate ad-hoc readers. |
| `PeerRequestGate` + `HouseholdMessage.authenticate` | Checks a signed peer route's header first: HMAC, shape, freshness and the route's message type. An unsigned or forged request is refused before its body is read or hashed. The replay guard and the body-hash pin still run in each route's `verifyRequest`. |
| `HouseholdBus.contained` | Wraps work a bus handler defers to the main thread or an executor, so an exception there is logged and dropped instead of crashing the launcher. |
| `FleetHttpServer` budgets | The request head has a 10 s per-read timeout and a 15 s total budget. A text body has a 30 s total budget (408 when exceeded). An unread body is drained at most 1 MiB or 5 s. The accept queue holds at most 32 connections; the rest are shed. |

## Route table

Auth: **HH** means a household-signed `X-Immortal-Household` header (HMAC, ±120 s, nonce replay
guard). **Session** means a paired phone's bearer session. **Fleet** means the fleet bearer token.
Limits are the body cap and, for clients, the reply cap.

| Route / input | Auth | Limits | Before | After |
|---|---|---|---|---|
| HouseholdBus UDP 50122, all 31 types | HH (HMAC before JSON parse) | 1,400-char wire, 5,600 B datagram | HMAC, freshness and replay checked first. But an exception outside the handler call ended the listener for good, a socket error was never retried, and deferred handler work could crash the app | Each packet is contained; the listener rebinds with backoff; deferred work is contained (share, follow-me, settings sync, dinner card, music handoff) |
| `POST /remote/chorespeer` | HH | 512 KiB | Body read before the header was checked | Header gate first; then body, pin, merge |
| `POST /remote/listpeer` | HH | 512 KiB | Same | Header gate first |
| `POST /remote/reminderspeer` | HH | 1 MiB | Same | Header gate first |
| `POST /remote/settingspeer` | HH | 256 KiB | Same | Header gate first |
| `POST /remote/musicpeer` | HH | 1 KiB | Same | Header gate first |
| `POST /remote/callpeer` | HH | 64 KiB | Same | Header gate first |
| `POST /remote/callmsg` | HH (header first) | sealed length must equal Content-Length; size, free space, 1 upload, deadline | OK | Unchanged |
| `/remote/call/poll,send,offer` | Session + HH | 48 KiB; 2 parked long-polls | OK | Unchanged (the phone's chosen name is no longer logged) |
| `/remote/call/msg{,/get,/ack}` | Session + HH | streamed and capped; 1 KiB | OK | Unchanged |
| ChoreSync reply (client) | HH-signed reply | 1 MiB | **`readBytes()` buffered the whole reply, then checked the size** | `BoundedRead`: capped while streaming, 15 s budget |
| HouseholdListSync reply (client) | HH-signed reply | 1 MiB | **Same unbounded `readBytes()`** | `BoundedRead` |
| CallSignalClient answer (client) | HH-signed reply | 64 KiB | **Same unbounded `readBytes()`** | `BoundedRead` (25 s budget, covering answer build time) |
| ReminderSync reply (client) | HH-signed, sealed | 2 MiB | Capped (fixed in 1.270) | `BoundedRead` (adds the time budget) |
| MusicHandoffNet queue (client) | HH-signed reply | 2x codec cap | Capped | `BoundedRead` |
| SettingsSyncNet reply (client) | HH-signed reply | 256 KiB | Capped | `BoundedRead` |
| CallMessageSender ack and error (client) | HH-signed ack | 16 KiB | Capped, but silently truncated | `BoundedRead`; an oversized reply is refused |
| Intercom TCP (LanAudio) | HH handshake with deadline | 4 KiB frames; handshake and listener slots | OK | Unchanged |
| `/remote/*` session routes (list, chores, reminders, timers, dinner, CC, ...) | Session (+ remote enabled for companion routes) | 256 KiB companion; 1 MiB legacy; CC per-caller limiter | Bodies read after auth; JSON parse failures give 400 | Server-level head/body/drain budgets now apply |
| `POST /remote/pair` | none (LAN only) | 1 MiB; PIN lives 5 min, 5 tries | **A client could hold a server thread indefinitely by dribbling the head or body, or by declaring a 2 GB body that was then drained in full** | Head and body budgets, bounded drain |
| Fleet routes (`/install`, `/fs/*`, `/config`, `/action`, ...) | Fleet token (constant-time) | 1 MiB text; `/fs/write` streamed | Bodies read after auth | Server budgets apply; `/action` dream dismissal is contained |
| Whole server (6 threads) | - | - | **Unbounded accept queue; per-read timeouts only (30 s), before auth** | Bounded queue (32) and total budgets |

## Findings by severity

### High (fixed)

1. **Unbounded peer replies in ChoreSync, HouseholdListSync and CallSignalClient.** These
   clients used `readBytes()` before the size check. The same bug the reminders review found.
   A device at the signalled LAN address (any key holder can name one), or a spoofed host, could
   make every Portal buffer an endless reply and run out of memory. They now use `BoundedRead`.
2. **Slow clients exhausted Portal HTTP before auth.** The server has 6 threads. `soTimeout` was
   per read, so one byte every 29 s held a thread for hours, and a declared 2 GB body on a
   refused request was drained in full. Six such connections stopped phone control, sync and
   fleet. Head, body and drain budgets plus a bounded accept queue now prevent this.
3. **The HouseholdBus listener could die permanently.** An exception thrown during key lookup
   or decode, or a socket error, ended the only listener thread, and `start()` could not restart
   it. All household sync would then stop silently. Each packet is now contained, and the socket
   rebinds with backoff.

### Medium (fixed)

4. **Signed peer routes read their body before checking the header.** An unauthenticated LAN
   client could make a Portal read and SHA-256 up to 1 MiB per request on six routes. The header
   is now verified first.
5. **Deferred bus work outside any catch.** Share notes, follow-me presence and music, settings
   sync, the dinner card and music handoff `present` ran on the main thread or an executor with
   no try/catch. A bug triggered by one broadcast would crash every Portal at once. That work is
   now contained.

### Low (fixed)

6. **Maps keyed by claimed sender ids had no cap.** These were the list-sync `peers` map
   (now 16 most recent), the settings `joinerPushedAt` map and the timer `lastCorrection` map.
7. **The phone's user-chosen name was logged** on every call-mailbox message
   (`CallingPhoneRoute`). It now logs a 4-character id prefix.
8. **`CallMessageSender` silently truncated** an oversized ack. It is now refused.

### Fixed in the follow-up (Portal and phone together, 2026-11-19)

These were deferred above because the Portal and the phone app had to change together. They are
fixed on the launcher branch `remote-security` (on top of 1.272) and Portal Toolkit branch
`remote-security` (1.8.20). They ship together from Hearthpane 1.273.

| # | Finding | Status | Fix |
|---|---|---|---|
| 1 | **High: `GET /remote/sources` returned plaintext secrets** | Fixed | The Immich key and SMB/WebDAV passwords never leave the Portal. A read puts a mask (`••••••••`) in each secret's slot, keeps the `has…`/`…Configured` flags, and adds `immichKeyHint` (last 2 characters, only for keys of 16+ characters; never for passwords). On save, a missing, blank or masked secret means "keep the saved one" (still only for the same server and account); only a typed value replaces it. The album picker resolves a masked key the same way. Toolkit shows "•••• saved" (or "••••XY saved"), never pre-fills a secret draft, and sends a secret only when typed. |
| 2 | **Medium: household-key window open to any session** | Fixed | The window is a grant bound to the session minted by that PIN pairing: 10 minutes, one successful share or set, then spent. A refused set (bad key) doesn't spend it, a newer pairing moves it, and check-and-spend is atomic. Other sessions get `403 pair_again`. `GET` (fingerprint only) is unchanged. The current Toolkit already provisions with the session it just paired, so first pairing keeps working. |
| 3 | **Medium: sessions never expired** | Fixed | A session expires after 180 days unused; every use refreshes it (stored at most hourly). No hard cap. At upgrade, every stored token gets a record stamped "now", so no pairing breaks and the clock starts then. Tokens stay in the same `sessions` list older builds read (records sit beside it in `session_records`, without tokens), so a downgrade keeps working. Settings › Phone Remote now lists **Paired phones** (label, last use, days left) with Remove. Phones send an optional `label` when pairing (the Toolkit sends the device model, the web page "Web browser"; older Portals ignore it). `RemoteRoutes.authed` now also requires the remote to be enabled. Toolkit shows a "Pair … again" card with a **Pair again** button on 401. |
| 4 | **Low: anyone on the LAN could burn PIN attempts** | Fixed | `PairingThrottle`: 3 wrong guesses from one address make that address wait 30 s (doubling to 10 min). The code still burns after 5 wrong guesses from any mix of addresses (unchanged brute-force bound), and then every address waits 30 s (doubling to 5 min). Escalation is forgotten after 30 min of quiet or a successful pairing, so the owner is never locked out for good. `429 pair_rate_limited` with `retryAfterSec`, `scope` (`device`/`portal`) and `Retry-After`. The Portal card shows "Too many tries · Wait N s, then generate a new code" (it never auto-issues a code an attacker could keep burning); the Toolkit and web page show "Too many tries. Wait N s, then try again." |
| 5 | **Low: request-supplied fetch URLs** | Fixed | `/remote/immich/albums`: the saved Immich server, or an http(s) host whose every resolved address is RFC 1918 or IPv6 ULA (never loopback, link-local, metadata or public; mixed answers refused); `400 immich_url_not_lan`. Redirects stay on the credential's origin, the reply is capped at 4 MiB, and the fetch runs one at a time within 20 s (`504 immich_timeout`). `/install apkUrl`: HTTPS on 443 to our GitHub release hosts, F-Droid, or a host the loaded catalog uses; `400 apk_url_not_allowed`. Every redirect hop is checked against the same list (catalog downloads: never https to http), at most 5 hops; APK downloads are capped at 512 MiB and 20 min. |
| 6 | **Low: `/fs/write` had no size or free-space cap** | Fixed | Checked before a byte is read: at most 512 MiB (`413 body_too_large`) and 256 MiB must stay free after the write (`507 insufficient_storage`). |

Operator verification hooks (fleet token, which already has full control):
`phone_test_session` gains `{"householdGrant":true}` on `mint` (simulates a fresh PIN pairing),
`{"op":"age","days":N}` (debug clock for test sessions), `{"op":"pin"}` (issues a live code whose
session is a test session) and `{"op":"revoke_id","id":"…"}`.

### Closed in the last follow-up (sec-lows, on top of 1.273)

| # | Finding | Status | Fix |
|---|---|---|---|
| 7 | **Low: the `/fs/read` blocklist didn't cover `shared_prefs`** | Fixed | `/fs/read` now refuses (`403 protected_path`) the app's whole private data directory: `shared_prefs` (household key, Home Assistant token, music tokens, phone sessions and pairing, fleet token), `databases`, `files`, `no_backup` (Who's there templates, companion store, sealed stores), `app_*` and `cache`, under `/data/data/<pkg>`, `/data/user/<n>/<pkg>`, `/data/user_de/<n>/<pkg>` and the Android 11+ `data_mirror` aliases. It also refuses the fleet manifest on external storage (`Android/data/<pkg>/files/fleet`, which carries the fleet token) and the `/proc/<pid>` side doors (`fd`, `fdinfo`, `map_files`, `mem`, `pagemap`, `root`, `cwd`, `exe`, `environ`, also under `task/<tid>`). The rule is checked on both the requested and the canonical path, so `..`, symlinks and `/proc/self/root/...` can't reach a store; a path that can't be resolved is refused. What the fleet tools read stays readable: `/sdcard` (`fleetctl cat`/`pull`, dev APKs), the installed APK under `/data/app` and `/proc/self/maps`/`cmdline` (`room-fleet.py`). Who's there's `no_backup/whos_there` also joins the stores `/fs/list` and `/fs/write` refuse. Listing and writing `shared_prefs` are unchanged, so `fleet-restore.sh --prefs` from an older snapshot still works. **Consequence:** `fleet-backup.sh` can no longer copy preferences from a 1.274+ agent; it now fails at that step with a clear message and keeps the previous snapshot (README updated). |
| 8 | **Residual of 5: DNS rebinding on the Immich album check** | Fixed | `LanUrlPolicy.immichAlbumsTarget` resolves a phone-typed host once and returns the checked answer (`Target.Pinned`). `ImmichPinnedFetch` then connects to exactly those addresses through OkHttp with a pinned `Dns` (answers only that host, only with those addresses; any other name is `UnknownHostException`) and no proxy, so there is no second lookup. The URL keeps the host name, so the `Host` header, TLS SNI and certificate verification use the typed name. Redirects stay on the credential's origin and never downgrade (`PhotoSourceRedirects.redirectTarget`, at most 5), the reply is capped at 4 MiB, and the 20 s budget is also the OkHttp call timeout. The saved server (`Target.Saved`) keeps its normal path. |

Tests: `FleetSecretPathsTest` (+8: every secret store under each data-dir alias, the fleet manifest, `/proc` side doors versus `maps`/`cmdline`/`status`, the fleet tools' paths, judging the resolved target, failed resolution, list/write left open) and `ImmichPinnedFetchTest` (10, against a loopback server under an unresolvable `.test` name: `Host` header and key, pinned DNS refuses other names, a URL for another host is refused before connecting, same-origin redirect stays pinned, cross-host redirect refused, oversized reply, error status, HTTPS ClientHello carries the typed name as SNI to the pinned address, a rebinding resolver is called once and its first answer is the one used, the saved server is not pinned). `:app:testDebugUnitTest`: 7,344 tests, 0 failures. `:app:assemblePerformance`: passes. `provisioning/test_fleet_backup.py`: 23 passed.

### Still open

- **Residual (finding 5):** a private-LAN Immich URL may name the Portal's own LAN address. It needs a
  session, and the request still carries only the key the phone typed or the saved key for the saved server.
- **Info: any household-key holder can claim any device id** (no per-device keys), point peer
  clients at any RFC 1918 host:port, wipe music history, or raise a forged leak alert. That is the
  household trust model; replies are still HMAC-verified and redirects are off.
- **Info:** room names and per-room presence are logged by follow-me and music handoff. These
  are Portal names, not people. Message bodies, keys and tokens are never logged.

## Tests

- New JVM suites:
  - `BoundedReadTest` (cap, endless stream, trickle budget, declared length).
  - `PeerRequestGateTest` (all six signed routes refuse unsigned, garbage, forged, stale and
    wrong-feature headers without touching the body; a genuine request still verifies; the gate
    doesn't burn the nonce).
  - `FleetHttpServerLimitsTest` (dribbled head, dribbled body leading to 408, 2 GB unread drain,
    trickling drain).
  - `HouseholdBusResilienceTest` (a throwing packet and throwing deferred work are contained;
    the peer map stays bounded).
  - `PeerReadTripwireTest`: a source tripwire. Every file that both holds the household key and
    opens a connection must read through `BoundedRead`, and every signed route must gate before
    `bodyText`. It was confirmed to flag the old ChoreSync, HouseholdListSync and CallingSignal.

## Results

- `:app:testDebugUnitTest`: 7,219 tests, 0 failures, 0 skipped. `:app:assemblePerformance`: passes.
- Family (Portal+), on this build (performance APK, sha256 `fcd95a1b4604f170...`), then
  restored to official 1.270 (sha256 `7fdb7c82...8354368`, matching the OTA log):

| Check | Official 1.270 | This build |
|---|---|---|
| List add on Family seen by Living (Family serves `/remote/listpeer` behind the gate) | - | 1 s |
| List remove on Living pulled by Family (Family's client reads through `BoundedRead`) | - | 1 s; revisions equal |
| 6 peer routes, no header / forged header | 401 | 401 |
| 6 peer routes, unsigned 400 KB body | 401 after reading the body (chorespeer 46 ms) | 401 from the header alone (chorespeer 17 ms); 413 where the cap is smaller |
| 6 peer routes, 3 MB body | 413 | 413 |
| Head dribbled 1 byte / 8 s | still held when the probe gave up at 96 s | closed at 32 s |
| `/remote/pair` declaring a 2 GB body | 2,001 MB drained over 38 s | closed at once after about 2 MB |
| 300 junk / forged / oversized datagrams to UDP 50122, then a list change on Living | - | the bus still syncs (1 s); no crash, same PID |
| logcat after install | - | no FATAL, exchange failures, bus stops or contained handler failures |

The test sessions were revoked on Family and Living afterwards, and the test list items were
removed.

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

### Not changed (need a protocol or product decision)

- **High: `GET /remote/sources` returns the plaintext Immich key and SMB/WebDAV passwords** to
  any phone session. `*Configured` flags already exist. Removing the plaintext fields needs
  the phone remote to stop round-tripping them first; otherwise older phones would wipe the
  stored passwords on save.
- **Medium: household-key share window.** For 10 minutes after any pairing, any valid session
  (not only the new phone) can read or replace the household key via `/remote/household`.
  Tying the window to the newly paired session changes the phone's pairing flow.
- **Medium: sessions never expire,** and `RemoteRoutes.authed` doesn't check "remote enabled".
  Nothing currently turns the remote off, so this is latent.
- **Low: one global PIN guess counter.** A LAN client can burn every PIN shown and block pairing.
  Brute force remains infeasible (5 guesses per PIN, 10^6 PINs).
- **Low: `/remote/immich/albums` fetches a request-supplied URL** (session auth) and `/install
  apkUrl` (fleet). These are LAN-appliance SSRF.
- **Low: `/fs/write` has no size or free-space cap,** and the `/fs/read` blocklist doesn't cover
  `shared_prefs`. Fleet token only; the fleet token is already full control.
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

# Calling phase 2 — calls between homes, and what is left of 2a/2b

Written 2026-11-21 on branch `calling-2`. Visual spec: `mocks/big-ideas-20261117/calling/index.html`
(owner-approved). Parent plan: `docs/plans/big-three-2026-11-17.md`.

## Where we are

The backlog line "Calling phase 2 — cross-home, Portal Toolkit phone calls, groups of 4, video
messages, Grandma mode" is mostly shipped. Everything **inside one home** is done:

| Item | Shipped | Notes |
|---|---|---|
| Groups of 4 (LAN mesh, 360p, 24 fps at 4) | 1.259 (2a) | `CallingGroupModel`, `CallingGroupQuality`, `CallingMediaLink` |
| Video messages, Portal↔Portal | 1.259 / 1.265 | Encrypted at rest and in transit, ≤ 2 min, in Recent with posters |
| Phone calls via Portal Toolkit | 1.259 / 1.269 (2b) | Phones join groups, Move to phone/Portal, phone messages, opt-in ringing |
| Grandma mode + auto-answer | 1.259 / 1.265 | `CallAutoAnswerStore` set only on that Portal's own screen, 10 s countdown |

So phase 2 is really **one thing: calls between homes** (Maple House ↔ Lakeview). Everything
below is about that. In-home calls stay LAN-only on the household key and do not change.

## What crosses homes today: nothing

In-home calls rest on three LAN-only facts: a shared **household key** (HMAC) authenticates every
offer/answer (`CallSignalAuth`), SDP is POSTed straight to the other Portal's agent, and WebRTC
gathers **host candidates only, non-trickle** (`CallingMediaLink`). Across homes, all three break:
the two homes share no key, cannot reach each other's agents, and are usually both behind NAT.

## Architecture

### Trust: households, not devices (the mock's model)

- Each household has one **household identity key** (ECDSA P-256; X25519/Ed25519 are not in the
  Android 9/10 providers, see `FleetBackupCrypto`). Its private half is shared by every Portal in
  that home over the existing household-key channel and never leaves the home.
- Two homes become **linked** once: a one-time code introduces them, then both screens show the
  same **four pictures + 12 digits** (a short authentication string, SAS). If people say they
  match, each side pins the other's identity key. Mismatch → nothing kept.
- After linking, every cross-home offer/answer is **signed by the household identity key** and
  carries the DTLS fingerprint, exactly like `CallSignalAuth` does with the HMAC today. WebRTC
  refuses any DTLS peer whose certificate doesn't hash to the signed fingerprint, so a relay
  (TURN or signalling) sees only DTLS-SRTP ciphertext and IP addresses. That is the E2E anchor and
  it is in our hands, independent of which relay the owner picks.
- Signalling envelopes are additionally **sealed** (AES-GCM under an ECDH(P-256) link key derived
  at pairing), so the mailbox sees opaque blobs, a mailbox id and timing — not names, rooms or SDP.
- Permissions per linked home (mock `permsScr`): *Call us* on, *See when we're available* on
  (state only, never who is in which room), **drop-in never across homes** (not a setting),
  rooms that ring default to every room except Kids' room and Office. Auto-answer can only be set
  at the receiving Portal (unchanged `CallAutoAnswerStore` rule).

### Invite / pairing flow (relay-agnostic protocol)

Code: 9 symbols from a 32-symbol unambiguous alphabet (45 bits), shown `MPL-7Q4-K2P`, valid
10 minutes, single use. The code yields a **mailbox id** (hash of the code; the relay never sees
the code) and a MAC key that filters strangers. The SAS is what stops a man in the middle, so the
protocol is **commit-then-reveal** (ZRTP-style), which caps a MITM's chance at one SAS guess:

1. Joiner (typed the code) → `commit`: `SHA-256(joinerKey ‖ nonce ‖ name)` + code MAC.
2. Inviter → `reveal`: its public key and name + code MAC (it has not seen the joiner's key).
3. Joiner → `open`: its key, nonce, name + code MAC; inviter checks the commitment.
4. Both derive the SAS from both keys and the joiner's nonce; people compare; each side taps
   *They match* → stores a `HouseholdLink` (id, name, pinned key, verified time, permissions).

A second `commit` to the same invite is refused (single use); anything after expiry is refused.

### Transport options (owner decision D1)

| Option | Signalling | Media when NAT blocks direct | Runs | Cost (1 family) |
|---|---|---|---|---|
| **A. Cloudflare** (mock's recommendation) | Worker + Durable Object per mailbox (WebSocket, sealed blobs, TTL) | Cloudflare Realtime TURN (short-lived credentials from the Worker) | Nothing to patch | ≈ $0/month |
| B. Self-hosted | Small Kotlin/Go mailbox on a $5 VPS | coturn on the same VPS | Owner patches/monitors | ≈ $5–6/month |
| C. Tailscale/Headscale overlay | Direct over the overlay (reuse LAN code) | DERP relays | Client on every Portal (no VPN app on Portal without root is the catch) | $0 / VPS |
| D. SFU for groups | — | Server forwards media (needs SFrame for E2E) | Only if a 4-way cross-home mesh overloads the QCS605 | varies |

Recommendation: **A**, with the protocol written against a `HouseholdMailbox` interface so B is a
drop-in. Media path: STUN first (direct for most homes), TURN only as fallback; about 1 call in 5
relayed at ~1.3 GB/h (720p). C is unattractive: a Portal cannot run a VPN client without root.

What any relay can see: IPs, call timing and size, mailbox ids. What it cannot: who, which room,
SDP contents, video, sound, messages. Logs at the relay: none beyond Cloudflare's default request
metrics (owner to confirm).

### Group calls across homes

Keep the mesh, cap 4, 360p (already measured in 2a for LAN). Cross-home legs add TURN latency and
upstream limits: a 4-way mesh sends 3 encodes upstream (~3 × 0.6 Mbps at 360p). Rule: if any leg
is relayed or the uplink estimate is under 2.5 Mbps, cap cross-home groups at 3. SFU (D) only
if device measurement shows the mesh failing — not planned.

### Video messages across homes

Same sealed format as in-home (`CallMessageCrypto`, chunked AES-GCM), but the transit key comes
from the household link key, not the household key. Store-and-forward via the relay's blob store
(R2 for option A), **deleted on delivery**, hard expiry 7 days, ≤ 2 min (≈ 25 MB). The relay
holds ciphertext only. The sender keeps its own encrypted copy (newest 10, as today).

### Grandma mode across homes

Already built for in-home callers. Across homes, auto-answer may only apply to a person from a
**verified** linked household, set in person at that Portal, never in quiet hours, with the
chime + 10 s countdown + Not now. Needs only a new caller kind in `CallAutoAnswerPolicy`.

### Phones (Portal Toolkit) across homes

Walt's phone links by scanning the inviter's QR (same protocol, phone is the joiner) and then
belongs to Lakeview. A sleeping phone needs a push to ring (owner decision D4): FCM content-free
data message, or UnifiedPush/ntfy, or "rings only when Toolkit is open" (today's behaviour).

## Slices

Each slice ships alone. **No-decision slices can be built now**; the rest wait for the owner.

| # | Slice | Needs | Status |
|---|---|---|---|
| S1 | **Household pairing core**: identity key, invite code, commit/reveal protocol, SAS (4 pictures + 12 digits), link + permissions model and codec, in-memory mailbox for tests | nothing | **started this run** |
| S2 | Households UI: Households & devices list, Invite (code + QR), Join with a code (keypad), Verify (pictures), Mismatch, Permissions, Connected — behind a hidden flag until S4 | nothing (flag off) | next |
| S3 | Link store on device (encrypted at rest with `CallMessageKeys`), identity key shared to household Portals over the existing household channel, remove-household wipes keys everywhere | nothing | after S2 |
| S4 | Relay mailbox client + Worker/DO + TURN credentials | **D1 relay choice, D2 account** | blocked |
| S5 | Cross-home 1:1 call: STUN/TURN candidates, trickle, identity-signed offer/answer, honest route chip ("Direct" / "Relay") | S4 | blocked |
| S6 | Cross-home video messages (R2 blob, delete on delivery, 7-day expiry) | S4, **D3 storage** | blocked |
| S7 | Cross-home groups (mesh ≤ 4, cap 3 when relayed) | S5 + device CPU test | blocked |
| S8 | Grandma auto-answer for verified linked households | S5 | blocked |
| S9 | Phones in other homes: QR join in Toolkit, wake-up push | S4, **D4 push** | blocked |

## Owner decisions

- **D1 — Relay**: Cloudflare (Worker + Durable Object + Realtime TURN), self-hosted VPS, or other.
  Recommended: Cloudflare. Blocks S4–S9.
- **D2 — Account**: whose Cloudflare (or VPS) account, and a domain/route for the Worker.
  Agents will not create accounts or deploy anything.
- **D3 — Messages at rest on the relay**: OK to hold sealed video messages up to 7 days, deleted on
  delivery? (Alternative: messages only when both homes are online — no storage.)
- **D4 — Waking phones**: FCM content-free push, UnifiedPush/ntfy, or ring only when Toolkit is open.
- **D5 — Default rooms that ring** for a linked home (recommended: all but Kids' room and Office),
  and whether *See when we're available* defaults on (recommended: on, state only).
- **D6 — Who may link a home**: anyone at a Portal, or only behind the parent PIN (recommended: PIN,
  like phone pairing).

## Risks

- A short code alone is brute-forceable offline from the mailbox id (45 bits). It only introduces;
  the SAS check carries the security. The UI must not let people skip the pictures.
- TURN adds latency; Portal mic AEC behaviour over 150 ms+ RTT is untested.
- Identity key distribution inside a home relies on the household key being present on every
  Portal (the Mini re-pair issue from 2a shows this can lag).

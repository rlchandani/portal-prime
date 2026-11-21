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

### Invite / pairing flow (relay-agnostic protocol, v2 after the S1 security review)

**Code**: 10 symbols from the 32-symbol Crockford alphabet, shown `MPT-7Q4-K2PA`, valid 10
minutes, single use, typed on the big keypad. It is split the way magic-wormhole splits its codes:

- **Nameplate** (first 3 symbols, 15 bits) — public. It only locates the relay mailbox
  (`mailboxId = HMAC(salt, nameplate)`), so the mailbox id says nothing about the rest.
- **Secret** (last 7 symbols, 35 bits) — never sent, and never appears in anything the relay can
  test a guess against. It only feeds a **PAKE** (`HomeLinkPake`, CPace-style on P-256).

v1 (S1 as first built) made the mailbox id and a MAC key from a hash of the whole 45-bit code;
anyone who saw the mailbox id could brute-force the code offline (≈ 2^45 SHA-256, about a
GPU-hour) and race the real joiner. v2 removes every code-derived deterministic value from the
wire.

**PAKE**: both sides map (secret, mailbox) to a curve point `G` (hash-to-curve by
try-and-increment over a fixed 64 candidates, so timing doesn't depend on the secret); each sends
`x(y·G)` for a random scalar `y`; both compute `x(y_i·y_j·G)`. A share is a uniformly random point
whatever the secret, and testing a guess against a recorded transcript needs one side's scalar
(Diffie–Hellman hardness). Secret-scalar multiplications use the platform ECDH, not BigInteger.
The session key gives one MAC key per direction and a public session id `sid` that the identity
signatures cover.

**Messages** (every one carries its type and a sequence number, both covered by the session MAC;
anything out of sequence is rejected, relabelling breaks the MAC, a reflected message uses the
wrong direction key):

0. Inviter → mailbox `hello`: its PAKE share (posted when the invite is shown; covered later by
   key confirmation).
1. Joiner → `commit`: its PAKE share + `SHA-256(joinerKey ‖ nonce ‖ name)`, MAC'd under the PAKE
   key. A wrong code shows only as a failed MAC.
2. Inviter → `reveal`: its identity key and name, signed over `sid`, MAC'd.
3. Joiner → `open`: its key, nonce, name, signed over `sid` and the inviter's key, MAC'd; the
   inviter checks the commitment.
4. Both derive the SAS (four pictures + 12 digits) from both keys and the joiner's nonce; people
   compare; each side taps *They match* → stores a `HouseholdLink` (id, name, pinned key, verified
   time, permissions).

**Attempt limits (security-critical)**: the inviter burns the invite after **3** commits that fail
the MAC (`HomeLinkInviter.MAX_TRIES`, state `failedTries`, failure `TOO_MANY_TRIES`); the first
valid commit uses it up; anything after 10 minutes is refused. The joiner makes one attempt per
typed code.

### Threat model for linking

| Attacker | Sees / can do | Best attack | Bound |
|---|---|---|---|
| Passive relay or network observer | nameplate/mailbox id, both PAKE shares, MACs, keys/names after reveal | offline guessing of the secret | **none possible**: no transcript value is a function of the secret alone; a guess needs a scalar (tests `nothingDeterministicFromTheSecretCrossesTheRelay`, `aRecordedTranscriptCantConfirmEvenTheRightCode`, `theMailboxIdSaysNothingAboutTheSecret`) |
| Active relay / third party knowing the nameplate | post commits as a joiner, or a fake hello to the joiner | online guessing: one guess per join attempt | inviter: ≤ 3 tries per invite → 3 / 2^35 ≈ 1 in 11 billion; joiner: 1 guess per typed code |
| Someone who learnt the whole code (overheard the phone call) | complete a PAKE as either side, or sit in the middle | MITM | the SAS: commit-then-reveal fixes keys before either side sees the other's → one 1-in-2^64 guess at pictures+digits; people must compare (the UI never skips Verify) |
| Relay DoS | drop messages, burn an invite with 3 garbage commits | denial of service | accepted: make a new code |
| Local timing observer | PAKE map timing | partition attack on the secret | fixed 64-candidate map; secret scalars via platform ECDH |

A memory-hard KDF on the mailbox id was considered and not needed: with the nameplate public and
the secret only inside the PAKE, there is no offline target left to harden.

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
| S1 | **Household pairing core**: identity key, invite code (public nameplate + PAKE secret, 3 tries), commit/reveal protocol with typed+sequenced MAC'd messages, SAS (4 pictures + 12 digits), link + permissions model and codec | nothing | **built**, protocol v2 after security review (`HomeLink*.kt`, `HomeLinkPake.kt`) |
| S2 | Households UI: Invite (code + QR), Join with a code (keypad), Verify (pictures), Mismatch/Expired/Too many tries, Connected, **Households & devices** (`HomeLinkHouseholdsScreen`), **Permissions** (`HomeLinkPermissionsScreen`), **Mini 800×1280 layouts** for all of them (`HomeLinkDims`/`HomeLinkLayout`, ≥ 64 dp). Reachable only from the measurement-build "Link homes test" (`HomeLinkTestFlow`) until S4 | nothing (flag off) | **built** |
| S3 | Vault (`HomeLinkVault`: identity + revisioned entries, key-less tombstones) sealed at rest (`HomeLinkRepository`: own Keystore alias, `no_backup/hearthpane_homelinks`; not `CallMessageKeys`, whose delete-all forgets its alias), shared with this home's Portals (`HomeLinkSync`, `/remote/homelinkpeer`, sealed per requester/nonce/direction; phones refused), Remove wipes the home's key on every Portal | nothing | **built** |
| S4 | Relay mailbox client + Worker/DO + TURN credentials. **Security-critical relay rules**: a nameplate is claimed by one inviter `hello` (a nameplate in use is refused; the inviter makes a new code); the mailbox accepts **at most 3 joiner commits per nameplate**, then closes; mailboxes and their messages are deleted at 10 minutes or on the first completed `open`; one message ≤ 4 KB; per-IP rate limit on nameplate claims and commits. The Portal's own 3-try limit stands even if the relay is hostile — the relay limit protects against third parties who see the nameplate. | **D1 relay choice, D2 account** | blocked |
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

- The code's secret part is 35 bits: safe only because guesses are online and capped at 3 per
  invite (Portal side) and 3 per nameplate (relay side, S4). Lengthening it costs typing; the SAS
  remains a second check against anyone who learnt the code. The UI must not let people skip the
  pictures.
- TURN adds latency; Portal mic AEC behaviour over 150 ms+ RTT is untested.
- Identity key distribution inside a home relies on the household key being present on every
  Portal (the Mini re-pair issue from 2a shows this can lag).
- The household key is the trust root inside a home and paired phones hold it too. The vault
  route refuses phone ids and addresses a Portal didn't announce, but a phone holding the key could
  in principle impersonate a Portal on the LAN and receive the sealed identity. Closing that needs
  Portal-only key material (a Portal↔Portal pairing), not yet designed.
- If two Portals each make an identity before they first sync, the older one wins and the other
  Portal's links (pinned to the newer key by the other home) stop verifying. The identity is only
  made at first link and syncs at once, so this needs two first-links within seconds.

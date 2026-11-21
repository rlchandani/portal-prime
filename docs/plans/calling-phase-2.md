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

**PAKE**: both sides map (secret, mailbox) to a curve point `G` with **RFC 9380 hash_to_curve,
suite P256_XMD:SHA-256_SSWU_RO_** (`HomeLinkHashToCurve`: expand_message_xmd, simplified SWU with
Z = −10, square roots by exponent since p ≡ 3 mod 4, CMOV by arithmetic rather than branches; DST
`hearthpane-homelink-cpace-v2-P256_XMD:SHA-256_SSWU_RO_`; the RFC's Appendix J.1.1 and K.1 vectors
match exactly in `HomeLinkHashToCurveTest`). It replaced the S1 try-and-increment map after the
crypto review. Each side sends `x(y·G)` for a random scalar `y`; both compute `x(y_i·y_j·G)`.
Received shares are validated before use: exactly 32 bytes in canonical Base64 (so our own share
can't come back re-spelt), x < p, a finite point on the curve (x-only shares can't encode infinity;
cofactor 1, so no subgroup check). A share is a uniformly random point
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
typed code. The count and the invite are **memory only** (`HomeLinkInviterSession`): nothing
persists an invite, its PAKE scalar or its tries, so forcing the inviter to restart doesn't reset
the count, it ends the invite (a restarted inviter can't even accept a commit made against the old
hello), and the next invite has a new random secret. Each invite stays bounded at 3 guesses; an
attacker who keeps forcing new invites gets 3 independent 1-in-2^35 guesses per invite the family
creates, the same as without restarts. Parallel joins go through one lock against the latest
state, so they can't share a try between them (`HomeLinkInviterSessionTest`).

### Threat model for linking

| Attacker | Sees / can do | Best attack | Bound |
|---|---|---|---|
| Passive relay or network observer | nameplate/mailbox id, both PAKE shares, MACs, keys/names after reveal | offline guessing of the secret | **none possible**: no transcript value is a function of the secret alone; a guess needs a scalar (tests `nothingDeterministicFromTheSecretCrossesTheRelay`, `aRecordedTranscriptCantConfirmEvenTheRightCode`, `theMailboxIdSaysNothingAboutTheSecret`) |
| Active relay / third party knowing the nameplate | post commits as a joiner, or a fake hello to the joiner | online guessing: one guess per join attempt | inviter: ≤ 3 tries per invite → 3 / 2^35 ≈ 1 in 11 billion; joiner: 1 guess per typed code |
| Someone who learnt the whole code (overheard the phone call) | complete a PAKE as either side, or sit in the middle | MITM | the SAS: commit-then-reveal fixes keys before either side sees the other's → one 1-in-2^64 guess at pictures+digits; people must compare (the UI never skips Verify) |
| Relay DoS | drop messages, burn an invite with 3 garbage commits | denial of service | accepted: make a new code |
| Local timing observer | PAKE map timing | partition attack on the secret | RFC 9380 SSWU map, straight-line (same field operations for every input, no secret branches of our own); secret scalars via platform ECDH. Residual: `BigInteger` arithmetic and the final affine addition (`modInverse`) aren't constant-time, so timing can leak a little about the secret-derived point. Only the device holding the secret computes it, once per PAKE start, so this needs fine-grained local timing of that Portal or phone while it pairs |
| A paired phone (holds the household key) posing as a Portal | `/remote/homelinkpeer` from a spoofed Portal id at that Portal's announced address, or answering a Portal's pull in another Portal's place | fetch the vault = the household identity's private key | **closed by Portal device keys** (wire v2): a pull must be signed by a Keystore device key pinned in person, the answer is sealed to the pull's one-time ECDH key and signed by the responder's pinned key; the household key opens nothing (`HomeLinkSyncTest.aPhoneWithTheHouseholdKeyButNoPinnedDeviceKeyGetsNothing`, `anAnswerFromAnUnpinnedKeyOrTamperedCiphertextIsDropped`). Still measurement-build only until review (`HomeLinkGate`) |
| A paired phone trying to get pinned | join a Portal-pinning ceremony | add its own key to a Portal's roster | the code is shown only on a Portal's screen, ≤ 3 guesses per code (PAKE) and ≤ 3 commits per mailbox, then the pictures are compared by people standing at both Portals; phone ids are refused by `/remote/portalpin` and by the pin itself; no route or sync writes the roster |
| A household member's Portal (or anything with the household key) on the LAN during a pinning | ask for mailboxes, post commits, butt into the open/attestation swap, claim another Portal's id | pin itself in place of the Portal being paired, or swap the pinned key under a known id | nameplates are public (1,024 pinning ones; misses rate-limited), each commit is one online PAKE guess (≤ 3), after the first answered commit only that Portal may continue, the signed device id must equal the calling household id both ways, and a second key for a pinned id needs an explicit *Pair again* on screen (`PortalPinTransportTest`). Accepted: burning a code (DoS) |
| Someone who stole a recorded LAN exchange, then later a device key | replay or decrypt an old pull/answer | read an old vault | ephemeral-ephemeral ECDH per pull, never stored: forward secret; a replayed pull gets an answer sealed to a key only the original requester had |
| A Portal pinned under one id answering a pull sent to another | answer a pull the requester sent to Portal X with its own (pinned) key | feed X's requester a vault of its choosing (an old identity, resurrected links) | the answer signs the responder's household device id (`dev`, give label v3); the requester accepts it only if the signed id and the pin's id both equal the id it asked (`HomeLinkSyncTest.anAnswerFromAPinnedKeyUnderAnotherDeviceIdIsRejected`) |
| A removed Portal (sold, given away, stolen) | keeps its copy of the vault: the household identity's private key and the linked homes' keys | sign future cross-home offers as this home; pull newer vaults | **Remove** unpins it here first, stores a marker signed by this Portal's device key that every Portal which pinned this one applies before merging (so no Portal holds the new key while still pinning it), and rotates the identity a generation up: its copy matches nothing this home uses. Linked homes verified under the old key are marked *needs checking again* until the pictures are re-checked (`PortalRemovalTest`). Residual: until the marker reaches a Portal, the removed one is still a trusted Portal there; links keep their old pin on the other home's side until re-checked (S4 has no channel to tell them, see "Removing a Portal") |
| Fleet-token holder / file routes | write `no_backup/hearthpane_portal_roster` | plant a pin | directory blocked by `FleetSecretPaths`; the file is AES-GCM under its own Keystore key, so a written file fails to open and the roster starts empty (sync off) |

A memory-hard KDF on the mailbox id was considered and not needed: with the nameplate public and
the secret only inside the PAKE, there is no offline target left to harden.

### Portal-only device keys (S3b, wire v2)

The household key is the trust root for LAN signalling, but paired phones hold it, so it must not
be enough to receive the vault (the household identity's private key). What exists today that
only Portals take part in: **nothing**. Every Portal↔Portal channel (household bus, settings sync,
lists, reminders, `/remote/homelinkpeer` v1) is authenticated by the household key; `FleetAuth` is
the operator's bearer token (fleetctl and the phone remote), not a Portal↔Portal channel; the
remote roster is phone-owned. So S3b adds a Portal-only trust root and a pinning step.

- **Device key** (`PortalDeviceKey`): ECDSA P-256 made in the Android Keystore at first use,
  non-exportable, sign-only. Attestation is requested (`setAttestationChallenge`, API 24+) and
  `KeyInfo.isInsideSecureHardware` is recorded; a Keystore that refuses attestation still gets a
  key. Keystore ECDH (`PURPOSE_AGREE_KEY`) is API 31, Portals are Android 9/10, so the device key
  never does ECDH — it signs one-time ECDH keys instead.
- **Roster** (`PortalRoster`, `PortalRosterStore`): the Portals this Portal pinned, by key
  fingerprint (`kid`), public keys only, ≤ 16, sealed at rest under its own Keystore alias in
  `no_backup/hearthpane_portal_roster` (blocked from the fleet file routes). **Only the pinning
  step writes it**: no route, no sync, no phone. Pairwise: each Portal pins each Portal it shares
  with (3 Portals = 3 pinnings); transitive introduction is a possible later addition.
- **Pinning** (`PortalPinning`): the home-linking protocol run between two Portals in the same
  room with their device keys as the signing keys (`HomeLinkSigner`): code on one screen typed on
  the other, PAKE (≤ 3 tries per code), commit-then-reveal, four pictures + 12 digits compared side
  by side, *They match* pins the other's device key. A phone with the household key can see the
  messages but not the code, and anything it pins on itself isn't a Portal's Keystore key.
- **Vault pull** (`HomeLinkSyncWire` v2, signed Diffie–Hellman): the household-signed
  `home_link_rev` signal still says "my vault changed"; a Portal whose digest differs POSTs
  `{kid, eph, n, ts, d, back, sig}` — a fresh in-memory ECDH key signed by its device key — under
  the household `home_link_sync` header (outer filter, replay guard, Portal id at its announced
  address). The responder refuses unless `kid` is pinned, not its own, the signature verifies and
  `ts` is within 2 min; it answers `{kid, eph, s, sig}` with the vault AES-256-GCM under
  HKDF(ECDH(eph_req, eph_res), salt = both ephemerals + nonce), AAD binding both kids and the
  nonce, signed over the request it answers. The requester accepts only a pinned kid other than
  its own. If the puller's digest `d` differs, the responder pulls back once (`back = true` is
  never pulled back). Ephemerals are never stored: forward secret.
- **Gate** (`HomeLinkGate`): `syncs(roster)` = build switch ∧ roster not empty is the security
  condition — a Portal that pinned nobody neither signals, asks nor answers, and every request is
  checked against the roster. `VAULT_SYNC` (the build switch) stays `PORTAL_CALL_LOOPBACK` until
  this is reviewed and the pinning screens ship; after that it can be `true` everywhere because
  safety no longer depends on the build.
- **Code namespace** (`HomeLinkDomain`): pinning codes always start with `P`, home codes never
  do, and every label is domain-separated (mailbox salt, PAKE DST, sid/isk, commit, reveal, open),
  so a pinning code can never complete a home link nor a home code pin a Portal, even if a screen's
  check is bypassed (`HomeLinkDomainTest`). Home linking keeps its v2 labels.
- **Device id binding**: pinning reveal/open carry and sign the Portal's household device id (MAC'd
  too). The mailbox requires the open's id to be the id the request came from; the joiner requires
  the reveal's id to be the id it reached the host under. A pin is one key per id and one id per
  key; anything else is `NEEDS_REPAIR` and changes nothing until people choose *Pair again*. The
  vault route refuses a pinned key calling under another id (`device_mismatch`).
- **LAN transport** (`/remote/portalpin`, `PortalPinWire`/`PortalPinMailbox`/`PortalPinRoute`/
  `PortalPinJoin`, no relay): the Portal showing the code hosts the mailbox in memory; the typing
  Portal asks each household Portal it knows for that mailbox's hello, then posts commit (reply:
  reveal) and open. Rules: only our own inviter opens a box, ≤ 2 boxes, 5 min TTL (never past the
  invite), ≤ 3 commits per box, one joiner after the first answered commit, one message ≤ 4 KB,
  misses rate-limited (20/min per caller). The household `portal_pin` header (signed, fresh, not
  replayed, body-pinned, Portal id at its announced address, never a phone) is an outer filter only.
- **Attestation** (`PortalAttestation`): after the pictures the joiner swaps Keystore certificate
  chains with the host through the mailbox (once, joiner only). Verified: leaf key = pinned key,
  each cert signed by the next, root key ∈ Google's two roots (RSA-4096 and the 2025 P-384), the
  KeyDescription challenge, security level → `TEE`/`STRONGBOX`/`SOFTWARE`/`UNTRUSTED_ROOT`/
  `ABSENT`/`INVALID`, recorded on the pin and shown in the list; never blocks a pin. Validity
  dates are checked **leniently** (`PortalAttestation.check`): attestation intermediates are often
  expired on older devices, so a certificate outside its dates only sets `attestationOutOfDate` on
  the pin ("a certificate is out of date" in the list) and never changes the level. **Deferred**:
  Google's revocation list (`android.googleapis.com/attestation/status`) needs a network fetch and
  a cache; until then a revoked chain is recorded at the level it shows.
- **Legacy pins**: pins saved without a device id by older test builds can't be bound to an id, so
  the roster still drops them, and logs one non-secret count when it loads ("pair them again").
- **Screens** (measurement builds, Households & devices › This home › *Pair Portals*,
  `PortalPinFlowScreen`): paired Portals with Remove, Show a code / Type a code, Verify (pictures +
  digits), Pair again, done / failure.
- **Back key**: each pairing step and each Link homes test screen steps back one screen
  (`PortalPinFlowState.back`, `HomeLinkTestNav.back`, `HomeLinkHouseholdsState.backOrNull`); only the
  top screen leaves, never the whole Calls screen.
- **Wrong kind of code**: a complete home-link code on the pairing keypad (or a pairing code on
  Join a household) says where it belongs instead of a silently disabled button
  (`HomeLinkJoinWords.hint`).
- **Open items**: review; revocation-list check; a two-Portal device run.

### Removing a Portal (identity rotation)

"Remove" on a paired Portal (`PortalRemover.remove`) does three things, in this order:

1. **Unpin here** first, so the removed Portal can't pull what comes next from this Portal.
2. **Signed removal marker** in the vault (`PortalRemoval`: kid, household id, time, vault
   revision, signer kid, signature by this Portal's device key over all of them; label
   `hearthpane-portal-remove-v1`). Revisioned with the vault's clock like its tombstones, one per
   removed kid (higher revision wins, then the smaller signer; ≤ 32 kept), so merging is
   commutative and idempotent. Each Portal applies markers to its roster on every pulled vault
   **before merging it** (`PortalRemover.apply`) and once at startup. A marker counts only if it is
   signed by a Portal this one pinned (or by itself), names a pin held under the same household id,
   isn't about itself or its own signer, and is newer than that pin — so a Portal paired again in
   person after its removal stays pinned.
3. **Rotate the household identity**: a new key one `generation` up, made at the removal time, in
   the same vault change. `HomeLinkStoredIdentity.pick` prefers the higher generation, then the older,
   then the smaller key, so every Portal converges on it and the removed Portal's copy (older
   generation) can never win again, even if it is merged later. If two Portals remove at once and
   the winning identity is older than one of the markers (`HomeLinkVault.identityStale`), the merging
   Portal rotates again; markers dated more than a day ahead are ignored for this, so a bad clock
   can't force a rotation per merge.

**Linked homes**: each link now records which of our identities it verified (`HomeLink.verifiedAs`,
the identity's fingerprint; older links count as generation 0). After a rotation every link
verified under another identity is in `HomeLinkVault.reverifyIds`, and Households shows "This home
has a new key. Check again to keep calling." on it; *Check again* runs the pictures with the new key
and replaces the link. We deliberately do **not** send the other home a rotation statement signed
by the old key: the removed Portal holds that key and could sign one pointing at a key of its own.
Until S4 there is no channel to the other home at all, so it keeps the old pin; once S4/S5 ship,
cross-home offers are signed by the new key and fail against that pin, which the other home must show
as *needs checking again* too (never silently re-pin). A removed Portal isn't asked to forget
anything (it can't be trusted to); the rotation is what makes its copy useless.

Residuals: until a Portal receives the marker, the removed Portal is still pinned there (a removed
Portal that turns hostile in that window is a trusted Portal on that one); a Portal that didn't pin
the remover can't check its marker and keeps the removed Portal until a marker from a Portal it did
pin arrives (pin every Portal pairwise); markers are compared with pin times, so clock skew between
Portals of more than the gap between pinning and removal can keep a stale pin.

## Slices

Each slice ships alone. **No-decision slices can be built now**; the rest wait for the owner.

| # | Slice | Needs | Status |
|---|---|---|---|
| S1 | **Household pairing core**: identity key, invite code (public nameplate + PAKE secret, 3 tries), commit/reveal protocol with typed+sequenced MAC'd messages, SAS (4 pictures + 12 digits), link + permissions model and codec | nothing | **built**, protocol v2 after security review (`HomeLink*.kt`, `HomeLinkPake.kt`) |
| S2 | Households UI: Invite (code + QR), Join with a code (keypad), Verify (pictures), Mismatch/Expired/Too many tries, Connected, **Households & devices** (`HomeLinkHouseholdsScreen`), **Permissions** (`HomeLinkPermissionsScreen`), **Mini 800×1280 layouts** for all of them (`HomeLinkDims`/`HomeLinkLayout`, ≥ 64 dp). Reachable only from the measurement-build "Link homes test" (`HomeLinkTestFlow`) until S4 | nothing (flag off) | **built** |
| S3 | Vault (`HomeLinkVault`: identity + revisioned entries, key-less tombstones) sealed at rest (`HomeLinkRepository`: own Keystore alias, `no_backup/hearthpane_homelinks`; not `CallMessageKeys`, whose delete-all forgets its alias), shared with this home's Portals (`HomeLinkSync`, `/remote/homelinkpeer`), Remove wipes the home's key on every Portal. Wire v2: only to **pinned Portal device keys** (below). **Sync is measurement-build only** (`HomeLinkGate.VAULT_SYNC`) until review and S3b | nothing | **built** (sync gated) |
| S3b | **Portal-only device keys**: Keystore device key (`PortalDeviceKey`), roster (`PortalRoster`/`PortalRosterStore`) bound to device ids, signed-DH vault pull (`HomeLinkSyncWire` v2), gate on pins (`HomeLinkGate.syncs`), pinning = linking protocol with device keys (`PortalPinning`) in its own code namespace (`HomeLinkDomain`), LAN mailbox (`/remote/portalpin`), screens (`PortalPinFlowScreen`), attestation check recorded (`PortalAttestation`). answers bound to the asked device id, removal markers + identity rotation (`PortalRemoval`), lenient attestation dates. **Left**: review, two-Portal device run, revocation list | nothing | **built** (measurement builds only) |
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
  therefore moves only to **pinned Portal device keys** (S3b, "Portal-only device keys" above):
  a phone holding the household key can neither pull it nor answer a Portal's pull. Until S3b is
  reviewed and its pinning screens exist, nothing gets pinned on a real Portal and the route stays
  measurement-build only (`HomeLinkGate`). Residual: the device key is only as non-exportable as
  that Portal's Keystore (attestation chain verified and recorded, never required); a Portal that is
  removed keeps the identity it already had, so removal rotates the identity and marks linked homes
  for checking again ("Removing a Portal"). The
  alternative — never sync the private key, one identity per Portal pinned by the other home as a
  set — stays on the table if pinning proves too fiddly for families (it costs a re-link when a
  Portal joins).
- If two Portals each make an identity before they first sync, the older one wins and the other
  Portal's links (pinned to the newer key by the other home) stop verifying. The identity is only
  made at first link and syncs at once, so this needs two first-links within seconds.

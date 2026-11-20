# People in rooms (Control Center map)

The Control Center floor plan shows **where people are** — a soft pulse in each occupied room — and,
only when every consent below holds, **who** (an avatar chip with their initial and colour). The
same shows on the phone's House tab, and Security › Who's home adds the room a person was last seen
in. The spec is the big-ideas mock (`control-center`: `.mo` ripple, `.pp` chips, Who's home).

## Signals (all on-device or household-only)

| Signal | Where it comes from | Weight | Lasts |
|---|---|---|---|
| Portal presence | Each Portal's `PresenceHub` (Meta camera presence, touches); peers over the existing follow-me `presence` / `music_live` broadcast | 0.90 | until that Portal reports absent, or 5 min without a heartbeat |
| Occupancy sensor | HA `binary_sensor` (`occupancy` / `presence`) placed in the room or filed under its HA area | 0.95 | while on |
| Motion sensor | HA `binary_sensor` (`motion`) placed in the room or in its area | 0.80 | while on |
| Motion fading | the same sensor after it clears | 0.40 | 2 min after it clears |
| Recognition | Who's there? on a Portal (policy-gated, below) | 0.99 | 5 min, or until that room reports absent after the sighting |

Evidence combines by noisy-OR (`1 − Π(1 − w)`); a room under 0.30 is not drawn. Code:
`ControlCenterOccupancy.kt` (`CcOccupancyFusion`, pure, JVM-tested). The engine fuses on every
publish while someone looks (a Portal screen or a phone lease) and schedules one republish at the
next decay boundary, so a fading trail disappears on time without polling.

**Which room is a Portal in?** Its Settings › Control Center › Portals › *This Portal's room on the
plan* (`controlcenter.planRoom`, DEVICE scope, never synced). Empty = matched from its name
(`FleetConfig.name`): exact room name or HA area id, then without words like "Portal"/"Plus"/"Mini",
then the one room whose name is inside the Portal's name (ambiguous → no match). A Portal's chosen
room travels as an optional `rm` field in its follow-me `presence` broadcast (older Portals ignore it).

## The setting

`controlcenter.mapPeople` — **Show who's where on the map**: `off` · `someone` (default) · `names`.
HOUSEHOLD scope (one choice for every Portal and phone). It contains neither "whosthere" nor
"recogn", which the sync tripwire forbids.

- **Off**: no people pulses, no chips, no last room. Motion sensors still ring at their own spot, as before.
- **Someone** (default): anonymous pulses only. Recognitions are neither drawn nor sent.
- **Names**: also recognised people, under the per-person rules below.

## Who may be named (`ControlCenterPeoplePolicy.kt`, `CcPeoplePolicy`)

On the Portal that recognised the person (`shareable`), all of:
1. the map setting is Names;
2. Who's there? is on there and not paused for the household;
3. the person isn't paused (1 hour / until tomorrow);
4. they haven't set **Never recognise me on this Portal** (new per-person switch; their templates are
   then excluded from matching here, like a pause that never ends);
5. they turned on **Show my room on the home map** (new per-person switch, **off by default**, kids
   included; for a kid an adult sets it on the kid's page).

On a Portal that receives a sighting (`accept`): the map setting is Names, the sighting is under
5 min old, and if anyone with that name is enrolled on the receiving Portal they are not paused,
not opted out there and not off the map there (strictest wins).

The phone gets only what the Portal's policy already let through: `rooms[].occ` (0..1, absent when
nobody / Off) and `rooms[].ppl` (`k`, `n`, `i`, `c`) only with Names and per-person consent. CC kids
mode does not change people on the map. Nothing about people is logged except counts.

## Did recognition cross Portals before? No.

Who's there? enrolment is per Portal: people, ids and face templates live in one AES-GCM file under
a Keystore key in `noBackupFilesDir`, and nothing about recognition (results, ids, the audit) went on
the household bus, settings sync or any route. (Reminders carry a person's name and local id, but
never "who is here now".) So this feature adds **one minimal message**.

## The `room_seen` message (`RoomSeenWire.kt`)

The household bus already signs every message (HMAC-SHA256 with the household key), rejects
anything more than ±2 min old and drops replays (sender + nonce). But bus bodies are plain text on a
LAN broadcast. A name and a room are personal, so this body is also **sealed**:

- Key: `HMAC-SHA256(householdKey, "hearthpane-room-seen|v1")` (never the household key itself;
  distinct from the reminders seal key).
- AES-256-GCM, random 96-bit IV, AAD `hearthpane-room-seen|v1|<sender id>|<n>` — bound to the sending
  Portal (the envelope's `from`) and this message's own 64-bit nonce, so a sealed body cannot be
  re-sent as another Portal's.
- Body on the wire: `{"v":1, "n":"<16 hex>", "s":"<hex iv‖ciphertext‖tag>"}` — nothing readable.

Sealed fields, **one person per message** (~240 hex chars; fits one signed datagram with room to spare):

| Field | Meaning |
|---|---|
| `i` | the person's id on the sending Portal (random, per Portal) |
| `nm` | first name as enrolled (≤ 20) — receivers have no shared directory, so this is what they draw |
| `c` | colour index (0..63) |
| `fn` | the sending Portal's name |
| `rm` | its plan room id (may be empty) |
| `a` | age of the sighting in ms (< 5 min; anchored to the receiver's clock on arrival) |
| `x` | `1` = forget this person now (only `i` and `fn` are sent) |

Never sent: face data, images, match scores, other people in view, the recognising Portal's view state.

**When it is sent** (`RoomSignals.kt`): only for people `shareable` says yes to, while they are in
the recognising Portal's current Who's there? view (Personal, not awaiting "Is this you?", or Family):
once when first seen, then every 60 s while still in view. When a person stops being shareable
(paused, opted out, map switch off, deleted, recognition off, setting changed from Names) a `forget`
goes out once and receivers drop them at once. Otherwise sightings simply age out after 5 min.
With the setting at Someone or Off, nothing is sent at all.

**Who can read it**: any device holding the household key — i.e. the household's Portals (the
phone provisions the key); exactly the trust boundary the bus already uses, and nothing wider.

## Performance

Pulses are the plan's existing animated layer (layer 5 of `CcPlanCanvas`): a new pulse tone
`OCCUPIED` in `CcPlanModel.pulses`, under the same 6-pulse budget, one clock, still frame with
reduced motion. Chips are drawn in the cached "over" layer (re-recorded only when the model
changes). No occupied room → no pulses → the animated layer is not composed (no idle frames). The
phone draws rings on a separate canvas composed only while a room on that floor is occupied.

## Tests

`ControlCenterOccupancyTest`, `ControlCenterPeoplePolicyTest`, `RoomSeenWireTest`,
`ControlCenterPlanPeopleTest` (Portal); `HousePeopleTest` (Toolkit). Scope tripwires:
`SettingScopeTest` pins `controlcenter.mapPeople` household and `controlcenter.planRoom` local.

## Operator testing at night (no camera)

Fleet `whos_there_debug`: `{"seedTestPerson":true,"testOnMap":true,"enabled":true}` then
`{"simulate":"personal"}` shows the synthetic test person as recognised (no glance); the response's
`roomSignals` gives counts only. `{"simulate":"shared"}` / `{"clearTestPerson":true}` to undo.

# Control Center on the phone

Portal Toolkit's **House** tab shows the same live house as the Portals' Control Center:
the map with Live / Climate lenses, rooms and the room sheet, device controls, Security
with the full sensor list, Cameras, the Tesla screen, alerts and scenes.

## Architecture: the phone goes through its paired Portal

The phone never talks to Home Assistant. It talks to a Portal it is paired with, over the
same session-authenticated `/remote/…` surface as the rest of the phone remote, and that
Portal answers from its own Control Center engine (`CcEngine`) with its own HA token.

Why not the phone straight to Home Assistant: that needs the long-lived HA token on the
phone. It is an admin-equivalent secret for the whole house, so it stays on the Portals.

- **Live state.** Each phone read renews a 45 s lease (`ControlCenter.remoteWatch`) that holds
  the Portal's on-screen HA subscription open, even with nobody at the Portal. Banners, the
  Home dot and plan models still follow the Portal's own screen. The lease lapses on its own.
- **Polling with deltas.** The phone polls every 1.5 s while the House tab is open and the
  app is started. It sends the `rev` it holds. `CcRemoteJournal` returns only the entities that
  changed, or `unchanged` when nothing moved within the same minute. A full answer goes to
  a new phone, one that fell too far behind, or one ahead of a restarted Portal.
- **One source of truth for wording.** Alerts, the Security sensor list, people, cameras,
  scenes, room summaries and the Tesla screen are derived on the Portal by the same helpers
  its own screens use (`CcAlerts`, `CcSecurityList`, `CcTeslaCar`…). The phone draws them.
- **Failover.** The phone asks the Portal it is viewing first. If that Portal doesn't answer,
  it asks the next paired Portal, which shows the same house, and says so.

## Routes

All require `Authorization: Bearer <paired session>`. The fleet token is not accepted.
`RemoteCompanionRoutes` checks the session, the 256 KiB body limit and the JSON first.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/remote/controlcenter` | Everything (same as `POST {since:0}`) |
| `POST` | `/remote/controlcenter` | `{since, minute, want?}` → `{rev, conn, entities[] (delta), removed[], alerts[], security{}, cameras[], scenes[], rooms[], tesla?}` or `{unchanged:true, conn}` |
| `GET` | `/remote/controlcenter/plan` | The floor plan (`CcPlanJson`) |
| `POST` | `/remote/controlcenter/act` | `{entity, action, value?, on?, mode?, code?, ticket?}` or `{action:"car", key}` / `{action:"car_limit", value}` → `{ok, done}` |
| `POST` | `/remote/controlcenter/camera` | `{entity, width?}` → JPEG, fetched by the Portal from HA's `camera_proxy` |

`act` vocabulary (`CcRemoteActions`): `power`, `brightness`, `kelvin`, `color`, `fan`, `lock`,
`unlock`, `open`, `close`, `stop`, `position`, `temperature`, `hvac`, `play_pause`, `next`,
`previous`, `volume`, `run`, `alarm`, `camera_refresh`, `car`, `car_limit`. The phone never
names an HA service. The Portal builds every call with its own `CcCalls` / `CcTeslaCalls`
builders, for entities it already watches. Values out of range are refused, not clamped.

Errors: `400 bad_action|bad_entity|bad_width`, `401 unauthorized`, `404 unknown_entity|no_car`,
`405`, `409 confirm`, `429 rate_limited {retryMs}`, `502 ha_refused|snapshot_failed`,
`503 ha_not_configured|ha_unavailable`, `202 {done:false}` when HA doesn't answer within 5 s.

## Confirm first

`CcPhonePolicy` is `CcPolicy` with the household's asks (unlock, garage, valve, whole-house
scenes; disarm, security-ish scenes/scripts and car unlock/open always ask). Kids mode is a
Portal-screen setting and does not apply to the phone.

An asking call first answers `409 confirm` with `{ask, ticket, title, body, button, code, camera}`.
The phone shows its confirm:

- the system biometric prompt (fingerprint, face or screen lock) where the phone has one;
- otherwise a confirm button;
- for an alarm with a code, a PIN pad (`camera_confirm` also shows the garage camera).

Then it sends the same call with the ticket. `CcRemoteConfirm` tickets are random, single use,
valid 60 s, and bound to the caller and to that exact entity, action and value. The alarm code is
never part of the binding or of any log.

## Limits and privacy

- `CcRemoteLimiter`: token buckets per phone (state 4/s, act 3/s, camera 4/s, plan 1/s, with
  bursts), plus a Portal-wide cap of 3× one phone's budget.
- Callers are identified by a 64-bit SHA-256 prefix of the session (`RemoteFeatureRoutes.callerKey`),
  never by the token. Nothing logs tokens, tickets, codes, entity names or pictures.
- Entity attributes are allow-listed (`CcRemoteEntities`): camera `access_token` and `entity_picture`,
  GPS positions and anything else not on the list stay on the Portal.
- Snapshots are fetched without caching, capped at 1.5 MB, and passed on only if they are a
  JPEG or PNG.

## Operator verification

`fleetctl raw POST /action '{"action":"phone_test_session","op":"mint"}'` (fleet token) mints a
paired-phone session marked as a test. `{"op":"revoke"}` removes every test session and leaves
real pairings alone. Combine it with `cc_debug {"dryRun":true}` so `act` checks never move real
devices.

## Known gaps

- Live video: the phone shows fast snapshots through the Portal (about one a second in the full
  view), labelled honestly. It does not play HA's HLS stream.
- People per room and the 3D isometric view are not on the phone.
- The plan is drawn from the shared plan, but editing it stays on the Portals.

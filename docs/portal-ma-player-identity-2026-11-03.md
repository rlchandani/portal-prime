# Music Assistant player identity — 2026-11-03

Legacy Music Assistant selection could replace this Portal's exact client-ID
match with a later player sharing its display name. For example, players/all
contains portal-a named Family followed by other-b also named Family. Supplying
portal-a and Family returned other-b's track and private command target. A
renamed exact-ID player could likewise lose to a stale display-name match.
An inactive own player could show the active decoy's track; grouped players
could resolve the decoy's group.

MaControl now uses one shared complete-array selector. An exact player_id match
wins over display_name matches. When no exact ID exists, the current last name
fallback remains. Case matching, current_media fields, idle and own-track
fallback, active_group then synced_to redirection, authentication, reply
correlation and transport remain. This correction does not introduce a new
name-ambiguity policy or canonical provider-ID mapping.

Sixteen frozen methods reproduce **eight assertion failures and eight passing
controls**, with zero errors or skips, on the unchanged baseline. Four metadata
assertions call the actual internal parser; four separate command-target
assertions call the actual private resolver. They cover match ordering, renamed
identity, own versus decoy group and inactive own-player selection. Eight
control methods exercise both paths for ID-only metadata, name-only paused and
image fallback, missing-ID fallback, no match, group precedence, synced leader,
last name fallback and inactive-group own-track fallback.

The JVM resolver uses an actual MaWebSocket connected to strict generated Socket
bytes. A numeric 127.0.0.1 endpoint performs no DNS or real connection; the
fixture never calls super.connect. The handshake, masked players/all request
and correlated reply use real protocol code. Each generated socket retires and
cachedPlayerId is restored before selection assertions. Android Handler,
Looper and Log boundaries are inert. No command executor is scheduled.

All sixteen methods pass unchanged. Full gates pass **4,264 JVM tests / 442
suites**, zero failures, errors or skips, plus debug and optimized APKs, R8 and
vital lint in **1m 56s**.

Actual debug APK execution on Family Android 10 improves **20/28 to 28/28**.
Eight selection checks are corrected; twenty checks remain, comprising sixteen
product checks and four harness controls. Each variant makes **12 internal
parser calls, 12 private resolver calls and 12 generated wire connections and
closes**. Full metadata and returned targets are compared independently. The
isolated lazy executor terminates without starting a command worker. Both
accepted native parents retire and owned scratch is removed, with no excluded
execution. Initial author corrections precede the first compilation and
execution. Compilation succeeds with **three Java 8 obsolescence warnings and
a deprecation note**. Native stderr is empty.

Native selection is debug only. R8 specializes the optimized constructor and
removes metadata getters; optimized selection remains unavailable, with zero optimized
selection invocations and no added keep rules. Generated private resolver
selection does not establish a public players/cmd send, live Music Assistant
authentication, actual provider player topology, real network, decoded or
audible playback, or physical controls. No native public nowPlaying, command,
testLogin, authentication, Context or household preference operation occurs.
API28 and wider schema or group-policy acceptance remain open. Only normal
cleanup is observed; external runner failure and timeout cleanup are unverified.

Exact signed optimized **1.117/code111** is installed and fully hashed in Family
Area and Living Area. SHA-256:
`c8eafff85be1f47211d5e09089c0807bdbeacf9c212830a592bd3892de234ce1`
(25,862,702 bytes), package `com.immortal.launcher.debug`; signer matches the
retained 1.116 rollback. Both helpers exit zero and request the screensaver.

Complete captured screensaver, settings/profile, layout and source maps match
preflight. **Ten named preference states per room match: Family has ten
existing byte-identical files; Living has seven byte-identical files and three
unchanged absences.** Room names and Dev Mode remain. Strict postflight passes
without an exception, household preference edit or restore.

Family's current display was visually inspected by the coordinator: a tan
2:45 digital night clock and Tuesday November 3 date on black, with the
accessibility button. Font, accessibility, original Chrome task records,
resumed PhotoFrame, awake display and installed full SHA remain. No music
feature or provider acceptance is inferred. Living visual acceptance stays open.
Normal diagnostics and logcat requests with lines=1500 return HTTP200 in both
rooms; actual outputs contain **1,644 Family and 2,007 Living lines**, with zero
fatal or ANR markers. These are limited observations, not complete runtime
health, provider, negative authentication or playback acceptance. Original
registry hashes match; only the owned copied registry was removed.

Public manifest remains **1.83/code77**; its guard rejects local 1.117/code111
with expected exit 1. The native CLI remains unchanged. No Google phone
operation, HA change, GitHub authentication, push or publication. Original
independent audit clock **10:30:21–11:00:21 UTC** includes discovery,
implementation, gates, delivery, documentation, cleanup and commit. The broader
goal remains active. **93 tasks, 58 delivered, 25 locally verified, 3 approval,
6 parked and 1 queued**. All 92 prior task objects and their complete raw suffix
remain. Default Weather and all boundaries remain except the current room
version substitution; design approvals and publication restrictions remain.

The source-only album/duration dedupe and SMB ownership hypotheses share this
clock without implementation or a new slot. Earlier Presence, MQTT, Aurora,
ISS, TimerAlarm and bedtime findings retain their original held or exhausted
scopes. Documentation and roadmap rendering acceptance are recorded separately
in the structured proof.

Structured proof:
`mocks/portal-widget-gallery-20261102/evidence/ma-player-identity-verification.json`.

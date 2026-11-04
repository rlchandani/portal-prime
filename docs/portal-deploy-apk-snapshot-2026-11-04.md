# One selected APK throughout room deployment

The local `room-fleet.py deploy` helper now copies and hashes the APK before
contacting a room. All selected rooms receive that same private copy, even if
the original pathname is overwritten or deleted during deployment. The owned
directory is mode0700, the file mode0600, and both retire when the group attempt
ends, including failure, unexpected exceptions and KeyboardInterrupt. One extra
APK of disk space is required.

Previously the helper hashed the original file and later handed its mutable
pathname to `fleetctl`. A generated real-CLI loopback reproduction replaced A
with B during the first `/info` request: the unchanged helper uploaded B and
reported success. The candidate uploads A once and sends one install request.
The stable control also uploads A once. These are actual existing CLI requests
with synthetic install acknowledgments, not Android installer acceptance.

The frozen ten-method regression has **11 assertion failures across seven
methods and three passing controls** on the baseline, then **10 passing methods**
on the candidate. It covers replacement/deletion after hashing, changes between
rooms, group rejection/continuation, private permissions and cleanup after
normal return, exceptions and interruption. The repository test differs from
the frozen fixture only in its portable default source path.

All **199 provisioning tests** and Bash syntax checks pass. Independent review
replays the accepted baseline/candidate, confirms all22 existing deploy cases,
and checks invalid arguments before snapshot I/O. CI now runs the new regression.
An initial fixture `/var` alias assertion and an absent CLI setup path are
retained and excluded; corrected runs are the reported gates.

Installed-byte verification, acknowledgment reconciliation and optional screen
return retain their previous behavior. Streaming copy/hash is not an atomic
filesystem snapshot against writes during copying, and abrupt process death can
leave a temporary copy. No fresh Rust compilation was possible; the existing
CLI is unchanged.

This is a local tooling delivery. Both rooms retain **1.139/code133** and the
public release remains **1.83/code77**. No Android build, real deployment, phone,
owner registry, source media, provider/account, push or publication operation
occurred.

Original **08:16:54–08:46:54 UTC November4** clock includes discovery, fixture
corrections and closure; target15minutes, verified in **9.43minutes**. Earlier parked scopes and clocks
remain. Queue131 contains81 delivered/40 verified/2 approval/7 parked/1 queued,
with all130 previous task objects, their raw suffix and the Weather default
retained. Whole-product work remains active.

Structured proof and portable loopback probe:
`mocks/portal-deploy-snapshot-20261104/`.

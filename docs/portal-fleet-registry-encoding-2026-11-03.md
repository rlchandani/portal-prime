# Fleet registry encoding — 3 November 2026

The locally installed macOS arm64 Fleet CLI and the room helper now read UTF-8
registry files written by Windows PowerShell 5.1. Read-only admission accepts one
leading UTF-8 byte-order mark and preserves Unicode fields and file bytes. Endpoint
refresh uses the same decoder before its existing private atomic write. The exact
tested executable replaces the ignored local `provisioning/fleetctl`; its original
bytes remain privately available for rollback. The previous **1.113/code107** room
delivery is retained. No owner registry or device was accessed for this unit.

Original issue clock: **08:51:50–09:21:50 UTC**, including discovery, implementation,
verification, documentation, cleanup and commit. Base:
`361c09ce4657edda97c3799e970a04e5bccd8ba4`. Older parked clocks and approvals remain unchanged.

## Problem and correction

`Record-FleetInventory` in `provision.ps1` writes `Set-Content -Encoding UTF8`.
[Microsoft documents Windows PowerShell 5.1 Unicode output and UTF-8 BOM behavior](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_character_encoding?view=powershell-5.1).
The native CLI previously sent the file bytes straight to its JSON parser and
silently skipped the BOM-prefixed row. In a mixed registry, a successful `info
--device all` could consequently omit the Windows-provisioned device. The Python
helper decoded `utf-8`, retained U+FEFF, and failed with `cannot read registry JSON`.

The native file boundary now strips one exact leading EF BB BF sequence. The
Python file boundary uses `utf-8-sig`. Generic network JSON parsing and the Windows
writer are unchanged. BOM-free UTF-8, CRLF, Unicode names/serials/models/tokens and
embedded U+FEFF string data retain their meanings. Double or misplaced markers,
marker-only files, invalid UTF-8 and UTF-16 remain rejected. Existing malformed-row
CLI warning/skip and strict room-helper failure policies remain; reading does not
rewrite registry bytes or change port, schema or selector rules.

## Initial delivery: frozen reproduction and host acceptance

Ten actual-reader methods were frozen before production changes, SHA256
`c5a47c9b83659c6c44e35c02114b0fc94bc54224339c3091e1290b9fcb8a85af`. The freshly compiled unchanged CLI and unchanged helper
produce **six assertion failures /zero errors or skips**, terminal 1. Four controls
already pass. The same ten methods pass unchanged on the candidate and again on
the exact installed executable, terminal 0 and zero skips.

Generated registries exercise ordinary and mixed BOM/plain files, Unicode exact
selection, actual `GET /info` host and bearer headers, and two-target fanout to
exclusively owned ephemeral 127.0.0.1 peers. Malformed encoding controls retain a
valid sentinel and verify each reader's existing policy. All generated registry
bytes are compared after reading. Fixture headers/read/CLI/join bounds are explicit;
normal sockets, worker threads, child processes and temporary files retire.
External-runner timeout/failure cleanup was not independently fault-injected.

| Check | Actual local result |
| --- | --- |
| Fresh optimized CLI and Rust test compilation | Terminal 0; one existing `as_i64` dead-code warning on optimized compile |
| Rust / compiled HTTP / endpoint / encoding | 25 /28 /12 /10 tests pass |
| Room helper / backup / restore | 60 /23 /34 tests pass |
| Bash access behavior | 38 tests pass |
| Bash syntax | Provision, backup and restore scripts pass |
| Windows script bytes | Writer unchanged; all Windows-executed scripts remain ASCII |
| CI definition | YAML parsed; both triggers and Linux/native Windows compiled-CLI wiring checked |

All **192 Fleet host tests**, plus **38 Bash access checks**, finish terminal 0,
zero skips. Independent source review finds zero blockers. The seven Fleet suites
and supplemental checks are joined in **18.075 seconds**.
Atomic installation and acceptance were recorded at **2026-11-03T09:00:31.074938+00:00** with mode
preserved, full hash readback and no replacement scratch file. The installed copy
passes the same frozen ten methods in **0.408 seconds**.

Generated files reproduce the documented PowerShell byte format; the actual
Windows PowerShell 5.1 writer and Windows executable were not run locally. The CI
workflow adds the frozen suite to Linux and builds/runs a native Windows CLI under
the existing Windows PowerShell job. Hosted Windows/Linux execution is unobserved.
No owner registry/token copy, room/phone operation, external provider, Home Assistant
change, GitHub authentication, push or publication occurred. Android source,
version and public manifest remain byte-identical to the base.

## Artifact bindings

| Artifact | SHA256 |
| --- | --- |
| Original Rust source | `05c7218802342d60e0cb39347c38feffd3d96cbcb69d89b1ef794290e0dc7887` |
| Original helper source | `2bdc79f2ca8530cc810d9ee6c57e01683198ee8809f45b58e07dc80864ec505a` |
| Fresh baseline executable | `4e98f493036e321dfbc171db3c920e6389735d7a8f19f790be546266398c871f` |
| Candidate Rust source | `d4f7664bade9149eba4679935f4e983d933fd0359bc98b3f8f9db074b6d66544` |
| Candidate helper source | `0d56c1c4c4b0dc9cf026ba9938a7d16180980571ba619f9186ea96ec88fb8d82` |
| Candidate and installed executable | `41a6e4f98bf11f497fec3b40a983ca62a40e4d9be9a63ad6ffad17fe43bb0fae` |
| Original local executable, privately preserved | `e49b00eb1de129a4f5e75ab9091aacbfcd09293a40197556a5b6e4249cb07555` |
| Reused Rust compiler | `1ba7bb8e273bd64125f4048d2ecce530e0a6470e565ab1b3aecf0b82b532f7b1` |

Compiler: `rustc 1.99.1 (58cf8f9c3 2026-10-13)`, reused read-only. The baseline
is a 695,520-byte Mach-O arm64 executable; candidate, installed and preserved
original are 694,352-byte Mach-O arm64 executables. Raw
logs, rollback binary and review records remain under private
`/private/tmp/portal-product-audit-20261103-085150`.

## Same-clock workflow follow-through

The continued audit found two additional file reads using plain UTF-8:
`room-fleet.py::atomic_update` after successful load/authentication, and
`scripts/portal_installer_smoke.py::registered_serial` during registry admission.
Both now use `utf-8-sig`. This continues the same **08:51:50–09:21:50 UTC** encoding
clock, including the earlier delivery and all follow-through; no clock restarts.
The follow-through baseline is commit `be51159fc9148f7e281da6c64e36b8747231a7c5`.

Eight frozen refresh methods reproduce **three assertion failures**, then pass
unchanged. An owned HTTP peer verifies actual GET, Host and bearer headers and
returns the exact registered Unicode name; only service lookup is patched. The
existing IPv4 resolution, HTTP identity/authentication, full snapshot comparison
and private atomic replacement run. Before correction, the authenticated room
reported down because its file reread rejected the marker. Endpoint-only changes,
all remaining fields,0600 mode, replacement inode, unchanged authenticated
snapshot, changed token/data rejection and symlink replacement checks pass.
Injected replacement failure is actually reached and retires its private temporary
file while preserving original BOM bytes. Double/displaced markers and invalid
UTF-8 are rejected before writer creation; embedded U+FEFF data remains.

Four installer admission cases extend the existing smoke suite from19 to23 methods.
The two valid BOM cases reproduce **two assertion failures**, then pass unchanged;
all19 prior method bodies remain unchanged. Generated Unicode name and serial
lookup return only the safe identity and preserve file bytes. Malformed markers
and embedded U+FEFF controls retain existing policy; no fixture Runner command
is issued by those four cases. This proves the helper, not a live installer run.

Current gates pass **200 Fleet tests +23 smoke tests +38 Bash access checks**, all
terminal0/zero errors or skips. Eight Fleet suites, smoke/access and shell checks
are joined in 18.017 seconds. The previously installed CLI is
unchanged and passes the original frozen10 methods again. Rust source and its
existing compiled25-test artifact are unchanged; no CLI reinstall or Android
build/install is needed. New refresh tests are added to both provisioning triggers
and its Linux room step; the smoke suite is already selected by existing CI.
Hosted execution remains unobserved.

`fleet-backup-data.py` already passes bytes into `json.loads`, which detects the
leading marker; no backup/restore source change was needed. Existing backup/restore
suites pass, but explicit BOM end-to-end backup/restore acceptance remains open.
Actual mDNS, owner registry refresh, device/Windows execution and a live installer
smoke run are unverified. Normal completion and replacement-failure cleanup are
checked; crash/external-runner cleanup and universal filesystem race resistance
are unverified. No owner configuration or phone operation occurred.

The candidate selector-fanout investigation was stopped without source/test edits
when an older receipt established the same defect under the exhausted November2
23:15:37–23:45:37 clock. It receives no new slot.

| Follow-through artifact | SHA256 |
| --- | --- |
| Baseline room helper | `0d56c1c4c4b0dc9cf026ba9938a7d16180980571ba619f9186ea96ec88fb8d82` |
| Candidate room helper | `7d0ca1b4cef2ab27e7b0a4e09de3e49cdac2a220e2fbea98596fb42f7ee79a80` |
| Frozen refresh tests | `7f7ec593a3c52bbcea5c0752e7852e39cf0810a5dfdaed8ea9cf42540a57d1f8` |
| Baseline smoke source | `2791cf42daa639da13da933ac4a0cccbae66f4e8a05668d1fffa0bcd6c90e547` |
| Candidate smoke source | `7e0b6e99b1c8129c004429a76f1d39ca2ac4455d3a8b038f1a5ce3a544e310f0` |
| Frozen expanded smoke tests | `117ff911b4c2cfaa86d2ff2252602bc8a50bd8451b2f99dc8fbd4179e65b5eec` |

Follow-through raw logs/reviews are private under
`/private/tmp/portal-fleet-registry-refresh-20261103`. The first delivery's original
binary, source/test hashes and counts above remain historical evidence.

## Documentation and backlog acceptance

Strict MkDocs passes with zero warnings/errors. Browser checks at390×844 and
1600×1000 retain default Weather, list89 tasks, select this unit and the current
HTTP room-delivery unit, and show no horizontal overflow, page errors or external
requests. Current detail facts fit their cells. The served proof returns HTTP200
and matches local bytes. Both screenshots were inspected; owned browser contexts
close and the existing deliverable preview server is preserved. Final proof bytes
are checked again after recording this metadata.

The roadmap records **89 tasks:54 delivered,25 locally verified,3 approval,6 parked,
1 queued**. The existing encoding task is updated; all 88 older task objects and their raw
serialization, default Weather selection and all permission/delivery boundaries remain unchanged. The broader
autonomous goal stays active. Native Windows acceptance remains open.

# Fleet raw preference preflight — 4 November 2026

`fleet-restore.sh --prefs` now validates and privately freezes the complete raw
preference plan before any install, push, reboot or config command. Previously,
the script enumerated live files after installing apps and pushed them without
checking XML. A malformed later map could follow earlier mutations, and changing
a backup during an install or first push changed the bytes sent by later pushes.

The existing Python backup helper pins the snapshot and preference directories,
reads regular files through descriptors without following leaf symlinks, and
validates the exact bytes it copies. Maps are not serialized again: credentials,
comments, XML spelling and formatting remain byte-identical. Scratch directories
are mode 0700 and copied files/list plans are mode 0600. The shell consumes the
NUL-separated list only after complete preflight succeeds. Scratch is removed on
success, rejection and local dry-run.

`fleet_agent.xml` must be a regular file when present, but its content is neither
decoded nor copied. It remains excluded from writes to preserve the provisioned
token. A regular token-only folder is a successful no-op without reboot. Missing
or empty preference folders, XML symlinks/directories and unsafe names are
rejected before CLI calls. The normal top-level `latest` snapshot link remains
supported; the preference directory itself cannot be a link.

When `MANIFEST.txt` exists, its selected XML inventory must match the files, with
the token excluded from comparison. Duplicate/malformed inventories, unreadable
or linked manifests and known non-release packages are rejected. Legacy manifests
without package metadata retain their original release-package interpretation.
Manually assembled folders without a manifest remain supported; they cannot prove
whether an externally expected file is missing. Unicode and spaces remain literal,
including Unicode line separators inside names. Manifest parsing follows the
writer's literal newline convention and accepts CRLF.

## Verification

| Check | Result |
|---|---|
| Frozen actual Bash suite | 48 methods: original has 19 assertion failures across 11 new methods, zero errors/skips; final candidate passes all 48 |
| New coverage | 14 raw-plan methods; 34 earlier app/target/soft-config methods retained |
| Freeze witnesses | Generated CLI replaces the source during install and first push; all later pushes retain the original bytes |
| Compatibility controls | Manifestless, legacy and current snapshots, top-level `latest`, Unicode/spaces, token skip, token-only and dry-run |
| Review correction | Initial manifest `splitlines()` fails three Unicode separator subcases; literal newline correction passes all three |
| Actual existing CLI / generated HTTP | Same eight-method loopback fixture: original five failures/three controls → candidate eight passes |
| Provisioning Python | All 189 methods pass with the unchanged repository CLI, zero failures/errors/skips |
| Script Python | All 266 methods pass, zero failures/errors/skips |
| Source checks | Changed Python files parse; restore/backup/provision shell syntax and staged whitespace pass |
| Independent review | Final 48 methods pass independently; descriptor, inventory, byte fidelity, permissions and fixture scope reviewed with no remaining blocker |
| Documentation | Strict MkDocs build passes in an owned temporary environment |

The loopback fixture exercises actual Bash and `fleetctl`, authenticated generated
registry records, exact uploaded bytes/query paths, invalid-plan no-request
behavior, and stopping after a rejected push. Every fixture server and temporary
folder retires. Its reboot response is generated success, not Portal permission
evidence. Rust source and the prebuilt CLI are unchanged; `rustc` is unavailable on
this host, so no fresh Rust build is claimed. An initial broad test invocation
lacked the required CLI environment and is retained as a corrected setup error.

## Release and acceptance

| Target | Status |
|---|---|
| macOS Bash/Python restore helper | Implemented and locally verified |
| Existing repository CLI / loopback transport | Verified with generated data |
| Linux provisioning CI | Existing job discovers the expanded suite; hosted run unobserved |
| Real Portal raw restore / app reload / reboot | Unobserved; no owner restore performed |
| Android application / phone / publication | No change or action |

This validates XML syntax/map roots and freezes selected files. It does not
validate every Android preference value or make the filesystem capture atomic.
Later device writes remain separate, and an earlier write can remain applied if
a later command fails. Existing stop/no-automatic-retry behavior remains; no
rollback, Android readback, cross-package migration or reboot permission is
claimed.

The room APK remains exact optimized **1.132/code126**. Public
`version.json` remains **1.83/code77**; catalog, backup producer, Fleet filesystem
endpoint and prebuilt CLI remain exact. No owner registry, device, phone,
household media, provider/account, GitHub push or publication was involved.

The original **06:02:41–06:32:41 UTC November 4** issue window includes discovery,
fixture corrections, implementation, checks, review, documentation, cleanup and
commit. Earlier backup/app/soft-config scopes explicitly excluded raw preference
restore and keep their original clocks. The broader product goal remains active;
all previous tasks, holds and the Weather default remain.

Structured receipt:
`mocks/portal-fleet-preferences-20261104/evidence/verification.json`.

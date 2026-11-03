# Fleet endpoint port admission — 3 November 2026

The locally installed macOS arm64 Fleet CLI now rejects invalid supplied ports
before authenticated requests or registry fanout. Previously, direct ports could
silently select the default, while registry numbers could wrap or truncate to a
different endpoint. The exact tested executable replaces the ignored local
`provisioning/fleetctl`; its original bytes are retained privately for rollback.
The Portal APK remains **1.112/code106**. No room or owner registry was accessed
or changed for this unit.

Original issue clock: **08:14:58–08:44:58 UTC**, including discovery, implementation,
verification, documentation, cleanup and commit. Base:
`181eb52052e843a8774a5a39092d56c6285c49f1`. This is an independent host-tooling
delivery; older parked issue clocks and product approvals remain unchanged.

## Problem and correction

`resolve_targets` previously parsed direct `--port` values with a default-on-error
fallback. A typo, fraction or overflow could therefore send the command and token
to port 8723. Zero reached a transport failure instead of validation.
`load_registry` previously converted JSON numbers to integer and then `u16`,
allowing overflow and fractional truncation. A wrong-typed primary `agentPort`
could fall through to a valid legacy `port` alias or default.

The existing target boundary now applies these rules:

- Direct `--host --port` must parse as a nonzero `u16`; omitted port retains 8723.
  Existing valid parser forms remain accepted.
- A parsed, nonempty-token registry row selects present `agentPort`, otherwise
  legacy `port`. The selected value must be a finite, integral JSON number
  from 1 through 65535 before conversion; integral numeric values such as 43210.0
  remain valid. Missing both fields retains 8723.
- An invalid primary cannot fall through to its alias. A valid primary ignores an
  unused invalid alias, preserving precedence.
- An invalid selected registry port aborts registry loading with validation exit 2
  before any selected device receives a request, including `--device all`.
  Direct `--host` continues to bypass unrelated registry entries.

Diagnostics identify the port problem without including the token or raw JSON.
The change does not validate the entire registry: existing malformed-JSON and
missing/empty-token warning or skip behavior remains. Numeric validation uses the
existing JSON parser's `f64` representation; exact decimal lexical validation,
duplicate fields, selector collisions, hostname/token syntax and general command
deadlines are outside this unit.

## Frozen reproduction and retained behavior

The new actual-CLI suite was frozen before production changes. Twelve methods
produce **seven failing methods /35 assertion or subtest failures**, terminal 1,
zero errors and zero skips on a freshly compiled baseline. Five methods already
pass. The same file passes all 12 methods on the candidate and again on the exact
installed executable, each terminal 0 with zero skips.

Generated private registries and owned 127.0.0.1 servers demonstrate real requests
to default, wrapped, truncated and invalid-primary/valid-alias endpoints before
the fix. Another case demonstrates partial fanout to a good row before an invalid
row. Admission requires exit 2 and an `error:` diagnostic containing `port`;
an arbitrary transport error is not accepted as successful validation.
Range/type listing checks perform no HTTP.

Retained controls cover omitted defaults, lower/upper numeric selection,
integral numeric JSON, primary precedence, legacy host/port aliases,
case-insensitive named selection and explicit host bypass. Port 1/65535 are tested
through selection and listing, without privileged or high-port listeners.
Default-port network cases run only after the fixture exclusively owns 8723,
using `SO_REUSEADDR` without `SO_REUSEPORT`; they may skip when unavailable on
another host. Both accepted baseline and green executions have zero skips.

Two fixture attempts are retained privately and excluded: the first skipped a
default control because reuse was absent; the second used a weak assertion that
mistook direct-zero's transport error for validation. Both were corrected before
production edits and the accepted frozen baseline. The first compiler lookup
exited 127 because `rustc` was absent from PATH, with zero fixture executions.
The discovered existing private toolchain was then reused read-only.

Two positive Rust tests were added before production edits. The baseline CLI was
compiled from the original production source before those test-only additions;
the distinct source hashes below preserve that distinction.

## Verification and local delivery

| Target | Result | Scope and remaining acceptance |
| --- | --- | --- |
| Native host CLI | Fresh optimized compile terminal 0; exact installed artifact passes12 frozen endpoint methods | macOS arm64 and generated loopback only; no real device command |
| Rust |25 tests, terminal 0 | Includes new direct boundary/default controls |
| Compiled CLI HTTP |28 tests, terminal 0 | Actual candidate binary and generated HTTP peers |
| Room helper |60 tests, terminal 0 | Generated private fixtures |
| Backup / restore |23 /34 tests, terminal 0 | Generated fixtures; no owner backup or restore |
| CI definition | YAML parsed; both triggers, exact compiled-CLI environment and HTTP/port commands checked | Hosted Ubuntu and Windows execution not observed |
| Provisioning shell | `bash -n` passes provision, backup and restore scripts | No PowerShell execution in this unit |
| Android / rooms | Existing source/version unchanged from base; no new build or install | Last recorded room delivery remains 1.112/code106 |

All six host suites finish terminal 0, **182 tests /zero skips**, with all jobs
joined in 15.53 seconds. Candidate compilation emits one `dead_code` warning for
the existing `as_i64` helper whose production consumer was removed; the Rust-test
compile has no diagnostic. Compiler errors are absent.

After those gates and independent source review with zero blockers, the tested
binary was atomically installed at **08:23:52.798027 UTC**. Original mode and
executable permission are preserved; no replacement scratch file remains.
Full SHA256 readback matches the tested candidate. The actual installed copy
then passes the unchanged 12-method suite in 0.620 seconds.

Normal owned listener, thread, subprocess and temporary-registry retirement is
covered by the fixtures; timeout or external-runner failure cleanup was not
independently fault-injected. The existing local roadmap preview server is
preserved. No owner registry/token copy, Portal/phone inspection, Home Assistant
change, external provider, GitHub authentication, push or publication occurred.

## Documentation and backlog acceptance

Strict MkDocs succeeds with zero warnings or errors. Browser checks at 390×844
and 1600×1000 retain the default Weather selection, list all 87 tasks and select
both this unit and the previous Snapcast unit. Both pages have no horizontal
overflow, page errors or external requests; current detail facts fit their cells.
The served proof returns HTTP 200 and matches the local bytes. Both screenshots
were inspected. Owned browser contexts close, and the existing deliverable
preview server remains available.

## Artifact bindings

| Artifact | SHA256 |
| --- | --- |
| Original compiled baseline source | `75b50784186428ef5ff14975d8ec566fdd3d3572f235e89deb0cd9cd839f7362` |
| Pre-fix source plus two positive Rust tests | `1e270d34cb977d66f35faf6ca694c8b2b3fa99a163ddc1b8b58ccf000a308fc7` |
| Fresh baseline executable | `6ba472bc4d5e8f511717bb4f3bf3ba9cc1057932535d0966fbd913f020248ca4` |
| Candidate source | `05c7218802342d60e0cb39347c38feffd3d96cbcb69d89b1ef794290e0dc7887` |
| Frozen endpoint test | `e995457709be722368239c46f8685b638513a995d4584c56c7e82ad482ddad39` |
| Candidate and installed executable | `e49b00eb1de129a4f5e75ab9091aacbfcd09293a40197556a5b6e4249cb07555` |
| Original local executable, preserved privately | `c4c791adbdaef1df4db3df6c774c394ba7b950c3a2e7643835c9eb2395acecc5` |
| Reused Rust compiler | `1ba7bb8e273bd64125f4048d2ecce530e0a6470e565ab1b3aecf0b82b532f7b1` |

Compiler: `rustc 1.99.1 (58cf8f9c3 2026-10-13)`. Fresh baseline and candidate are
694,352-byte Mach-O arm64 executables. The preserved original is 694,344 bytes.
Raw logs, original binary and review records remain under the private
`/private/tmp/portal-fleet-port-admission-20261103` directory.

The roadmap records **87 tasks: 53 delivered, 24 locally verified, 3 approval,
6 parked, 1 queued**. All 86 prior task records, the default Weather selection and
existing delivery/permission boundaries are retained. The broader autonomous
goal remains active; real-device endpoint acceptance requires a separately scoped
task.

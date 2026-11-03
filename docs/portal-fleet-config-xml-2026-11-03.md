# Fleet soft restore XML — 3 November 2026

`fleet-restore.sh --config` now decodes the saved screensaver preference XML into
literal CLI arguments and validates the complete selected field structure before
any install, preference push or reboot. The former `sed` extraction sent entity
spellings such as `&amp;` in URLs, missed single-quoted/minified maps and lost
multiline/CDATA strings. It also reread the backup after earlier restore actions.

The existing Python backup helper parses the map once. It rejects malformed XML,
duplicate selected keys, wrong selected element types, nested selected values and
invalid Boolean or Android integer representations. Both endpoint argument lists
are loaded before the first CLI call. XML-decoded whitespace, Unicode and shell
metacharacters remain literal arguments through NUL-delimited private plan files.
There is no shell evaluation of saved values.

Config commands capture stdout/stderr privately. Each command must exit
successfully and return an unambiguous JSON object containing literal `ok: true`
without an `error` field. An unconfirmed result stops later commands and suppresses
`done`. Previously a CLI returning zero with a failed or contradictory envelope
could continue and report success. Scratch, response files and the copied
single-device registry are removed on exit.

## Verification

| Check | Result |
|---|---|
| Actual Bash script regressions | Ten new config methods; nine fail against the original script, with 24 failure entries; all 34 restore methods pass |
| XML and command controls | Entity/numeric-entity decoding, Unicode, literal shell text, CDATA/multiline values, minification, attribute order, single quotes and ordinary values |
| Before-action rejection | Malformed maps and selected type/value/duplicate/nesting failures prevent every earlier app/pref/config command |
| Frozen plans and target | Earlier app command replaces the XML; later calendar uses the original plan. A registry collision injected after screensaver cannot expand the calendar target |
| Acknowledgments | False, numeric/string success, duplicate fields, malformed JSON, process failure and contradictory errors stop later config commands without printing response bodies |
| Private resources | Scratch/registry directories mode 0700; plan/registry files mode 0600; scratch removed on success, failure and local dry-run |
| Python discovery | 145 provisioning tests and 226 script tests pass; zero failures, errors or skips |
| Actual existing `fleetctl` / loopback HTTP | Original 1/7 → candidate 7/7; exact decoded POST bodies, invalid-map rejection, contradictory acknowledgment and private failed-calendar response |
| Shell and diff checks | `bash -n` for restore/backup and `git diff --check` pass |
| Independent review | No blocking production finding; positive fit fixtures corrected to the endpoint-supported `fit` value |

The loopback fixture executes the existing repository CLI binary, using generated
private registry records and generated HTTP replies. Every owned server is closed
and every temporary fixture directory removed. Rust source is unchanged; this
check does not constitute fresh Rust compilation or a real device restore.
Existing provisioning CI discovers the expanded restore tests.

## Scope and retained limits

This is XML/type prevalidation and acknowledged command handling. It does not
read back every applied field or prevalidate every endpoint-specific semantic
constraint. Screensaver and calendar are separate commands: a later failure can
leave an earlier change applied. Inspect partial state before retrying; there is
no rollback or automatic retry.

The previous subset policy remains: missing maps and empty strings produce no
corresponding command, only `default` explicitly selects a source, and nonempty
folder/album values are submitted in the existing order. Endpoint precedence
can change the active source. Stored credentials, complete source fidelity,
raw preference restore, backup atomicity and cross-package migration are outside
this change.

No owner registry, device, phone, Android build, APK installation, Home Assistant,
GitHub authentication or publication was involved. The last verified room APK
remains optimized **1.102/code96**; public `version.json` remains **1.83/code77**.
This host-tooling item is locally verified; no actual owner restore is claimed.

The original **05:39:37–06:09:37 UTC** issue window includes diagnosis, regression
checks, review, documentation, cleanup and commit. The broader product goal
remains active, with design approvals, human sign-in dependency and previously
parked clocks retained.

Structured receipt:
`mocks/portal-widget-gallery-20261102/evidence/fleet-config-xml-verification.json`.

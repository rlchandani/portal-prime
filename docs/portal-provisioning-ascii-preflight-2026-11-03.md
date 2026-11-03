# Local provisioning ASCII preflight — 3 November 2026

The manual `scripts/check-provisioning.sh` command now enforces the repository's
existing Windows-script ASCII rule before optional PowerShell parsing. The previous
checker accepted all three ordinary UTF-8 Windows comment fixtures; the same frozen
cases now reject them. This is local tooling verification, with no app deployment.

## Existing rule and selected change

`AGENTS.md` requires Windows scripts to be pure ASCII. The
[provisioning workflow](https://github.com/rlchandani/portal-prime/blob/main/.github/workflows/provisioning.yml)
already checks `provisioning/provision.ps1`, `provisioning/*.bat` and
`scripts/test-provision-access.ps1`. Its comments describe why PowerShell 7 parsing
can miss the Windows PowerShell 5.1 decoding problem recorded in local commit
`0bee1ff148bdab0aa4fa6423dbd252129c30a297`.

The local command now checks that same target set with
`LC_ALL=C tr -d '\000-\177'`: any remaining bytes cause failure and identify the
filename. Tabs and CRLF remain ASCII; `provision.sh` remains exempt from the byte
rule. Existing Bash syntax checking, optional PowerShell parsing and optional-target
handling remain. The helper header describes this scope and CI's separate Windows
PowerShell 5.1 check; it does not promise to reproduce every CI job.

## Actual verification

| Check | Observed result |
| --- | --- |
| Unchanged whole-checker baseline | Seven cases: 3 assertion failures, 4 passing controls; zero errors/skips. |
| Same frozen cases after correction | Seven pass, zero failures/errors/skips; test bytes unchanged. |
| Full local Python CI command | 241 tests pass, zero failures/errors/skips. |
| `bash -n scripts/check-provisioning.sh` | Exit 0. |
| Actual local checker | Bash syntax and all four current Windows targets pass; PowerShell explicitly skipped because `pwsh` is unavailable. |

The test copies the complete production checker into disposable directories and
runs real Bash. Three ordinary Unicode comment/help fixtures cover the PS1, BAT
and test-helper targets. Four controls cover ASCII Windows files with UTF-8 Bash,
Windows CRLF/tabs, absent optional targets, and the existing Bash syntax rejection.
Only PowerShell parsing is replaced with a success stub; the byte scanner is real.
The sanitized evidence at
`mocks/portal-widget-gallery-20261102/evidence/provisioning-ascii-preflight-verification.json`
binds the actual commands, logs, source/test bytes and protected paths.

## Delivery and limits

The existing `test_portal_*.py` CI wildcard discovers the seven cases. Local
execution is verified; hosted CI and native PowerShell 7/5.1 parsing, Windows
codepage behavior and real provisioning remain unverified. Neither the publishing
command nor its signing gates changed.

The original **16:50:58–17:20:58 UTC** issue clock includes discovery, baseline and
closure; it is not reset. The earlier Fleet and version-guard contracts retain
their own boundaries. App source/version, public manifest/catalogs and the native
Fleet CLI remain unchanged. Room delivery remains the prior **1.128/code122**
snapshot, with no device recheck in this unit. Phone, provider, audio, HA and
publication restrictions remain. Photo-import and Quick-controls design decisions
remain pending; the broader improvement goal stays active.

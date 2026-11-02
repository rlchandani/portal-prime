# Fleet response integrity implementation plan

> **For agentic workers:** Use the existing isolated worktree. Parent implements the HTTP fix; the parallel worker owns only the provisioning workflow.

**Goal:** Prevent interrupted fleet responses from being reported as successful or saved as complete downloads.

**Architecture:** Keep the standard-library Rust CLI and existing command behavior. Validate the Portal server's HTTP framing at the shared request boundary; reject incomplete headers/bodies and unsupported framing before callers receive a success status. Preserve complete fixed-length binary responses and successful close-delimited responses.

**Spec:** Existing `FleetHttpServer.writeResponse`, fleet exit-code contract, and the current fleet-local-gates roadmap item.

## Constraints

- One unit: 10:22:40–10:52:40 UTC, including cleanup.
- No APK, configuration, provisioning, credential, or GitHub operations.
- Private binaries and compiler outside the tracked release paths.
- No crates or new runtime dependencies; retain existing signing and secret boundaries.
- PowerShell/Windows and reboot/device setup acceptance remain separate.

## Review focus

- Incomplete responses must fail even if the status is 200.
- Binary and Unicode lengths count bytes, not characters.
- Header names are case-insensitive; conflicting or invalid lengths are rejected.
- Close-delimited streams require clean EOF; a failed read is not completion.
- Fanout and file commands must preserve failure codes and existing local files.

## Work

- [x] Build the unchanged CLI and run its existing tests; run Bash, Calls access, ASCII and room-fleet checks.
- [x] Add real loopback request tests; observe the incomplete-response failure before changing production code.
- [x] Implement shared HTTP framing validation; run the complete Rust suite and optimized build.
- [x] Exercise the actual CLI against a local interrupted-download server, including existing destination preservation.
- [x] Parallel worker adds missing fleet source/test workflow triggers and independent Rust/Python gates.
- [x] Review integrated changes, run applicable local gates, update roadmap/operating evidence, and commit locally.

Review correction: preserve the existing server's zero-length procfs metadata
with a narrowly scoped clean-EOF diagnostic path, including `raw` query parsing.
Actual device evidence also required whole-input JSON validation so numeric-leading
diagnostic text is not discarded. Both corrections have observed red/green tests.

## Write ownership

- Parent: `provisioning/fleet.rs`, `provisioning/test_fleet_http.py`, README, this plan, delivery receipt, operating state and roadmap.
- Parallel worker: `.github/workflows/provisioning.yml`.

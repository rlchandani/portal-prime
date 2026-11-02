# Local Preview Readiness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Run the existing local readiness gates for preview versions without changing publication metadata.

**Architecture:** Add explicit `--local-preview` for debug/performance only. Record the policy and manifest version; validate the actual APK against Gradle and the trusted baseline. Default/release manifest synchronization remains required.

**Tech Stack:** Python 3.12+, existing CLI fixtures, Node and offline Gradle/Android SDK.

**Spec:** Existing local readiness contract in `docs/portal-local-readiness-delivery-2026-11-02.md`, extended by the constraints below.

## Global Constraints

- Original source-audit clock: 20:30–21:00 UTC, including cleanup.
- No GitHub, publication, deployment, device interaction or manifest modification.
- Explicit preview only; default remains strict and release variant rejects the flag.
- Preserve signer, package, exact Gradle version, source consistency, fresh reports and timeouts.
- Use existing isolated checkout and a new `codex/` branch.

## Review Focus

- Preview version divergence must be accepted only when explicitly requested.
- Release or default mode must still reject divergence.
- Stale APK and changed signing key must fail in preview mode.
- Malformed or nonpositive source version metadata must fail.
- Receipts must identify preview scope and retain published/deployed false.

### Task 1: Explicit preview policy

**Files:** Modify `scripts/portal_release_readiness.py`; create `scripts/test_portal_release_preview.py`; update delivery documentation and saved roadmap.

**Interfaces:** `version(root, local_preview=False)` retains its version dictionary. `--local-preview` is a Boolean flag; receipt adds version policy and publication-manifest version.

- [x] Write actual CLI regressions for explicit preview divergence, strict divergence and forbidden release preview; verify preview regression fails.
- [x] Implement policy and receipt without weakening remaining gates.
- [x] Run all readiness CLI fixtures; independent reviewer adds preview negative cases in a separate file.
- [x] Execute actual local preview gates against the accepted 1.85 baseline, preserving official manifest and all device state.
- [x] Record scoped results, clean up owned processes and commit.

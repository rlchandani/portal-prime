# Local release readiness implementation plan

> **For agentic workers:** execute inline with TDD and a bounded independent review.

**Goal:** Produce an honest local check receipt for a versioned, signed Portal APK
without invoking authentication, network publication, source mutation or deployment.

**Architecture:** A Python standard-library CLI runs the existing local gates,
checks the resulting APK with SDK tools, compares a mandatory local baseline,
and records source/artifact identities. A new output directory owns logs and the
JSON receipt. Failed, missing or changed evidence cannot report ready.

**Tech stack:** Python 3.12+, Git read-only operations, Node 22+, existing Gradle
wrapper, Android `aapt` and `apksigner`.

**Spec:** Product operating state, release-engineering item 8; existing
`docs/releasing.md` and the autonomous whole-product objective.

## Global constraints

- Fresh slot **08:18:38–08:48:38 UTC**, including cleanup.
- Reuse the isolated worktree on `codex/local-release-readiness-20261102`.
- No GitHub hooks, authentication, fetch, push, publication or device changes.
- Mandatory local baseline; no certificate-match fallback when evidence is absent.
- `--variant release|performance|debug`, default release; performance/debug use
  `com.immortal.launcher.debug`, release uses `com.immortal.launcher`.
- Offline Gradle; existing slideshow JS, bundled JS, Python and JVM gates.
- Version metadata, built APK identity and all signing digests must agree.
- Receipt `status=ready` means local artifact readiness only; `published=false`
  and `deployed=false`. It does not assert provisioning, phone or hardware acceptance.

## Review focus

- A failed process or missing/empty JVM report must fail, not reuse success.
- A wrong package, stale version, unsigned APK or different baseline signer fails.
- Existing output files must not be overwritten.
- Source/build inputs changing during checks must invalidate the receipt.
- Timeout must terminate the started process group and fail truthfully.

## Task 1 — checker and contract guards

**Files:** create `scripts/portal_release_readiness.py` and
`scripts/test_portal_release_readiness.py`; independent additional guards may
live in `scripts/test_portal_release_readiness_edges.py`.

**Interface:** CLI `--baseline-apk PATH --output NEW_DIRECTORY
[--repo-root ROOT] [--sdk ROOT] [--variant VARIANT] [--timeout-seconds N]`.
Write `receipt.json`, gate logs and an immutable candidate APK in the output.

- [x] Write and run failing CLI integration assertions against local fixture tools.
- [x] Implement metadata/gates/artifact/signature/source/timeout boundaries.
- [x] Run positive and negative contract tests; independent edge review.

## Task 2 — actual evidence and delivery state

- [x] Run the checker against real optimized 1.68, real SDK and accepted local baseline.
- [x] Run full Portal Python guards and required local tests; inspect terminal receipt.
- [x] Document usage, scope and failure behavior in `docs/releasing.md`.
- [x] Update operating state/roadmap with exact evidence and finish browser review.
- [x] Clean this unit’s generated artifacts and prepare the local commit.

Verification completed with actual local readiness and fourteen-detail browser
review; cleanup and local commit remain part of the same cap. No original exhausted issue was reopened.

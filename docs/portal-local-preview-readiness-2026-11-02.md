# Explicit local preview readiness

The existing readiness CLI required app and publication-manifest versions to
match for every variant. Actual local 1.85 / code79 is intentionally ahead of
the 1.83 / code77 publication manifest, so that policy blocked preview checks.

`--local-preview` now permits that difference for **debug/performance only**.
Both version declarations must still be valid. The default policy still requires
synchronization and runs `check-version-sync.sh`; the release variant refuses
the preview option. Publication metadata is not modified.

```bash
PYTHONDONTWRITEBYTECODE=1 python3 scripts/portal_release_readiness.py \
  --variant performance --local-preview \
  --baseline-apk /absolute/path/to/accepted.apk \
  --sdk /absolute/path/to/android-sdk \
  --output /absolute/path/to/new-private-receipt
```

The existing baseline signer, package, exact Gradle version, fresh JVM reports,
source/build input fingerprints, process deadline and all test/build gates
remain required. The receipt records `versionPolicy`, `releaseManifestVersion`
and actual APK identity. It continues to say `published=false` and
`deployed=false`; preview readiness grants no publication eligibility.

## Verification and review

Three core CLI regressions include explicit preview success, strict mismatch
rejection and release-preview rejection. The initial CLI lacked the flag:
one failure and one missing-receipt error, with strict mismatch control passing.
An independent worker added seven negative methods, including malformed metadata,
wrong package, stale version, different signer, source change and failed build.
All **34 readiness methods** passed after integration.

Review identified a second unvalidated manifest read. The final implementation
uses one validated parse for both receipt identities. The existing `version()`
dictionary contract is retained through a wrapper. The worker reviewed that
correction and reported no remaining concrete defect.

Actual strict mode correctly rejects the current version mismatch before any
external gate. Actual release-preview mode also rejects the option before any
external gate. Both return failed receipts, leave the manifest intact and
retain publication/deployment false.

The original source-inspection clock is **20:30–21:00 UTC** on 2026-11-02,
including cleanup. Evidence is private under
`/private/tmp/portal-local-preview-readiness-20261102`.

## Boundaries

This changes local release tooling only. It does not install another APK, change
Portal/phone configuration, establish hosted CI or publication eligibility, or
claim UI, playback or performance improvements. Both Portals retain their
previously verified 1.85 artifact. No GitHub or Home Assistant operation.

## Actual complete preview execution

The real checker finished with `status=ready` in **162,080 ms** (2m 42s).
All fourteen recorded processes exited zero. It recorded **354 slideshow JS,
715 bundled browser JS, 196 Python and 3,920 JVM /412 suites**. JVM failures,
errors and skips were zero; 412 previous XML reports were archived before the
forced fresh test task.

The optimized build gate passed in 2m 1s, with 5 tasks executed and 69 up to
date. This is a real verified build, not a claim that unchanged R8 or packaging
work was rerun. SDK metadata/signature validation and independent candidate
hashing passed. Candidate APK is byte-identical to the previously accepted 1.85:
`e2c93a3c2d2b187a5f47f45cf7120a69da4321be9590d452671c400c273fe974`.
Accepted signer:
`4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6`.

The unchanged build-input fingerprint is
`35ba9db3ac92d2c4e96380a909ef5517525ab5b81e104454fe6797f7455d7566`
over 1,373 selected files. It records parent HEAD `a69302f8` and `dirty=true`,
because this tooling patch was not yet committed when checked. Manifest
preservation, terminal checker exit and owned reviewer cleanup are verified.
Private logs/candidate/receipts are retained; no owned server or device test
was started. Public receipt:
`mocks/portal-widget-gallery-20261102/evidence/local-preview-readiness-verification.json`.

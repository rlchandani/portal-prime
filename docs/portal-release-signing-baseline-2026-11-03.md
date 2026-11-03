# Mandatory published signing baseline

The public release helper now **stops when the published APK cannot be
downloaded**. Previously, every curl failure was treated as a possible first
release and skipped the signing-key comparison before packaging, commit, push
and publication steps. This is a locally verified tooling correction; no release
was published and no Portal APK changed.

The one-line correction replaces that warning with the existing fatal-error
path. HTTP, DNS, timeout and TLS failures require restored access to the published
`immortal.apk` before retrying. No first-release exception, certificate bypass or
local substitute was added. Successful download/certificate verification and
same-key comparison retain their existing behavior.

## Verification

- Eight regression methods cover 12 cases. Before the correction, **six failures**
  demonstrate continuation after curl exits22/6/28/35/60 and a partial download.
  Afterward all eight methods/12 cases pass. Wrong-key, missing-certificate,
  invalid candidate/baseline and same-key controls retain their expected behavior.
- The tests execute only the actual signing-gate source slice in an isolated
  shell. They exclude the publishing script's authentication, version writes,
  builds, Git operations and publication commands. Generated process doubles
  provide download and certificate results; no account or network is involved.
- The complete existing CI Python discovery command passes **214 tests in
  25.592 seconds**. Its file pattern already includes the new module; the workflow
  is unchanged. `bash -n scripts/cut-release.sh` and whitespace validation pass.
- Independent review reran the focused suite and restored the old warning in
  memory: exactly six assertion failures reappear, with zero errors. All three
  reviewed source/test/release-guide hashes match the final files.
- A separate real-tool check executes the same source slice with real `curl` and
  Android SDK `apksigner`, using retained signed performance1.99/1.98 APKs through
  a loopback server. The old gate passes **2/5 expected outcomes**; the new gate
  passes **5/5**. Same-key200 continues; HTTP404, HTTP503, truncated200 and
  invalid-APK200 stop. Neither version runs the full release script. The owned
  server is closed and temporary downloaded files are removed.

The real-tool APKs are `com.immortal.launcher.debug`; this signing-only check is
not a new production release build. Actual provider TLS/DNS/timeouts, hosted CI
and the full publishing flow remain unverified.

## Release boundaries

Application source/build metadata, the catalog, public manifest and existing CI
are unchanged from `db704eb0`. The last verified room delivery remains1.99/code93;
no device was accessed in this unit. The public version-sync guard still returns1
for the deliberate local1.99/public1.83 mismatch. That guard was not weakened;
public release eligibility is not claimed.

The helper's version bump and local build occur before the signing gate. After
failure, inspect those local changes before retrying; this correction does not
introduce automatic rollback. No authentication, Git remote operation, publication,
phone control or Home Assistant change occurred.

The sanitized evidence is
`mocks/portal-widget-gallery-20261102/evidence/release-signing-baseline-verification.json`.
The original discovery clock is **04:40:49–05:10:49 UTC on 3 November**, including
source audit, regressions, independent review, real-tool checks and closure.

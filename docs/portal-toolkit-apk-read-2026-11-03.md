# Portal Toolkit APK read-failure cleanup

The verified, withdrawn ViewModel candidate closes input streams and removes its staged upload after a
copy or input-close failure. Ordinary read failures settle to the existing localized
Failed state. Cancellation and non-Exception failures propagate after owned cleanup;
null-input and disconnected behavior remain. The file reference spans the IO return
boundary so it remains available when cancellation discards that return.

The original **17:40:01–18:10:01 UTC, 2026-11-03** discovery clock is retained,
including the preceding monitor/CI work and this continuation. Earlier unadmitted
source triage is superseded by an actual frozen five-case ViewModel fixture.
Unchanged source produces **3 AssertionErrors and 2 passing controls**, no errors or
skips: partial read and close failures retain a file/Installing state, while direct
copy cancellation skips input close and cleanup. The same fixture passes after
correction. Actual full gate: **200 JVM tests / 35 suites**, zero failures/errors/skips,
plus debug assembly, **18:08:00.990376–18:08:25.516633 UTC**, exit 0.

The fixture uses actual AppsViewModel and public state/lifecycle APIs, mocked Android
and container/holder collaborators, generated streams and a private host cache.
It verifies zero manager/history/Dadb interactions. Mockito 5.11.0 is test-only;
existing configured CI discovers this test. All other ViewModel source bytes remain.
Debug APK: 13,901,710 bytes; SHA-256
`5f182d75520c220b5119cd14fca741aeae30c04b15ab4a464221d38e66fba9b8`.
Assembly/bytes do not establish signing, identity or device behavior. Existing native
strip warnings and the JVM bootstrap-classpath-sharing warning are retained.

No metadata/success branch or actual provider/transport/phone is exercised. Later
metadata-query failure remains outside this correction. No unrelated-file sentinel,
delete failure, real interruption latency or universal harness setup/teardown-fault
cleanup is proven. Handler/Main restoration and lifecycle retirement cover the
observed normal test runs. All phone/provider/publication holds remain; no version,
install, merge, push or publication change. Historical earlier receipts remain their
closure snapshots. Queue **109:69 delivered,29 verified,3 approval,7 parked,1 queued**;
all108 previous task objects/raw suffix and Weather default are retained.

Structured evidence is the repository file
`mocks/portal-widget-gallery-20261102/evidence/toolkit-apk-read-verification.json`.

The cutoff was observed at **18:10:06 UTC**, after the original18:10:01 deadline.
The integration commit was not completed in that slot. The candidate is **parked**,
with source, fixture, test dependency, APK and actual results retained privately.
Post-cutoff cleanup restores Toolkit source to clean commit `44d75050`; the
unintegrated candidate APK is removed from the normal output path. No Toolkit
commit, merge or deployment promotes this candidate. Cleanup and this honest
parking record finish after the cutoff; no within-window closure is claimed.

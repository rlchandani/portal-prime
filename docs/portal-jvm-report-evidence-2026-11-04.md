# Release check: verify individual JVM results

The existing local readiness checker now verifies each Gradle suite's individual
`testcase` results before accepting its summary. A missing or mismatched testcase
count fails the gate, as does any actual failure, error or skipped testcase even
when the header counters claim success. Complete reports remain accepted, including
an empty zero-test suite alongside real passing tests. Freshness, positive aggregate
counts, source consistency, default/preview policy, signing and deadline checks remain.
The production change is one block in `jvm_result`; the shared external Gradle fixture
now emits three literal testcase children and its edge adapter uses that same report.

Eight frozen tests exercise the actual readiness CLI with generated external
Gradle/SDK/APK processes. Six invalid reports previously produced false READY exits,
causing six intended AssertionErrors; both controls passed. All eight now pass within
the full **296 Python tests**, with zero failures/errors/skips in **34.50 seconds**.
Independent review reconstructed all three modified files exactly. Root fully read
and rehashed its16 material bindings and all476 existing Gradle XML files.

The stricter helper accepts the **4,573 actual testcase results /476 suites** from
the previous1.176 Gradle run. This uses cutoff0 to check retained-report compatibility;
it is **not a fresh JVM build or complete readiness run**. Hosted CI, signing/build,
runtime and hardware acceptance were not rerun for this Python-only change. Nested
suites, namespaces, duplicate testcase names and complete JUnit schema validation
are outside the selected direct Gradle report contract.

Both Portals retain1.176/code170; app/version/assets/provisioning are unchanged.
No install, settings, household media, account/provider, phone, auth or publication
operation occurred. Public1.83 and every prior delivered/held clock remain.
Original turn start **20:00 UTC**, target **20:15**, hard stop **20:30** include
discovery, tests, review, docs and commit without reset. Queue176 retains all175
original objects, their raw suffix, default selection and clocks. Preexisting
scripts cache is preserved; the whole-product goal remains active.

Evidence: `mocks/portal-jvm-report-evidence-20261104/evidence/`.

# Native fixture minimum API

The existing local fixture packager accepts `--min-api 28` for Android 9 fixtures.
The Python entry point accepts `min_api=28`; both interfaces retain API 29 when
omitted. Only integers from 24 through 36 are accepted, matching the repository's
minimum and compile API range. The selected D8 value appears as `minApi` in the
JSON receipt. Invalid values fail before external tools or output creation.

The caller still supplies the manifest and must set its minimum SDK consistently.
This option selects D8 compatibility; it does not rewrite the manifest or prove
runtime behavior on a device. For example, add `--min-api 28` to an existing
`scripts/portal_native_fixture.py` invocation whose manifest declares minimum SDK 28.
The helper remains local and unsigned, with its existing input capture, deadline,
archive validation, atomic output publication and owned staging cleanup.

Eight frozen public API/CLI methods exercised generated files through the existing
inert external-command fixture. Before the change, five methods failed with eight
AssertionError entries, including four invalid-value subtests; three controls
passed and there were zero setup errors. All eight pass after the change. The full
existing CI Python selection passes **304 tests**, with zero failure, error or
skip, in **36.04 seconds**. Existing packager tests and CI configuration are unchanged.

Two actual offline SDK 36 pipelines passed javac, D8, aapt2 and zipalign: API 28
selected explicitly and API 29 using the default. Binary manifest badging matches
each selected API. Both APKs have unique ZIP entries, valid DEX magic and an exact
embedded retained 1.179 debug APK. APK and source hashes match their receipts;
all owned intermediate folders were removed. No signing, installation, device,
provider or household-media operations were involved. Runtime/API 28 device
acceptance is separate from these successful local builds.

Original start **21:29:45 UTC**, target **21:44:45**, hard stop **21:59:45** on
2026-11-04 are retained without reset. This is a bounded tooling capability,
not an observed screensaver or device bug. Both rooms remain on their previously
verified **1.179/code173** delivery. All older roadmap objects, raw suffix, default
and issue clocks remain; no held issue is reopened. The whole-product goal remains
active. Evidence: `mocks/portal-native-fixture-api-20261104/evidence/`.

Independent review found no blockers. The root read the complete review
and rehashed all25 inspected material bindings before commit. Strict local documentation
build and whitespace checks pass. Verification and review completed at 21:41:43 UTC
(11.98 minutes from the original start), within the15-minute target.

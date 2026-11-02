# Optimized Portal installer fixture — 2026-11-02

Source commit `85547f76`, branch `codex/optimized-platform-qa-20261102`.
This independent test-only unit began at 14:19 UTC with a 30-minute cap.
It addresses the observed test-harness ABI failure, not a parked UI or
performance investigation.

## Correction

The debug-targeted Kotlin runner could not load `kotlin.jvm.internal.Intrinsics`
in the R8-optimized target. A standalone Java/framework-only instrumentation
now exercises public PackageInstaller APIs without depending on app, Kotlin,
AndroidX, Compose or JUnit classes. It does not launch Home, acquire UiAutomation,
stage APK contents or commit an installation.

AGP generates the instrumentation name from `testInstrumentationRunner`,
overriding a manually supplied manifest name. The opt-in Gradle property selects
the platform runner; normal builds retain `PortalUxInstrumentation`:

```sh
./gradlew :app:assembleDebugAndroidTest \
  -PportalPlatformInstallerFixture=true --no-daemon
adb -s <family-adb-transport> install -r \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <family-adb-transport> shell am instrument -w \
  com.immortal.launcher.debug.test/com.immortal.launcher.PlatformInstallerInstrumentation
```

Back up and restore the existing test APK when using this alternate test build.
For the deliberate failing case, add `-e failAfterCreate true` to `am instrument`.
An instrumentation completion code of 1 is expected for that case.

Each invocation records the exact existing owned-session set, creates a fresh
target and guard, abandons the target, and checks that the guard remains. Finally,
it removes all newly created sessions and compares the exact final ID set to the
baseline. Only fresh fixture IDs can be abandoned.

Independent static review found an enumeration-before-cleanup hazard. Cleanup
now attempts abandonment without requiring successful session enumeration first,
then verifies removal. The revised review passed.

## Family verification

The actual optimized **1.76 / code70** application stayed installed throughout.
Two normal runs each passed **15 checks, zero failures**. The intentionally
interrupted run correctly reported failure, with five unreached/failed checks,
while cleanup and exact baseline restoration passed. Each run created two
sessions and left the owned-session count at **0 → 0**.

Instrumentation wall times were 3,320.346ms, 1,712.939ms and 1,529.353ms for normal,
injected failure and repeat respectively. These include test-process startup and
are not app-interaction or installer performance benchmarks.

Full before/after application APK readbacks match:
`85ae519f439ab0d6e0f66565b6bfa291ae5ab470efea9fe9bb75e0869ba96fcd`.
The original test APK was fully restored and matches:
`b318d39ff76ce0ee59fce2bff4164745da6da28e48d4ed5ffcfa6dcc2635c136`.
Complete settings profiles and screensaver configuration compare equal. The
existing Chrome task remains listed; browser foreground, process retention,
sign-in and music continuity are not established by that observation.

The opt-in test APK assembled and its generated instrumentation, matching signer
and Java bytecode dependency boundary were checked. The normal debug/test builds
also passed and the normal generated runner was confirmed. The JVM gate remained
up-to-date and green: **3,858 tests / 405 suites**, zero failures/errors/skips.
No fresh JVM execution or new production APK build/deployment is inferred.

## Limits

This proves the new framework fixture can run against Family's optimized API29
runtime. It does not make the existing Kotlin UI runner optimized-compatible.
The guard is a fresh empty owned session, not a concurrent valid installation.
No real timeout, successful/pending confirmation, API28, Living native test,
physical phone, UX acceptance or frame-rate verdict is claimed.

Product sources, version, R8 rules, signing, provisioning and Meta OS settings
are unchanged. Living received no fixture or app update. Private logs, device
payloads and APK copies remain outside the public repository. No account
automation, Home Assistant deployment or GitHub authentication/publication was
performed.

The selected public receipt is
`mocks/portal-widget-gallery-20261102/evidence/optimized-platform-qa-verification.json`.

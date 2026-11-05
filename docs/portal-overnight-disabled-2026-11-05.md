# Disabled overnight-start delivery

Delivered optimized **1.196/code190** to both rooms, with complete installed APK byte/hash readback and one install attempt each.

An overnight-start broadcast that was already queued could still request screen-off or a night clock after the user disabled the overnight feature. The start handler now checks current overnightEnabled before entering rest. The existing scheduleOvernight call remains unconditional, so disabled deliveries still cancel both owned alarms. No timing, user-session, idle, timer, audio or storage policy changes.

Seven frozen JVM methods improved from **five failures/two controls to seven passes**. Tests use the actual receiver, scheduler and config with generated preferences and captured Android boundaries. They cover disabling after arming, initially disabled dark and night-clock modes, independent sleep-timer settings, both alarm cancellations, enabled dark sleep and absent admin. Every delivery retains generated preference contents/write count. Baseline failures include four assertions and one Mockito interaction verification.

Four frozen cases through the **actual debug APK receiver on Family API29** improved from **three disabled boundary mismatches/one enabled control to four passes**. The generated Context denies resolver and DPM service access and halts at the actual scheduleOvernight config read before real settings, services, alarms or PendingIntents. This proves rest-entry admission only; it does not prove physical sleep, wake, night-clock rendering or native cancellation. Host and native vectors differ. Class origins and identical Java/JAR/driver are verified; URL opens are zero and both owned scratch paths were removed.

All **4,695 JVM tests/489 suites**, debug/optimized packaging, R8 and vital checks passed in **112.17 seconds**. Independent source review passed and root rehashed all22 bindings. All23 source assets and the signing certificate match1.195. Public manifest1.83 remains.

All43 Family/30 Living preference files, file sets and four complete settings/source/layout maps were preserved. Family font, accessibility, original Chromium activity, resumed frame and awake state were preserved; both normal-frame requests passed. Captured diagnostics are scoped samples, not universal crash freedom. Copied registries were removed and owner registry hashes match.

[Generated delivery summary](portal-delivery-summary-1.196.md) and `mocks/portal-overnight-disabled-20261105/verification.json` record evidence and limitations. Optimized receiver execution, API28, Living native and physical alarm/screen acceptance remain separate. A setting change after the guard read has no new atomicity guarantee.

Original start **2026-11-05 03:31:49 UTC**, discovery cap **03:36:49**, target **03:46:49**, hard limit **04:01:49**. Discovery overran its cap. No clock was reset; previous held scopes and the whole product goal remain open.

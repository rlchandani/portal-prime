/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher;

import android.app.Instrumentation;
import android.content.pm.PackageInstaller;
import android.os.Bundle;
import android.os.SystemClock;
import java.util.HashSet;
import java.util.Set;

/**
 * Standalone framework-only fixture: no app, Kotlin, AndroidX, UI or installer commits.
 * Only sessions created in this invocation may be abandoned.
 */
public final class PlatformInstallerInstrumentation extends Instrumentation {
    private static final long REMOVAL_TIMEOUT_MS = 5_000L;
    private static final String[] CHECKS = {
        "baselineRead", "guardCreated", "targetCreated", "ownedSessionsDistinct",
        "fixtureSessionsPresent", "guardPresentBeforeTargetAbandon", "noInjectedFailure",
        "targetAbandonSucceeded", "targetRemovalVerified", "guardPreservedAfterTargetAbandon",
        "originalSessionsPreservedAfterTargetAbandon", "cleanupSucceeded",
        "ownedSessionsAbsentAtEnd", "baselineRestored", "unexpectedExceptionsAbsent"
    };

    private boolean failAfterCreate;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        failAfterCreate = arguments != null
                && "true".equals(arguments.getString("failAfterCreate"));
        start();
    }

    @Override public void onStart() {
        Bundle result = runFixture();
        finish(result.getBoolean("platformFixturePassed") ? 0 : 1, result);
    }

    private Bundle runFixture() {
        Bundle result = new Bundle();
        for (String check : CHECKS) result.putBoolean(check, false);
        result.putBoolean("failureInjectionRequested", failAfterCreate);
        Set<Integer> createdIds = new HashSet<>();
        Set<Integer> abandonedIds = new HashSet<>();
        Set<Integer> baseline = null;
        PackageInstaller installer = null;
        int unexpectedExceptions = 0;
        try {
            installer = getTargetContext().getPackageManager().getPackageInstaller();
            baseline = sessionIds(installer);
            result.putInt("baselineSessionCount", baseline.size());
            demand(result, "baselineRead", true);

            int guardId = createOwned(installer, baseline, createdIds);
            demand(result, "guardCreated", true);
            int targetId = createOwned(installer, baseline, createdIds);
            demand(result, "targetCreated", true);
            demand(result, "ownedSessionsDistinct", guardId != targetId);
            Set<Integer> current = sessionIds(installer);
            demand(result, "fixtureSessionsPresent", current.containsAll(createdIds));
            demand(result, "guardPresentBeforeTargetAbandon", current.contains(guardId));
            // Deliberate failing run verifies finally cleanup and a non-success result.
            demand(result, "noInjectedFailure", !failAfterCreate);
            installer.abandonSession(targetId);
            abandonedIds.add(targetId);
            demand(result, "targetAbandonSucceeded", true);
            demand(result, "targetRemovalVerified", awaitAbsent(installer, targetId));
            current = sessionIds(installer);
            demand(result, "guardPreservedAfterTargetAbandon", current.contains(guardId));
            demand(result, "originalSessionsPreservedAfterTargetAbandon",
                    current.containsAll(baseline));
        } catch (CheckFailure ignored) {
            // Failed checks stay false, even when resource restoration succeeds.
        } catch (Exception ignored) {
            unexpectedExceptions++;
        } finally {
            boolean cleanupSucceeded = true;
            if (installer != null) {
                for (int sessionId : createdIds) {
                    try {
                        // Enumeration failure must not prevent cleanup of our own resource.
                        if (!abandonedIds.contains(sessionId)) {
                            installer.abandonSession(sessionId);
                        }
                        if (!awaitAbsent(installer, sessionId)) cleanupSucceeded = false;
                    } catch (Exception ignored) {
                        cleanupSucceeded = false;
                        unexpectedExceptions++;
                    }
                }
                result.putBoolean("cleanupSucceeded", cleanupSucceeded);
                if (baseline != null) {
                    try {
                        Set<Integer> finalIds = sessionIds(installer);
                        result.putInt("finalSessionCount", finalIds.size());
                        Set<Integer> remainingOwned = new HashSet<>(createdIds);
                        remainingOwned.retainAll(finalIds);
                        result.putBoolean("ownedSessionsAbsentAtEnd", remainingOwned.isEmpty());
                        result.putBoolean("baselineRestored", finalIds.equals(baseline));
                    } catch (Exception ignored) {
                        unexpectedExceptions++;
                    }
                }
            }
        }
        result.putInt("createdSessionCount", createdIds.size());
        result.putBoolean("unexpectedExceptionsAbsent", unexpectedExceptions == 0);
        result.putInt("platformFixtureUnexpectedExceptionCount", unexpectedExceptions);
        int failures = 0;
        for (String check : CHECKS) if (!result.getBoolean(check)) failures++;
        result.putInt("platformFixtureCheckCount", CHECKS.length);
        result.putInt("platformFixtureFailureCount", failures);
        result.putBoolean("platformFixturePassed", failures == 0);
        return result;
    }

    private static int createOwned(PackageInstaller installer, Set<Integer> baseline,
            Set<Integer> createdIds) throws Exception {
        int id = installer.createSession(
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL));
        if (id < 0 || baseline.contains(id) || !createdIds.add(id)) throw new CheckFailure();
        return id;
    }

    private static void demand(Bundle result, String check, boolean passed) {
        result.putBoolean(check, passed);
        if (!passed) throw new CheckFailure();
    }

    private static Set<Integer> sessionIds(PackageInstaller installer) {
        Set<Integer> ids = new HashSet<>();
        for (PackageInstaller.SessionInfo session : installer.getMySessions()) {
            ids.add(session.getSessionId());
        }
        return ids;
    }

    private static boolean awaitAbsent(PackageInstaller installer, int id) {
        long deadline = SystemClock.elapsedRealtime() + REMOVAL_TIMEOUT_MS;
        do {
            if (!sessionIds(installer).contains(id)) return true;
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0L) return false;
            SystemClock.sleep(Math.min(100L, remaining));
        } while (true);
    }

    private static final class CheckFailure extends RuntimeException {}
}

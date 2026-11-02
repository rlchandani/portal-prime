/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Instrumentation
import android.content.pm.PackageInstaller
import android.os.Bundle
import android.os.SystemClock

/** Public installer APIs only; the caller must explicitly identify one app-owned session. */
internal object InstallSessionCleanupDeviceChecks {
  private const val REMOVAL_TIMEOUT_MS = 5_000L
  private const val POLL_MS = 100L
  private class CheckFailure : RuntimeException()

  fun run(instrumentation: Instrumentation, options: Bundle): Bundle {
    val checks = listOf(
        "ownedSessionIdValid", "originalSessionsRead", "ownedSessionPrevalidated",
        "scratchCreated", "scratchPresent", "scratchAbandonSucceeded",
        "scratchRemovalVerified", "ownedSessionStillPresent", "ownedSessionAbandonSucceeded",
        "ownedSessionRemovalVerified", "scratchFinallyAbandonSucceeded",
        "scratchFinallyRemovalVerified", "finalSessionsRead", "otherOriginalSessionsPreserved",
        "scratchAbsentAtEnd", "ownedSessionAbsentAtEnd", "unexpectedExceptionsAbsent",
    )
    val result = Bundle().apply {
      checks.forEach { putBoolean(it, false) }
      putInt("originalSessionCount", 0)
      putInt("otherOriginalSessionCount", 0)
      putInt("finalSessionCount", 0)
    }
    fun demand(name: String, passed: Boolean) {
      result.putBoolean(name, passed)
      if (!passed) throw CheckFailure()
    }

    var installer: PackageInstaller? = null
    var ownedSessionId: Int? = null
    var originalSessionIds: Set<Int>? = null
    var scratchSessionId: Int? = null
    var scratchRemoved = false
    var unexpectedExceptionCount = 0
    try {
      val suppliedId = options.getString("ownedSessionId")?.toIntOrNull()
      demand("ownedSessionIdValid", suppliedId != null && suppliedId > 0)
      val ownedId = checkNotNull(suppliedId)
      ownedSessionId = ownedId
      val api = instrumentation.targetContext.packageManager.packageInstaller
      installer = api
      val originalIds = sessionIds(api)
      originalSessionIds = originalIds
      result.putInt("originalSessionCount", originalIds.size)
      demand("originalSessionsRead", true)
      // mySessions is the public ownership boundary. Nothing is mutated before this check.
      demand("ownedSessionPrevalidated", ownedId in originalIds)
      result.putInt("otherOriginalSessionCount", originalIds.size - 1)

      val scratchId = api.createSession(
          PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
      // A fresh ID must never alias an original session, even on a faulty platform.
      demand("scratchCreated", scratchId > 0 && scratchId !in originalIds)
      scratchSessionId = scratchId
      demand("scratchPresent", scratchId in sessionIds(api))
      api.abandonSession(scratchId)
      demand("scratchAbandonSucceeded", true)
      scratchRemoved = awaitAbsent(api, scratchId)
      demand("scratchRemovalVerified", scratchRemoved)

      // Stop if the supplied session disappeared while checking the scratch lifecycle.
      demand("ownedSessionStillPresent", ownedId in sessionIds(api))
      api.abandonSession(ownedId)
      demand("ownedSessionAbandonSucceeded", true)
      demand("ownedSessionRemovalVerified", awaitAbsent(api, ownedId))
    } catch (_: CheckFailure) {
      // The failed assertion remains false; later cleanup cannot turn this run green.
    } catch (_: Throwable) {
      // Report only aggregate failures, never exception text or session/app metadata.
      unexpectedExceptionCount++
    } finally {
      val api = installer
      val scratchId = scratchSessionId
      if (api != null && scratchId != null && !scratchRemoved) {
        try {
          api.abandonSession(scratchId)
          result.putBoolean("scratchFinallyAbandonSucceeded", true)
          result.putBoolean("scratchFinallyRemovalVerified", awaitAbsent(api, scratchId))
        } catch (_: Throwable) {
          unexpectedExceptionCount++
        }
      } else {
        result.putBoolean("scratchFinallyAbandonSucceeded", true)
        result.putBoolean("scratchFinallyRemovalVerified", true)
      }
      val originalIds = originalSessionIds
      if (api != null && originalIds != null) {
        try {
          val finalIds = sessionIds(api)
          result.putBoolean("finalSessionsRead", true)
          result.putInt("finalSessionCount", finalIds.size)
          result.putBoolean("otherOriginalSessionsPreserved",
              originalIds.filter { it != ownedSessionId }.all { it in finalIds })
          result.putBoolean("scratchAbsentAtEnd", scratchId == null || scratchId !in finalIds)
          result.putBoolean("ownedSessionAbsentAtEnd",
              ownedSessionId != null && ownedSessionId !in finalIds)
        } catch (_: Throwable) {
          unexpectedExceptionCount++
        }
      }
    }
    result.putBoolean("unexpectedExceptionsAbsent", unexpectedExceptionCount == 0)
    result.putInt("installerCleanupUnexpectedExceptionCount", unexpectedExceptionCount)
    result.putInt("installerCleanupCheckCount", checks.size)
    result.putInt("installerCleanupFailureCount", checks.count { !result.getBoolean(it) })
    result.putBoolean("installerCleanupPassed", checks.all { result.getBoolean(it) })
    return result
  }

  private fun sessionIds(installer: PackageInstaller): Set<Int> =
      installer.mySessions.map { it.sessionId }.toSet()

  private fun awaitAbsent(installer: PackageInstaller, sessionId: Int): Boolean {
    val deadline = SystemClock.elapsedRealtime() + REMOVAL_TIMEOUT_MS
    while (SystemClock.elapsedRealtime() < deadline) {
      if (sessionId !in sessionIds(installer)) return true
      val remaining = deadline - SystemClock.elapsedRealtime()
      if (remaining <= 0L) return false
      SystemClock.sleep(minOf(POLL_MS, remaining))
    }
    return false
  }
}

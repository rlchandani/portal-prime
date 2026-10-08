/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteCompanionHistoryTest {
  @Test fun delayedNativeSuccessReplacesTimeoutOutcomeAndPreservesItsAudit() {
    var persisted = JSONArray()
    val history = RemoteCompanionHistory(persist = { persisted = JSONArray(it.toString()) })
    val id = pendingInstall(history)
    val timedOut = timeoutResult()
    history.finish(id, timedOut)
    val failed = history.find(id)!!
    assertNull(history.start("install", JSONObject()))

    history.reconcileInstall(id, "installed_readback", true)

    val installed = history.find(id)!!
    assertEquals("succeeded", installed.getString("state"))
    assertFalse(installed.getBoolean("installUncertain"))
    assertFalse(installed.getBoolean("backendMayContinue"))
    assertTrue(installed.getBoolean("installConfirmed"))
    assertFalse(installed.getBoolean("retrySupported"))
    val result = installed.getJSONArray("results").getJSONObject(0)
    assertTrue(result.getBoolean("ok"))
    assertEquals("installed", result.getString("result"))
    assertEquals("org.y20k.transistor", result.getString("packageName"))
    assertFalse(result.getBoolean("backendMayContinue"))
    assertFalse(result.getBoolean("installUncertain"))
    assertFalse("Old readback must not be reported as current native evidence", result.has("installedVersionCode"))
    val reconciliation = installed.getJSONObject("reconciliation")
    assertEquals("installed_readback", reconciliation.getString("evidence"))
    assertTrue(reconciliation.getBoolean("ok"))
    val previous = reconciliation.getJSONObject("previous")
    assertEquals("failed", previous.getString("state"))
    assertEquals(timedOut.toString(), previous.getJSONArray("results").toString())
    assertEquals(failed.getLong("updatedAt"), previous.getLong("updatedAt"))
    assertTrue(previous.getBoolean("backendMayContinue"))
    assertTrue(previous.getBoolean("installUncertain"))
    assertEquals(failed.getLong("createdAt"), installed.getLong("createdAt"))
    assertEquals(installed.toString(), persisted.getJSONObject(0).toString())
    assertNotNull(history.start("update", JSONObject()))
  }

  @Test fun nativeFailureClearsNestedUncertaintyAndMakesRetryAvailable() {
    val history = RemoteCompanionHistory()
    val id = pendingInstall(history, type = "update")
    history.finish(id, timeoutResult())

    history.reconcileInstall(id, "daemon_failed", false)

    val failed = history.find(id)!!
    assertEquals("failed", failed.getString("state"))
    assertFalse(failed.getBoolean("backendMayContinue"))
    assertFalse(failed.getBoolean("installUncertain"))
    assertFalse(failed.getBoolean("installConfirmed"))
    assertTrue(failed.getBoolean("retrySupported"))
    val result = failed.getJSONArray("results").getJSONObject(0)
    assertFalse(result.getBoolean("ok"))
    assertEquals("install_failed", result.getString("result"))
    assertFalse(result.getBoolean("backendMayContinue"))
    assertFalse(result.getBoolean("installUncertain"))
    assertEquals("install_failed_or_timed_out", failed.getJSONObject("reconciliation")
        .getJSONObject("previous").getJSONArray("results").getJSONObject(0).getString("result"))
    assertNotNull(history.start("install", JSONObject()))
  }

  @Test fun reconciliationAfterRestartMovesInterruptionErrorIntoAudit() {
    val running = RemoteCompanionHistory()
    val id = pendingInstall(running)
    val history = RemoteCompanionHistory(running.list())
    assertEquals("interrupted", history.find(id)!!.getString("state"))
    assertNull(history.start("install", JSONObject()))

    history.reconcileInstall(id, "installed_readback", true)

    val installed = history.find(id)!!
    assertEquals("succeeded", installed.getString("state"))
    assertFalse(installed.has("error"))
    val previous = installed.getJSONObject("reconciliation").getJSONObject("previous")
    assertEquals("interrupted", previous.getString("state"))
    assertEquals("process_restarted", previous.getString("error"))
    val reloaded = RemoteCompanionHistory(history.list()).find(id)!!
    assertEquals(installed.toString(), reloaded.toString())
  }

  @Test fun loadingPreviouslyReconciledTimeoutRepairsCurrentOutcomeWithoutNewEvidence() {
    val history = RemoteCompanionHistory()
    val id = pendingInstall(history)
    history.finish(id, timeoutResult())
    // Exact shape left by the previous reconcileInstall implementation after a successful poll.
    val saved = history.find(id)!!.put("installUncertain", false).put("backendMayContinue", false)
        .put("installConfirmed", true).put("retrySupported", false)
        .put("reconciliation", JSONObject().put("evidence", "installed_readback").put("ok", true).put("at", 456L))

    val reloaded = RemoteCompanionHistory(JSONArray().put(saved))

    val installed = reloaded.find(id)!!
    assertEquals("succeeded", installed.getString("state"))
    assertEquals("installed", installed.getJSONArray("results").getJSONObject(0).getString("result"))
    assertFalse(installed.getJSONArray("results").getJSONObject(0).getBoolean("backendMayContinue"))
    assertEquals(456L, installed.getJSONObject("reconciliation").getLong("at"))
    assertEquals(timeoutResult().toString(), installed.getJSONObject("reconciliation")
        .getJSONObject("previous").getJSONArray("results").toString())
    assertNotNull(reloaded.start("install", JSONObject()))
  }

  @Test fun immediateNativeSuccessStillWaitsForWorkerReadbackBeforeFinishing() {
    val history = RemoteCompanionHistory()
    val id = pendingInstall(history)
    history.reconcileInstall(id, "native_installer_success", true)
    assertEquals("running", history.find(id)!!.getString("state"))
    assertNull(history.start("action", JSONObject()))
    val mismatch = JSONArray().put(JSONObject().put("ok", false).put("result", "installed_readback_mismatch"))

    history.finish(id, mismatch)

    val failed = history.find(id)!!
    assertEquals("failed", failed.getString("state"))
    assertEquals(mismatch.toString(), failed.getJSONArray("results").toString())
    assertFalse(failed.getBoolean("retrySupported"))
    // A restart must also retain the worker's completed readback mismatch.
    assertEquals(mismatch.toString(), RemoteCompanionHistory(history.list()).find(id)!!
        .getJSONArray("results").toString())
  }

  @Test fun replayAndLateWorkerCompletionCannotReplaceReconciledNativeOutcome() {
    val history = RemoteCompanionHistory()
    val id = pendingInstall(history)
    history.finish(id, timeoutResult())
    history.reconcileInstall(id, "installed_readback", true)
    val installed = history.find(id)!!
    assertEquals("succeeded", installed.getString("state"))

    history.reconcileInstall(id, "daemon_failed", false)
    history.finish(id, timeoutResult())
    history.backendStopped(id)

    assertEquals(installed.toString(), history.find(id)!!.toString())
  }

  private fun pendingInstall(history: RemoteCompanionHistory, type: String = "install"): String {
    val id = history.start(type, JSONObject().put("packageName", "org.y20k.transistor"))!!.getString("id")
    history.installPending(id, "dialog", "org.y20k.transistor", 7L, 123L)
    return id
  }

  private fun timeoutResult() = JSONArray().put(JSONObject().put("ok", false)
      .put("result", "install_failed_or_timed_out").put("backendMayContinue", true)
      .put("installUncertain", true).put("packageName", "org.y20k.transistor")
      .put("installedVersionCode", JSONObject.NULL))
}

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

class RemotePresetExecutionTest {
  private fun outcome(ok: Boolean, code: String) = JSONObject().put("ok", ok).put("result", code)
  @Test fun orderedStepsRetainTheirIndexAndStopAtTheFailure() {
    val calls = mutableListOf<String>()
    val result = RemotePresetExecution.run(JSONArray("[{type:'text'},{type:'launch'},{type:'key'}]"), { false }, {
      calls += it.getString("type"); outcome(calls.size == 1, "public-result")
    })
    assertEquals(listOf("text", "launch"), calls)
    assertEquals(2, result.length())
    assertEquals(1, result.getJSONObject(1).getInt("step"))
  }
  @Test fun unknownAndNonObjectStepsStopWithoutExecuting() {
    for (steps in listOf("[{type:'future'},{type:'key'}]", "[3,{type:'key'}]")) {
      var calls = 0
      val result = RemotePresetExecution.run(JSONArray(steps), { false }, { calls++; outcome(true, "dispatched") })
      assertEquals(0, calls); assertEquals("unsupported_step", result.getJSONObject(0).getString("result"))
    }
  }
  @Test fun exceptionIsSanitizedAndPreventsLaterActions() {
    var calls = 0
    val result = RemotePresetExecution.run(JSONArray("[{type:'text'},{type:'key'}]"), { false }, {
      calls++; throw IllegalStateException("PRIVATE-PUBLIC-TEST-SENTINEL")
    })
    assertEquals(1, calls); assertFalse(result.toString().contains("SENTINEL"))
    assertEquals("step_failed", result.getJSONObject(0).getString("result"))
  }
  @Test fun unconfirmedNavigationDoesNotRunTheNextStep() {
    var calls = 0
    val result = RemotePresetExecution.run(JSONArray("[{type:'launch'},{type:'key'}]"), { false }, {
      calls++; outcome(true, "completion_unconfirmed").put("checkStateBeforeRetry", true)
    })
    assertEquals(1, calls); assertTrue(result.getJSONObject(0).getBoolean("backendMayContinue"))
  }
  @Test fun cancellationBeforeRunPerformsNoAction() {
    var calls = 0
    val result = RemotePresetExecution.run(JSONArray("[{type:'text'}]"), { true }, { calls++; outcome(true, "dispatched") })
    assertEquals(0, calls); assertEquals("cancelled", result.getJSONObject(0).getString("result"))
  }
  @Test fun longWaitChecksCancellationInBoundedChunks() {
    var stopped = false; var actions = 0; val sleeps = mutableListOf<Long>()
    val result = RemotePresetExecution.run(JSONArray("[{type:'wait',ms:10000},{type:'key'}]"), { stopped },
      { actions++; outcome(true, "dispatched") }, { sleeps += it; stopped = true })
    assertEquals(listOf(100L), sleeps); assertEquals(0, actions)
    assertFalse(result.getJSONObject(0).getBoolean("ok")); assertEquals("cancelled", result.getJSONObject(0).getString("result"))
  }
  @Test fun legacyWaitCoercionAndBoundsRemainCompatible() {
    for ((input, expected) in listOf("'300'" to 300L, "-1" to 0L, "99999" to 10000L)) {
      val sleeps = mutableListOf<Long>()
      val result = RemotePresetExecution.run(JSONArray("[{type:'wait',ms:$input}]"), { false },
        { error("wait must not call action") }, { sleeps += it })
      assertEquals(expected, sleeps.sum()); assertTrue(sleeps.all { it in 1L..100L })
      assertTrue(result.getJSONObject(0).getBoolean("ok"))
    }
  }
  @Test fun cancellationKeepsTheAdmissionFenceUntilActualCompletion() {
    val h = RemoteCompanionHistory(); val op = h.start("preset", JSONObject())!!; val id = op.getString("id")
    val stopping = h.cancelPreset(id)!!
    assertEquals("running", stopping.getString("state")); assertTrue(h.cancellationRequested(id))
    assertFalse(stopping.getBoolean("cancelSupported")); assertNull(h.start("scene", JSONObject()))
    assertTrue(h.cancelPreset(id)!!.getBoolean("cancelRequested"))
    h.finish(id, JSONArray().put(outcome(false, "cancelled")))
    assertEquals("cancelled", h.find(id)!!.getString("state")); assertNotNull(h.start("scene", JSONObject()))
  }
  @Test fun cancellationNeverClaimsSupportForOtherOperations() {
    for (type in listOf("scene", "action", "install", "update")) {
      val h = RemoteCompanionHistory(); val op = h.start(type, JSONObject())!!
      assertNull(h.cancelPreset(op.getString("id"))); assertFalse(h.cancellationRequested(op.getString("id")))
      assertFalse(op.getBoolean("cancelSupported"))
    }
  }
  @Test fun unsupportedTypeDoesNotExposeSavedContentInHistory() {
    for (raw in listOf("{private:'PUBLIC-SENTINEL'}", "'PUBLIC-SENTINEL'")) {
      val result = RemotePresetExecution.run(JSONArray("[{type:$raw}]"), { false }, { error("unsupported") })
      assertFalse(result.toString().contains("SENTINEL"))
      assertEquals("unknown", result.getJSONObject(0).getString("type"))
    }
  }
  @Test fun unconfirmedPresetIsNeverReportedFinished() {
    val h = RemoteCompanionHistory(); val op = h.start("preset", JSONObject())!!
    h.finish(op.getString("id"), JSONArray().put(outcome(true,"completion_unconfirmed").put("backendMayContinue",true)))
    val done = h.find(op.getString("id"))!!
    assertNotEquals("succeeded",done.getString("state"))
    assertTrue(done.getBoolean("backendMayContinue")); assertFalse(done.getBoolean("retrySupported"))
  }

}

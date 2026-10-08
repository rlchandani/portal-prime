/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.Bundle
import com.immortal.launcher.settings.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class RemoteCompanionPolicyTest {
  private val context = mock(Context::class.java)
  private var writes = 0
  private val domain get() = SettingsDomain("test", "Test", load = { 0 }, specs = listOf(
      BoolSpec("on", "On", get = { false }, set = { _, _ -> writes++ }),
      IntSpec("count", "Count", get = { 5 }, set = { _, _ -> writes++ }, min = 1, max = 10),
      EnumSpec("mode", "Mode", get = { "a" }, set = { _, _ -> writes++ }, options = listOf("a" to "A", "b" to "B")),
      BoolSpec("hidden", "Hidden", get = { true }, set = { _, _ -> writes++ }, visible = { _, _ -> false }),
      StringSpec("password", "Password", get = { "secret" }, set = { _, _ -> writes++ }, secret = true)))

  private fun layout() = JSONObject("""{"assignments":{},"homeOrder":[],"gridOrder":[],"gridSlots":[],"widgets":[]}""")
  private fun snapshot() = JSONObject().put("schemaVersion", 1).put("deviceId", "device").put("revision", "a".repeat(64))
      .put("domains", JSONObject().put("test", JSONObject().put("count", 7).put("hidden", false))).put("layout", layout())
  private fun preflight(s: JSONObject) = RemoteSnapshotPolicy.validateSnapshot(s, "device", "a".repeat(64), listOf(domain), emptyList())

  @Test fun layoutRejectsMalformedFieldsAndDuplicateTiles() {
    assertFalse(RemoteLayoutPolicy.validate(JSONObject(), emptyList()).valid)
    assertFalse(RemoteLayoutPolicy.validate(layout().put("gridSlots", JSONArray("[3]")), emptyList()).valid)
    assertFalse(RemoteLayoutPolicy.validate(layout().put("homeOrder", JSONArray().put("com.test.app").put("com.test.app")), emptyList()).valid)
  }
  @Test fun layoutRejectsInvalidSizesUnknownKindsAndForeignWidgetBinding() {
    val w = JSONObject("""{"kind":"weather","customId":"weather:abc","spanX":0,"spanY":2}""")
    assertFalse(RemoteLayoutPolicy.validate(layout().put("widgets", JSONArray().put(w)), emptyList()).valid)
    w.put("spanX", 2).put("kind", "shell")
    assertFalse(RemoteLayoutPolicy.validate(layout().put("widgets", JSONArray().put(w)), emptyList()).valid)
    w.put("kind", "appwidget").put("id", 7).put("package", "com.test").put("class", "Provider")
    assertFalse(RemoteLayoutPolicy.validate(layout().put("widgets", JSONArray().put(w)), emptyList()).valid)
  }
  @Test fun layoutRetainsBoundWidgetsWithoutRebinding() {
    val existing = HomeWidgetStore.HomeWidget(7, "com.test", "Provider")
    val l = layout().put("widgets", JSONArray(HomeWidgetStore.serialize(listOf(existing))))
    assertTrue(RemoteLayoutPolicy.validate(l, listOf(existing)).valid)
    l.getJSONArray("widgets").getJSONObject(0).put("package", "evil.pkg")
    assertFalse(RemoteLayoutPolicy.validate(l, listOf(existing)).valid)
  }
  @Test fun snapshotRejectsFutureOldWrongDeviceAndStaleRevisionBeforeAnyWrite() {
    assertFalse(preflight(snapshot().put("schemaVersion", 999)).valid)
    assertFalse(preflight(snapshot().put("schemaVersion", 0)).valid)
    assertFalse(preflight(snapshot().put("deviceId", "other")).valid)
    assertFalse(preflight(snapshot().put("revision", "old")).valid)
    assertEquals(0, writes)
  }
  @Test fun snapshotRejectsEveryInvalidScalarTypeAndUnknownOrSecretKey() {
    for ((key, value) in listOf("count" to 1.5, "count" to "7", "count" to 99, "mode" to "unknown", "on" to "true", "password" to "secret", "unknown" to true)) {
      val s = snapshot().put("domains", JSONObject().put("test", JSONObject().put(key, value)))
      assertFalse("$key=$value", preflight(s).valid)
    }
    assertEquals(0, writes)
  }
  @Test fun snapshotChecksAllDomainsAndLayoutBeforeMutation() {
    assertFalse(preflight(snapshot().put("layout", layout().put("widgets", JSONArray().put(JSONObject())))).valid)
    assertTrue(preflight(snapshot()).valid)
    assertEquals(0, writes)
  }
  @Test fun exportIncludesHiddenControlsAndOmitsSecrets() {
    val values = RemoteSnapshotPolicy.values(context, listOf(domain)).getJSONObject("test")
    assertTrue(values.has("hidden"))
    assertFalse(values.has("password"))
    assertFalse(values.toString().contains("secret"))
  }
  @Test fun operationsRejectUrlsPathsShellAndUnlistedPackages() {
    for (s in listOf("""{"type":"shell","command":"id"}""", """{"type":"install","packageName":"com.test","apkUrl":"https://x"}""", """{"type":"install","packageName":"com.test","path":"/sdcard/a.apk"}""", """{"type":"install","packageName":"other.app"}""")) {
      assertFalse(RemoteCompanionOperationPolicy.validate(JSONObject(s), emptyList(), 28, "touch").valid)
    }
  }
  @Test fun operationsHonorActualApiAndDeviceCompatibility() {
    val app = CatalogApp("Test", "com.test", "url", fdroidId=null, versionCode=null, apkUrl="https://example.test/app.apk", description="", category="", minSdk=29)
    val request = JSONObject("""{"type":"install","packageName":"com.test"}""")
    assertFalse(RemoteCompanionOperationPolicy.validate(request, listOf(app), 28, "touch").valid)
    assertTrue(RemoteCompanionOperationPolicy.validate(request, listOf(app), 29, "touch").valid)
    assertFalse(RemoteCompanionOperationPolicy.validate(request, listOf(app.copy(devices=listOf("tv"))), 29, "touch").valid)
  }
  @Test fun historyRetainsPartialFailureAndCannotCancelRunningBackend() {
    val history = RemoteCompanionHistory()
    val operation = history.start("install", JSONObject())!!
    assertNull(history.start("action", JSONObject()))
    assertFalse(operation.getBoolean("cancelSupported"))
    history.finish(operation.getString("id"), JSONArray().put(JSONObject().put("ok", true)).put(JSONObject().put("ok", false)))
    assertEquals("failed", history.list().getJSONObject(0).getString("state"))
  }
  @Test fun historyIsBoundedAndMarksInterruptedWorkAfterRestart() {
    val history = RemoteCompanionHistory()
    repeat(75) { val op = history.start("action", JSONObject())!!; history.finish(op.getString("id"), JSONArray().put(JSONObject().put("ok", true))) }
    assertEquals(50, history.list().length())
    val running = RemoteCompanionHistory(); running.start("install", JSONObject())
    assertEquals("interrupted", RemoteCompanionHistory(running.list()).list().getJSONObject(0).getString("state"))
  }
  @Test fun scenesRejectUnknownStepsExcessiveWaitsAndInvalidSchedule() {
    fun scenes(steps: JSONArray, schedule: JSONObject? = null) = JSONArray().put(JSONObject().put("id", "scene1").put("name", "Scene").put("steps", steps).apply { schedule?.let { put("schedule", it) } })
    assertFalse(RemoteScenePolicy.validate(scenes(JSONArray().put(JSONObject().put("type", "shell")))) { _, _ -> true }.valid)
    assertFalse(RemoteScenePolicy.validate(scenes(JSONArray().put(JSONObject().put("type", "wait").put("ms", 5001)))) { _, _ -> true }.valid)
    assertFalse(RemoteScenePolicy.validate(scenes(JSONArray().put(JSONObject().put("type", "wait").put("ms", 1)), JSONObject().put("enabled", true).put("hour", 25).put("minute", 0).put("days", JSONArray().put(1)))) { _, _ -> true }.valid)
    assertTrue(RemoteScenePolicy.validate(scenes(JSONArray().put(JSONObject().put("type", "key").put("action", "home")))) { _, _ -> true }.valid)
  }
  @Test fun jarvisOnlyAcceptsExplicitModelAndWakeFields() {
    assertFalse(RemoteCompanionJarvis.validConfiguration(JSONObject().put("apiKey", "secret")))
    assertFalse(RemoteCompanionJarvis.validConfiguration(JSONObject().put("wakeEnabled", "true")))
    assertTrue(RemoteCompanionJarvis.validConfiguration(JSONObject().put("wakeEnabled", false)))
  }
  @Test fun terminalHistoryCannotBeOverwrittenByLateBackendCompletion() {
    val history = RemoteCompanionHistory()
    val op = history.start("install", JSONObject())!!
    history.finish(op.getString("id"), JSONArray().put(JSONObject().put("ok", false).put("result", "deadline_exceeded").put("backendMayContinue", true)))
    history.finish(op.getString("id"), JSONArray().put(JSONObject().put("ok", true)))
    assertEquals("failed", history.list().getJSONObject(0).getString("state"))
    assertEquals("deadline_exceeded", history.list().getJSONObject(0).getJSONArray("results").getJSONObject(0).getString("result"))
    assertFalse(history.list().getJSONObject(0).getBoolean("retrySupported"))
    history.backendStopped(op.getString("id"))
    assertTrue(history.list().getJSONObject(0).getBoolean("retrySupported"))
    assertFalse(history.list().getJSONObject(0).getBoolean("backendMayContinue"))
  }
  @Test fun frozenSceneSourceEnvelopeRejectsCredentialsAndUnknownValues() {
    assertTrue(RemoteScenePolicy.validSource(JSONObject().put("folderPath", "/sdcard/Pictures/Phone")))
    assertTrue(RemoteScenePolicy.validSource(JSONObject().put("source", "folder").put("folderPath", "/sdcard/Pictures")))
    assertFalse(RemoteScenePolicy.validSource(JSONObject().put("source", "immich").put("immichKey", "test-secret")))
    assertFalse(RemoteScenePolicy.validSource(JSONObject().put("source", "unknown")))
    val scene = JSONObject().put("id", "same").put("name", "Name").put("steps", JSONArray().put(JSONObject().put("type", "source").put("values", JSONObject().put("source", "default"))))
    assertTrue(RemoteScenePolicy.validate(JSONArray().put(scene)) { _, _ -> false }.valid)
    assertFalse(RemoteScenePolicy.validate(JSONArray().put(scene).put(scene)) { _, _ -> false }.valid)
  }
  @Test fun revisionIsStableAcrossJsonObjectOrderButChangesWithSettings() {
    val a = JSONObject().put("a", 1).put("b", true)
    val b = JSONObject().put("b", true).put("a", 1)
    assertEquals(RemoteSnapshotPolicy.revision(a, layout()), RemoteSnapshotPolicy.revision(b, layout()))
    b.put("a", 2)
    assertNotEquals(RemoteSnapshotPolicy.revision(a, layout()), RemoteSnapshotPolicy.revision(b, layout()))
  }
  @Test fun scheduleUsesIsoDaysAndNeverReplaysPastTimes() {
    val schedule = JSONObject().put("enabled", true).put("hour", 9).put("minute", 30).put("days", JSONArray().put(1))
    val now = java.util.Calendar.getInstance().apply { set(2026, java.util.Calendar.OCTOBER, 5, 9, 31, 0); set(java.util.Calendar.MILLISECOND, 0) }
    val next = java.util.Calendar.getInstance().apply { timeInMillis = RemoteScenes.nextTime(schedule, now.timeInMillis) }
    assertEquals(java.util.Calendar.MONDAY, next.get(java.util.Calendar.DAY_OF_WEEK))
    assertEquals(12, next.get(java.util.Calendar.DAY_OF_MONTH))
    assertEquals(9, next.get(java.util.Calendar.HOUR_OF_DAY))
    assertTrue(next.timeInMillis > now.timeInMillis)
  }

  @Test fun snapshotRejectsMalformedRevisionEvenIfPresentedAsCurrent() {
    val s = snapshot().put("revision", "not-a-fingerprint")
    assertFalse(RemoteSnapshotPolicy.validateSnapshot(s, "device", "not-a-fingerprint", listOf(domain), emptyList()).valid)
  }

  @Test fun jarvisProjectsAgreedProviderFieldsWithoutCredentials() {
    val b = mock(Bundle::class.java)
    `when`(b.get("model")).thenReturn("gemini-test-model")
    `when`(b.get("wakeEnabled")).thenReturn(true)
    `when`(b.get("microphoneGranted")).thenReturn(true)
    `when`(b.get("microphoneMuted")).thenReturn(false)
    `when`(b.get("apiKey")).thenReturn("test-secret")
    `when`(b.getStringArrayList("availableModels")).thenReturn(arrayListOf("gemini-test-model", "another-model"))
    val json = RemoteCompanionJarvis.project(b)
    assertEquals("gemini-test-model", json.getString("model"))
    assertTrue(json.getBoolean("wakeEnabled"))
    assertTrue(json.getBoolean("microphoneGranted"))
    assertFalse(json.getBoolean("microphoneMuted"))
    assertEquals(2, json.getJSONArray("availableModels").length())
    assertFalse(json.has("apiKey"))
    assertFalse(json.toString().contains("test-secret"))
  }

  @Test fun retryFlagsDescribeTerminalBackendAvailability() {
    val history = RemoteCompanionHistory()
    val op = history.start("install", JSONObject())!!
    assertFalse(op.getBoolean("retrySupported"))
    history.finish(op.getString("id"), JSONArray().put(JSONObject().put("ok", false)))
    assertTrue(history.list().getJSONObject(0).getBoolean("retrySupported"))
    val next = history.start("install", JSONObject())!!
    history.finish(next.getString("id"), JSONArray().put(JSONObject().put("ok", true)))
    assertFalse(history.list().getJSONObject(0).getBoolean("retrySupported"))
  }

  @Test fun newCustomWidgetRoundTripKeepsCanonicalNativeId() {
    val l = layout().put("widgets", JSONArray().put(JSONObject().put("kind", "weather").put("customId", "weather:new").put("spanX", 2).put("spanY", 1)))
    assertTrue(RemoteLayoutPolicy.validate(l, emptyList()).valid)
    val widgets = RemoteLayoutPolicy.widgets(l)
    assertEquals(0, widgets.single().appWidgetId)
    assertTrue(RemoteLayoutPolicy.validate(layout().put("widgets", JSONArray(HomeWidgetStore.serialize(widgets))), emptyList()).valid)
  }

  @Test fun nativeComponentsAndShortcutIdsSurviveRealLayoutStoreRoundTrip() {
    val prefs = ScreensaverTestPrefs()
    `when`(prefs.context.getSharedPreferences("immortal_layout", Context.MODE_PRIVATE)).thenReturn(prefs)
    `when`(prefs.context.getSharedPreferences("immortal_home_widgets", Context.MODE_PRIVATE)).thenReturn(prefs)
    val ids = listOf("com.app/.Main", "com.other/com.other.Main", "com.immortal.launcher/__immortal_calls_bridge__", "com.immortal.launcher/com.immortal.launcher.ToolsActivity")
    UserLayout.saveOrder(prefs.context, ids)
    val exported = RemoteLayoutStore.read(prefs.context)
    assertTrue(RemoteLayoutPolicy.validate(exported, emptyList()).valid)
    val reversed = ids.reversed()
    exported.put("homeOrder", JSONArray(reversed))
    RemoteLayoutStore.apply(prefs.context, exported)
    assertEquals(reversed, UserLayout.loadOrder(prefs.context))
    assertTrue(RemoteLayoutPolicy.validate(RemoteLayoutStore.read(prefs.context), emptyList()).valid)
  }
  @Test fun uncertainInstallSurvivesRestartAndCannotBeRetriedOrEvicted() {
    val uncertain = JSONObject().put("id", "uncertain").put("type", "install").put("state", "failed")
        .put("target", JSONObject().put("type", "install").put("packageName", "com.app"))
        .put("installUncertain", true).put("backendMayContinue", true).put("results", JSONArray())
    val history = RemoteCompanionHistory(JSONArray().put(uncertain))
    assertTrue(history.find("uncertain")!!.getBoolean("backendMayContinue"))
    assertFalse(history.find("uncertain")!!.getBoolean("retrySupported"))
    assertNull(history.start("install", JSONObject()))
    repeat(60) { val action = history.start("action", JSONObject())!!; history.finish(action.getString("id"), JSONArray().put(JSONObject().put("ok", true))) }
    assertNotNull(history.find("uncertain"))
    assertNull(history.start("update", JSONObject()))
    history.backendStopped("uncertain")
    assertTrue(history.find("uncertain")!!.getBoolean("backendMayContinue"))
  }


  @Test fun restoreReportsPostApplyCoercionRatherThanClaimingSuccess() {
    var tls = false
    var port = 1883
    val mqtt = SettingsDomain("mqtt", "MQTT", load = { tls to port }, specs = listOf(
        BoolSpec("tls", "TLS", get = { it.first }, set = { _, v -> tls = v }),
        IntSpec("port", "Port", get = { it.second }, set = { _, v -> port = v }, min = 1, max = 65535)),
        onApplied = { _, _ -> if (tls && port == 1883) port = 8883 })
    val result = RemoteSnapshotPolicy.applyVerified(context, mqtt, JSONObject().put("tls", true).put("port", 1883))
    assertFalse(result.getBoolean("ok"))
    assertEquals("readback_mismatch", result.getString("error"))
    val mismatch = result.getJSONArray("mismatches").getJSONObject(0)
    assertEquals("port", mismatch.getString("key"))
    assertEquals(1883, mismatch.getInt("requested"))
    assertEquals(8883, mismatch.getInt("actual"))
  }
  @Test fun scheduledSceneBusyFailureIsRetainedAlongsideActiveOperation() {
    val history = RemoteCompanionHistory()
    val active = history.start("action", JSONObject())!!
    val failed = history.recordFailure("scene", JSONObject().put("id", "morning"), "busy", "scheduled")
    assertEquals("failed", failed.getString("state"))
    assertEquals("scheduled", failed.getString("trigger"))
    assertEquals("busy", failed.getJSONArray("results").getJSONObject(0).getString("result"))
    assertEquals("running", history.find(active.getString("id"))!!.getString("state"))
    assertEquals(2, history.list().length())
  }
  @Test fun installFenceIsPersistedBeforeHandoffAndOnlyEvidenceReleasesIt() {
    var saved = JSONArray()
    val history = RemoteCompanionHistory(persist = { saved = JSONArray(it.toString()) })
    val op = history.start("install", JSONObject().put("packageName", "com.app"))!!
    val id = op.getString("id")
    history.installPending(id, "dialog", "com.app", 7L, 123L)
    assertTrue(saved.getJSONObject(0).getBoolean("installUncertain"))
    history.finish(id, JSONArray().put(JSONObject().put("ok", false)))
    history.backendStopped(id)
    assertNull(history.start("update", JSONObject()))
    history.reconcileInstall(id, "installed_readback", true)
    assertFalse(history.find(id)!!.getBoolean("backendMayContinue"))
    assertFalse(history.find(id)!!.getBoolean("retrySupported"))
    assertEquals("succeeded", history.find(id)!!.getString("state"))
    assertNotNull(history.start("update", JSONObject()))
  }

}

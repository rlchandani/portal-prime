/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.AlarmManager
import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

/** Real paired route, with doubles only for Android preferences and AtomicFile reads. */
class RemoteSceneRevisionRoutesTest {
  private fun scene(id: String) = JSONObject().put("id", id).put("name", id)
      .put("steps", JSONArray().put(JSONObject().put("type", "wait").put("ms", 10)))
  private fun revision(scenes: JSONArray) = RemoteSnapshotPolicy.revision(
      JSONObject().put("scenes", scenes), JSONObject())

  @Test fun sceneReadReturnsItsOwnStableRevision() = withStore(JSONArray().put(scene("first"))) { route, _, saved ->
    val body = JSONObject(route.handle(request("GET")).jsonBytes!!.toString(Charsets.UTF_8))
    assertEquals(200, route.handle(request("GET")).status)
    assertEquals(revision(saved), body.getString("revision"))
    assertEquals("local_inexact", body.getString("scheduleMode"))
  }

  @Test fun staleEditorCannotEraseAnAddedScheduledScene() {
    val cached = JSONArray().put(scene("first"))
    val current = JSONArray(cached.toString()).put(scene("other-client").put("schedule",
        JSONObject().put("enabled", true).put("hour", 8).put("minute", 30).put("days", JSONArray().put(2))))
    withStore(current) { route, file, _ ->
      val before = file.readText()
      val response = route.handle(request("POST", JSONObject().put("scenes", cached)
          .put("expectedRevision", revision(cached))))
      assertEquals(409, response.status)
      assertEquals("stale_revision", JSONObject(response.jsonBytes!!.toString(Charsets.UTF_8)).getString("error"))
      assertEquals("A rejected save must not modify metadata or schedules.", before, file.readText())
    }
  }

  @Test fun nullAndWrongTypedRevisionCannotBypassConflictGuard() = withStore(JSONArray().put(scene("first"))) { route, file, saved ->
    val before = file.readText()
    for (expected in listOf(JSONObject.NULL, true, 7, JSONObject())) {
      val response = route.handle(request("POST", JSONObject().put("scenes", saved).put("expectedRevision", expected)))
      assertEquals(409, response.status)
      assertEquals(before, file.readText())
    }
  }

  @Test fun revisionIgnoresObjectKeyOrderButTracksScheduleAndArrayChanges() {
    val first = scene("first")
    val reordered = JSONObject().put("steps", first.getJSONArray("steps")).put("name", "first").put("id", "first")
    assertEquals(revision(JSONArray().put(first)), revision(JSONArray().put(reordered)))
    val scheduled = JSONObject(first.toString()).put("schedule", JSONObject().put("enabled", false)
        .put("hour", 8).put("minute", 30).put("days", JSONArray().put(2)))
    assertNotEquals(revision(JSONArray().put(first)), revision(JSONArray().put(scheduled)))
    assertNotEquals(revision(JSONArray().put(first)), revision(JSONArray().put(first).put(scene("second"))))
  }

  @Test fun matchingRevisionCommitsThenRejectsAnotherEditorOfTheOldList() = withStore(JSONArray()) { route, file, original ->
    val expected = revision(original)
    val first = JSONArray().put(scene("first-save"))
    val accepted = route.handle(request("POST", JSONObject().put("scenes", first).put("expectedRevision", expected)))
    assertEquals(200, accepted.status)
    val response = JSONObject(accepted.jsonBytes!!.toString(Charsets.UTF_8))
    assertEquals(revision(first), response.getString("revision"))
    val rejected = route.handle(request("POST", JSONObject().put("scenes", JSONArray().put(scene("stale-save")))
        .put("expectedRevision", expected)))
    assertEquals(409, rejected.status)
    assertEquals("first-save", JSONObject(file.readText()).getJSONArray("scenes").getJSONObject(0).getString("id"))
  }

  @Test fun legacyWriteWithoutARevisionStillReturnsTheNewRevision() = withStore(JSONArray()) { route, _, _ ->
    val replacement = JSONArray().put(scene("legacy"))
    val response = route.handle(request("POST", JSONObject().put("scenes", replacement)))
    assertEquals(200, response.status)
    assertEquals(revision(replacement), JSONObject(response.jsonBytes!!.toString(Charsets.UTF_8)).getString("revision"))
  }

  private fun request(method: String, body: JSONObject = JSONObject()): FleetHttpServer.Request {
    val bytes = if (method == "GET") byteArrayOf() else body.toString().toByteArray()
    return FleetHttpServer.Request(method, "/remote/scenes", "", mapOf("authorization" to "Bearer paired-fixture"),
        ByteArrayInputStream(bytes), bytes.size)
  }
  private fun withStore(scenes: JSONArray, block: (RemoteCompanionRoutes, java.io.File, JSONArray) -> Unit) {
    val directory = Files.createTempDirectory("scene-revision-test").toFile()
    val file = directory.resolve("remote-companion.json")
    file.writeText(JSONObject().put("scenes", scenes).toString())
    val context = mock(Context::class.java)
    val prefs = mock(SharedPreferences::class.java)
    `when`(context.noBackupFilesDir).thenReturn(directory)
    `when`(context.getSystemService(Context.ALARM_SERVICE)).thenReturn(mock(AlarmManager::class.java))
    `when`(context.getSharedPreferences("remote_pairing", Context.MODE_PRIVATE)).thenReturn(prefs)
    `when`(prefs.getString("sessions", null)).thenReturn("paired-fixture")
    `when`(prefs.getBoolean("enabled", false)).thenReturn(true)
    try {
      mockConstruction(AtomicFile::class.java) { atomic, _ ->
        `when`(atomic.openRead()).thenAnswer { FileInputStream(file) }
        `when`(atomic.startWrite()).thenAnswer { FileOutputStream(file) }
        doAnswer { it.getArgument<FileOutputStream>(0).close(); null }.`when`(atomic).finishWrite(any(FileOutputStream::class.java))
      }.use { block(RemoteCompanionRoutes(context), file, scenes) }
    } finally { directory.deleteRecursively() }
  }
}

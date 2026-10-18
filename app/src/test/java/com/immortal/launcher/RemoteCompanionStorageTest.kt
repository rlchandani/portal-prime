/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class RemoteCompanionStorageTest {
  private val unavailable = "Saved companion metadata is unavailable"
  private fun rejected(file: File, action: () -> Any?) {
    val original = file.readBytes()
    val error = runCatching(action).exceptionOrNull()
    assertTrue("must reject rather than invent an empty store", error is IllegalStateException)
    assertEquals(unavailable, error!!.message)
    assertNull(error.cause)
    assertArrayEquals("unreadable metadata must remain recoverable", original, file.readBytes())
  }
  private fun request(path: String, method: String = "GET", body: String = "", authorized: Boolean = true): FleetHttpServer.Request {
    val bytes = body.toByteArray()
    return FleetHttpServer.Request(method, path, "", if (authorized) mapOf("authorization" to "Bearer public-paired") else emptyMap(), ByteArrayInputStream(bytes), bytes.size)
  }
  private fun withStore(raw: ByteArray?, failRead: Boolean = false, block: (Context, File) -> Unit) {
    val directory = Files.createTempDirectory("public-companion-store").toFile()
    val file = directory.resolve("remote-companion.json")
    if (raw != null) file.writeBytes(raw)
    val context = mock(Context::class.java)
    val prefs = mock(SharedPreferences::class.java)
    `when`(context.noBackupFilesDir).thenReturn(directory)
    `when`(context.getSystemService(Context.ALARM_SERVICE)).thenReturn(mock(AlarmManager::class.java))
    `when`(context.getSharedPreferences("remote_pairing", Context.MODE_PRIVATE)).thenReturn(prefs)
    `when`(prefs.getString("sessions", null)).thenReturn("public-paired")
    `when`(prefs.getBoolean("enabled", false)).thenReturn(true)
    try {
      mockConstruction(AtomicFile::class.java) { atomic, _ ->
        `when`(atomic.baseFile).thenReturn(file)
        `when`(atomic.openRead()).thenAnswer {
          if (failRead) throw IOException("PUBLIC-PRIVATE-CONTENT-SENTINEL")
          FileInputStream(file)
        }
        `when`(atomic.startWrite()).thenAnswer { FileOutputStream(file) }
        doAnswer { it.getArgument<FileOutputStream>(0).close(); null }.`when`(atomic).finishWrite(any(FileOutputStream::class.java))
      }.use { block(context, file) }
    } finally { directory.deleteRecursively() }
  }
  @Test fun malformedExistingDataCannotBeReplacedByIdentityOrEitherCollection() {
    for (raw in listOf("", " ", "{", "[]", "null", "{} trailing", "{}/*comment*/", "{}\u0000", "{scenes:null}", "{scenes:{}}", "{scenes:[3]}", "{operations:null}", "{operations:[null]}")) {
      withStore(raw.toByteArray()) { c, file ->
        rejected(file) { RemoteCompanionStore.id(c) }
        rejected(file) { RemoteCompanionStore.scenes(c) }
        rejected(file) { RemoteCompanionStore.history(c) }
        rejected(file) { RemoteCompanionStore.update(c, "scenes", JSONArray()) }
      }
    }
  }
  @Test fun invalidUtf8CannotBeSilentlyReencodedAsReplacementText() {
    withStore(byteArrayOf(123,34,120,34,58,34,-1,34,125)) { c, file ->
      rejected(file) { RemoteCompanionStore.id(c) }
    }
  }
  @Test fun unreadableExistingFileDoesNotMasqueradeAsMissingAndNeverLeaksItsCause() {
    withStore("{public:1}".toByteArray(), failRead = true) { c, file ->
      rejected(file) { RemoteCompanionStore.id(c) }
      rejected(file) { RemoteCompanionStore.update(c, "operations", JSONArray()) }
    }
  }
  @Test fun unsearchableParentCannotMasqueradeAsMissingStorage() = withStore("{public:1}".toByteArray()) { c, file ->
    val original = file.readBytes()
    val parent = file.parentFile!!
    assertTrue(parent.setExecutable(false, false))
    try {
      assertTrue(parent.canRead())
      assertFalse(parent.canExecute())
      for (action in listOf<() -> Any?>({ RemoteCompanionStore.id(c) },
          { RemoteCompanionStore.scenes(c) }, { RemoteCompanionStore.history(c) },
          { RemoteCompanionStore.requireReadable(c) }, { RemoteCompanionStore.update(c, "scenes", JSONArray()) })) {
        val error = runCatching(action).exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(unavailable, error!!.message)
        assertNull(error.cause)
      }
    } finally { assertTrue(parent.setExecutable(true, true)) }
    assertArrayEquals(original, file.readBytes())
  }
  @Test fun genuinelyMissingStorageStillInitializesIdentityAndEmptyCollections() = withStore(null) { c, file ->
    assertEquals(0, RemoteCompanionStore.scenes(c).length())
    assertEquals(0, RemoteCompanionStore.history(c).length())
    assertFalse(file.exists())
    val id = RemoteCompanionStore.id(c)
    assertEquals(id, RemoteCompanionStore.id(c))
    assertEquals(id, JSONObject(file.readText()).getString("deviceId"))
  }
  @Test fun normalLegacyRowsUnknownFieldsAndJsonWhitespaceRemainCompatible() {
    val id = "00000000-0000-0000-0000-000000000001"
    val raw = "{deviceId:'$id',scenes:[{id:'legacy'}],operations:[{state:'succeeded'}],future:{keep:'public'}} \t\r\n"
    withStore(raw.toByteArray()) { c, file ->
      assertEquals(id, RemoteCompanionStore.id(c))
      assertEquals("legacy", RemoteCompanionStore.scenes(c).getJSONObject(0).getString("id"))
      assertEquals("succeeded", RemoteCompanionStore.history(c).getJSONObject(0).getString("state"))
      assertArrayEquals(raw.toByteArray(), file.readBytes())
      RemoteCompanionStore.update(c, "scenes", JSONArray())
      val saved = JSONObject(file.readText())
      assertEquals(id, saved.getString("deviceId"))
      assertEquals("public", saved.getJSONObject("future").getString("keep"))
      assertEquals("succeeded", saved.getJSONArray("operations").getJSONObject(0).getString("state"))
    }
  }
  @Test fun metadataEndpointsReturnSanitizedUnavailableBeforeServicesOrMutation() = withStore("{broken".toByteArray()) { c, file ->
    val routes = RemoteCompanionRoutes(c)
    for ((path, method, body) in listOf(
      Triple("/remote/companion", "GET", ""), Triple("/remote/scenes", "GET", ""),
      Triple("/remote/scenes", "POST", "{scenes:[]}"), Triple("/remote/operations", "GET", ""),
      Triple("/remote/operation", "POST", "{type:'cancel',id:'public'}"),
      Triple("/remote/scene", "POST", "{id:'public'}"), Triple("/remote/snapshot", "GET", ""))) {
      val original = file.readBytes()
      val response = routes.handle(request(path, method, body))
      assertEquals(path, 503, response.status)
      assertEquals("companion_storage_unavailable", JSONObject(response.jsonBytes!!.toString(Charsets.UTF_8)).getString("error"))
      assertArrayEquals(original, file.readBytes())
    }
    verify(c, never()).getSystemService(anyString())
  }
  @Test fun authorizationMethodAndBodyRejectionsPrecedeMetadataReads() = withStore("{broken".toByteArray(), failRead = true) { c, file ->
    val original = file.readBytes(); val routes = RemoteCompanionRoutes(c)
    assertEquals(401, routes.handle(request("/remote/scenes", authorized = false)).status)
    assertEquals(405, routes.handle(request("/remote/scenes", "PUT")).status)
    assertEquals(400, routes.handle(request("/remote/scenes", "POST", "{")).status)
    assertArrayEquals(original, file.readBytes())
  }
  @Test fun scheduledReceiverIgnoresUnreadableMetadataWithoutServicesOrRewrite() = withStore("{broken".toByteArray()) { c, file ->
    val original = file.readBytes()
    val intent = mock(Intent::class.java)
    `when`(intent.action).thenReturn("com.immortal.launcher.REMOTE_SCENE")
    `when`(intent.getStringExtra("id")).thenReturn("public")
    RemoteSceneReceiver().onReceive(c, intent)
    assertArrayEquals(original, file.readBytes())
    verify(c, never()).getSystemService(anyString())
  }
}

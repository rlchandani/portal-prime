/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import java.security.spec.MGF1ParameterSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/** The `/backup/…` routes end to end, on in-memory preferences and a temporary data directory. */
class FleetBackupRoutesTest {
  private val dataDir = Files.createTempDirectory("backup-routes").toFile()
  private val prefs = HashMap<String, ScreensaverTestPrefs>()
  private var androidId: String? = "a1b2c3d4e5f60718"
  private var now = 1_000_000L
  private val household = HashMap<String, Any>(mapOf("screensaver.shuffle" to true))
  private val appliedHousehold = ArrayList<Map<String, Any>>()

  private val platform = object : FleetBackupRoutes.Platform {
    override fun androidId(c: Context) = androidId
    override fun log(message: String) = Unit
    override fun read(file: File) = file.takeIf { it.isFile }?.readBytes()
    override fun write(file: File, bytes: ByteArray) { file.parentFile?.mkdirs(); file.writeBytes(bytes) }
    override fun household(c: Context): Map<String, Any> = HashMap(household)
    override fun householdWellFormed(key: String, value: Any?) = value.takeIf { key.startsWith("screensaver.") }
    override fun applyHousehold(c: Context, values: Map<String, Any>): Set<String> {
      appliedHousehold.add(values)
      return values.keys
    }
  }

  private val context: Context = mock(Context::class.java).also { c ->
    val info = mock(ApplicationInfo::class.java)
    info.dataDir = dataDir.absolutePath
    `when`(c.applicationInfo).thenReturn(info)
    `when`(c.filesDir).thenReturn(File(dataDir, "files"))
    `when`(c.noBackupFilesDir).thenReturn(File(dataDir, "no_backup"))
    `when`(c.getSharedPreferences(anyString(), anyInt())).thenAnswer { call ->
      prefs.getOrPut(call.getArgument(0)) { ScreensaverTestPrefs() }
    }
  }

  private val routes = FleetBackupRoutes(context, clock = { now }, platform = platform)

  private fun seedPrefs(name: String, values: Map<String, Any?>) {
    File(dataDir, "shared_prefs").mkdirs()
    File(dataDir, "shared_prefs/$name.xml").writeText("<map />")
    prefs[name] = ScreensaverTestPrefs(values)
  }

  private fun call(method: String, path: String, body: JSONObject? = null, advanceMs: Long = 3_000): Pair<Int, JSONObject> {
    now += advanceMs // past the per-route minimum gap
    val bytes = body?.toString()?.toByteArray() ?: ByteArray(0)
    val req = FleetHttpServer.Request(method, path, "", emptyMap(), ByteArrayInputStream(bytes), bytes.size)
    val response = routes.handle(req)
    return response.status to JSONObject(String(response.jsonBytes!!))
  }

  private fun enrol(keys: KeyPair = MAC) =
      call("POST", "/backup/key", JSONObject().put("publicKey", b64(keys.public.encoded)))

  /** Fleet enrolment plus the USB confirmation the adb receiver performs. */
  private fun enrolAndConfirm() {
    assertEquals(202, enrol().first)
    assertTrue(FleetBackupKeyStore.confirm(context, FleetBackupCrypto.keyId(MAC.public.encoded)))
  }

  private fun seedPortal() {
    seedPrefs("immortal_screensaver", mapOf("dark_room_lux" to 7, "face" to "flip", "scale" to 0.5f,
        "last" to 99L, "shuffle" to true, "tags" to setOf("a", "b")))
    seedPrefs("immortal_household", mapOf("k" to "ab".repeat(32)))
    seedPrefs("remote_pairing", mapOf("sessions" to setOf("phone-session-token")))
    seedPrefs("fleet_agent", mapOf("token" to "fleet-token-must-stay"))
    File(dataDir, "files").mkdirs()
    File(dataDir, "files/household_list.json").writeText("{\"items\":[\"milk\"]}")
  }

  /** Export, then open the archive as the Mac would. */
  private fun exportAndOpen(): Pair<JSONObject, ByteArray> {
    val (status, body) = call("POST", "/backup/export", JSONObject().put("keyId", FleetBackupCrypto.keyId(MAC.public.encoded)))
    assertEquals(body.toString(), 200, status)
    val envelope = FleetBackupCrypto.parseEnvelope(body.getJSONObject("backup"), 1 shl 24)
    return body to FleetBackupCrypto.open(envelope, MAC.private, 1 shl 24)
  }

  /** The Mac's half of an import: unwrap the data key and re-wrap it to the Portal's one-time key. */
  private fun importBody(backup: JSONObject, crossDevice: Boolean = false, dryRun: Boolean = false): JSONObject {
    val (status, begin) = call("POST", "/backup/import", JSONObject().put("op", "begin"))
    assertEquals(200, status)
    val portal = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(begin.getString("publicKey"))))
    val oaep = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
    val dataKey = Cipher.getInstance("RSA/ECB/OAEPPadding").apply { init(Cipher.DECRYPT_MODE, MAC.private, oaep) }
        .doFinal(Base64.getDecoder().decode(backup.getString("wrappedKey")))
    val rewrapped = Cipher.getInstance("RSA/ECB/OAEPPadding").apply { init(Cipher.ENCRYPT_MODE, portal, oaep) }.doFinal(dataKey)
    return JSONObject().put("op", "apply").put("session", begin.getString("session"))
        .put("backup", JSONObject(backup.toString()).put("wrappedKey", b64(rewrapped)))
        .put("crossDevice", crossDevice).put("dryRun", dryRun)
  }

  @Test fun aFleetEnrolmentWaitsForUsbConfirmationThenTheActiveKeyWins() {
    assertEquals(false, call("GET", "/backup/key").second.getBoolean("enrolled"))
    val (status, body) = enrol()
    assertEquals(202, status)
    assertEquals("pending", body.getString("result"))
    val id = FleetBackupCrypto.keyId(MAC.public.encoded)
    assertEquals(id, call("GET", "/backup/key").second.getString("pendingKeyId"))
    seedPortal()
    assertEquals("backup_key_not_enrolled", call("POST", "/backup/export").second.getString("error"))
    assertFalse(FleetBackupKeyStore.confirm(context, FleetBackupCrypto.keyId(OTHER.public.encoded)))
    assertEquals(202, enrol(OTHER).first) // a later proposal replaces the pending one...
    assertFalse(FleetBackupKeyStore.confirm(context, id)) // ...so the old id no longer confirms
    enrol()
    assertTrue(FleetBackupKeyStore.confirm(context, id))
    assertEquals(true, call("GET", "/backup/key").second.getBoolean("enrolled"))
    assertEquals("unchanged", enrol().second.getString("result"))
    val (mismatch, refused) = enrol(OTHER)
    assertEquals(409, mismatch)
    assertEquals("backup_key_mismatch", refused.getString("error"))
    assertFalse(FleetBackupKeyStore.confirm(context, FleetBackupCrypto.keyId(OTHER.public.encoded)))
    assertEquals(id, call("GET", "/backup/key").second.getString("keyId"))
  }

  @Test fun forgettingTheKeyOverUsbAllowsANewOne() {
    enrolAndConfirm()
    assertTrue(FleetBackupKeyStore.forget(context))
    assertEquals(202, enrol(OTHER).first)
    assertTrue(FleetBackupKeyStore.confirm(context, FleetBackupCrypto.keyId(OTHER.public.encoded)))
    assertEquals(FleetBackupCrypto.keyId(OTHER.public.encoded), call("GET", "/backup/key").second.getString("keyId"))
  }

  @Test fun weakOrMalformedKeysAreRefused() {
    val small = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    assertEquals(400, enrol(small).first)
    assertEquals(400, call("POST", "/backup/key", JSONObject().put("publicKey", "%%")).first)
    assertEquals(400, call("POST", "/backup/key", JSONObject()).first)
    assertEquals(false, call("GET", "/backup/key").second.getBoolean("enrolled"))
  }

  @Test fun exportNeedsAnEnrolledMatchingKey() {
    seedPortal()
    assertEquals("backup_key_not_enrolled", call("POST", "/backup/export").second.getString("error"))
    enrolAndConfirm()
    val (status, body) = call("POST", "/backup/export", JSONObject().put("keyId", "0000000000000000"))
    assertEquals(409, status)
    assertEquals("backup_key_mismatch", body.getString("error"))
  }

  @Test fun theExportIsSealedAndCarriesPrefsButNeverTheFleetToken() {
    seedPortal()
    enrolAndConfirm()
    val (reply, plain) = exportAndOpen()
    assertFalse(reply.toString().contains("phone-session-token"))
    assertFalse(reply.toString().contains("ab".repeat(32)))
    val archive = FleetBackupArchive.decode(plain, platform::householdWellFormed)
    assertEquals(setOf("immortal_screensaver", "immortal_household", "remote_pairing"), archive.prefs.keys)
    assertEquals(FleetBackupArchive.PrefValue.IntV(7), archive.prefs.getValue("immortal_screensaver").getValue("dark_room_lux"))
    assertEquals(setOf("files/household_list.json"), archive.files.keys)
    assertEquals(mapOf("screensaver.shuffle" to true), archive.household)
    assertFalse(String(plain).contains("fleet-token-must-stay"))
    assertEquals(FleetBackupRoutes.deviceIdOf("a1b2c3d4e5f60718"), archive.device)
  }

  @Test fun sameDeviceImportRestoresPreferencesAndDataFiles() {
    seedPortal()
    enrolAndConfirm()
    val backup = call("POST", "/backup/export").second.getJSONObject("backup")
    prefs.getValue("immortal_screensaver").edit().putInt("dark_room_lux", 12).putString("extra", "x").commit()
    prefs.getValue("fleet_agent").edit().putString("token", "fresh-token").commit()
    File(dataDir, "files/household_list.json").writeText("{}")
    val (status, body) = call("POST", "/backup/import", importBody(backup))
    assertEquals(body.toString(), 200, status)
    assertTrue(body.getBoolean("sameDevice"))
    assertEquals(3, body.getJSONObject("applied").getInt("prefFiles"))
    val screensaver = prefs.getValue("immortal_screensaver")
    assertEquals(7, screensaver.getInt("dark_room_lux", 0))
    assertFalse(screensaver.contains("extra")) // the file is restored as it was
    assertEquals(0.5f, screensaver.getFloat("scale", 0f))
    assertEquals(setOf("a", "b"), screensaver.getStringSet("tags", null))
    assertEquals("fresh-token", prefs.getValue("fleet_agent").getString("token", null))
    assertEquals("{\"items\":[\"milk\"]}", File(dataDir, "files/household_list.json").readText())
    assertTrue(appliedHousehold.isEmpty())
  }

  @Test fun theExportCarriesTheHouseholdKeyWhereverItIsStored() {
    seedPortal()
    // Sealed in the Keystore, the key is no longer a prefs file the export would list on its own.
    File(dataDir, "shared_prefs/immortal_household.xml").delete()
    enrolAndConfirm()
    val (_, plain) = exportAndOpen()
    val archive = FleetBackupArchive.decode(plain, platform::householdWellFormed)
    assertEquals(mapOf("k" to FleetBackupArchive.PrefValue.Str("ab".repeat(32))),
        archive.prefs.getValue("immortal_household"))
  }

  @Test fun sameDeviceImportBringsTheHouseholdKeyBack() {
    seedPortal()
    enrolAndConfirm()
    val backup = call("POST", "/backup/export").second.getJSONObject("backup")
    assertTrue(HouseholdKey.set(context, ByteArray(32) { 0x11 }))
    val (status, body) = call("POST", "/backup/import", importBody(backup))
    assertEquals(body.toString(), 200, status)
    assertEquals("ab".repeat(32), HouseholdKey.get(context)?.let(HouseholdKey::toHex))
  }

  @Test fun anotherDevicesArchiveIsRefusedWithoutCrossDevice() {
    seedPortal()
    enrolAndConfirm()
    val backup = call("POST", "/backup/export").second.getJSONObject("backup")
    androidId = "ffffffffffffffff"
    prefs.getValue("immortal_screensaver").edit().putInt("dark_room_lux", 12).commit()
    val (status, body) = call("POST", "/backup/import", importBody(backup))
    assertEquals(409, status)
    assertEquals("different_device", body.getString("error"))
    assertEquals(12, prefs.getValue("immortal_screensaver").getInt("dark_room_lux", 0))
  }

  @Test fun theSameSerialConfirmationRestoresAfterAnIdentityChangeButNotWithCrossDevice() {
    seedPortal()
    enrolAndConfirm()
    val backup = call("POST", "/backup/export").second.getJSONObject("backup")
    androidId = "ffffffffffffffff" // reinstalled with another signing key
    prefs.getValue("immortal_screensaver").edit().putInt("dark_room_lux", 12).commit()
    val both = importBody(backup, crossDevice = true).put("sameSerial", true)
    assertEquals("bad_scope", call("POST", "/backup/import", both).second.getString("error"))
    val (status, body) = call("POST", "/backup/import", importBody(backup).put("sameSerial", true))
    assertEquals(body.toString(), 200, status)
    assertFalse(body.getBoolean("sameDevice"))
    assertEquals(7, prefs.getValue("immortal_screensaver").getInt("dark_room_lux", 0))
  }

  @Test fun crossDeviceAppliesOnlyHouseholdSettings() {
    seedPortal()
    enrolAndConfirm()
    val backup = call("POST", "/backup/export").second.getJSONObject("backup")
    androidId = "ffffffffffffffff"
    prefs.getValue("immortal_household").edit().putString("k", "cd".repeat(32)).commit()
    prefs.getValue("remote_pairing").edit().putStringSet("sessions", mutableSetOf("new-phone")).commit()
    val (status, body) = call("POST", "/backup/import", importBody(backup, crossDevice = true))
    assertEquals(body.toString(), 200, status)
    assertEquals(listOf(mapOf<String, Any>("screensaver.shuffle" to true)), appliedHousehold)
    assertEquals("cd".repeat(32), prefs.getValue("immortal_household").getString("k", null))
    assertEquals(setOf("new-phone"), prefs.getValue("remote_pairing").getStringSet("sessions", null))
  }

  @Test fun aDryRunWritesNothing() {
    seedPortal()
    enrolAndConfirm()
    val backup = call("POST", "/backup/export").second.getJSONObject("backup")
    prefs.getValue("immortal_screensaver").edit().putInt("dark_room_lux", 12).commit()
    val (status, body) = call("POST", "/backup/import", importBody(backup, dryRun = true))
    assertEquals(200, status)
    assertTrue(body.getBoolean("dryRun"))
    assertEquals(3, body.getJSONObject("plan").getInt("prefFiles"))
    assertEquals(12, prefs.getValue("immortal_screensaver").getInt("dark_room_lux", 0))
  }

  @Test fun anImportSessionIsSingleUseAndExpires() {
    seedPortal()
    enrolAndConfirm()
    val backup = call("POST", "/backup/export").second.getJSONObject("backup")
    val request = importBody(backup, dryRun = true)
    assertEquals(200, call("POST", "/backup/import", request).first)
    assertEquals("import_session_unknown", call("POST", "/backup/import", request).second.getString("error"))
    val late = importBody(backup, dryRun = true)
    now += FleetBackupRoutes.SESSION_MS
    assertEquals("import_session_expired", call("POST", "/backup/import", late).second.getString("error"))
  }

  @Test fun theOriginalWrappedKeyCannotBeImported() {
    seedPortal()
    enrolAndConfirm()
    val backup = call("POST", "/backup/export").second.getJSONObject("backup")
    val request = importBody(backup).put("backup", backup) // not re-wrapped to the one-time key
    val (status, body) = call("POST", "/backup/import", request)
    assertEquals(422, status)
    assertEquals("wrong_key", body.getString("error"))
  }

  @Test fun aTamperedArchiveIsRefusedBeforeAnyWrite() {
    seedPortal()
    enrolAndConfirm()
    val backup = call("POST", "/backup/export").second.getJSONObject("backup")
    prefs.getValue("immortal_screensaver").edit().putInt("dark_room_lux", 12).commit()
    val request = importBody(backup)
    val sealed = request.getJSONObject("backup")
    val ciphertext = Base64.getDecoder().decode(sealed.getString("ciphertext")).also { it[0] = (it[0].toInt() xor 1).toByte() }
    sealed.put("ciphertext", b64(ciphertext))
    val (status, body) = call("POST", "/backup/import", request)
    assertEquals(422, status)
    assertEquals("seal_broken", body.getString("error"))
    assertEquals(12, prefs.getValue("immortal_screensaver").getInt("dark_room_lux", 0))
  }

  @Test fun anImportsApplyMayFollowItsBeginAtOnceButBeginsAreLimited() {
    seedPortal()
    enrolAndConfirm()
    val backup = call("POST", "/backup/export").second.getJSONObject("backup")
    val request = importBody(backup, dryRun = true) // its begin runs now
    assertEquals(200, call("POST", "/backup/import", request, advanceMs = 0).first)
    val (status, body) = call("POST", "/backup/import", JSONObject().put("op", "begin"), advanceMs = 0)
    assertEquals(429, status)
    assertEquals("backup_rate_limited", body.getString("error"))
    assertEquals(200, call("POST", "/backup/import", JSONObject().put("op", "begin")).first)
  }

  @Test fun methodsAreCheckedAndCallsAreRateLimited() {
    assertEquals(405, call("GET", "/backup/export").first)
    assertEquals(405, call("GET", "/backup/import").first)
    val req = { FleetHttpServer.Request("GET", "/backup/key", "", emptyMap(), ByteArrayInputStream(ByteArray(0)), 0) }
    assertEquals(200, routes.handle(req()).status)
    val limited = routes.handle(req())
    assertEquals(429, limited.status)
    assertEquals("2", limited.extraHeaders["Retry-After"])
  }

  @Test fun exportFailsClosedWithoutADeviceIdentity() {
    seedPortal()
    enrolAndConfirm()
    androidId = null
    assertEquals("device_identity_unavailable", call("POST", "/backup/export").second.getString("error"))
  }

  private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

  companion object {
    private val MAC: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
    private val OTHER: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
  }
}

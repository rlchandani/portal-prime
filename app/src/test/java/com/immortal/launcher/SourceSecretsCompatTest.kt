/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding 1: `GET /remote/sources` no longer returns plaintext secrets, and every phone/Portal
 * version combination keeps the saved secrets on an unchanged save.
 *
 * | Phone                                   | Portal | Save sends               | Result |
 * |-----------------------------------------|--------|--------------------------|--------|
 * | Toolkit <= 1.8.19 / web page (strips)   | new    | no secret fields         | kept   |
 * | an old form that round-trips every field| new    | the mask from the read   | kept   |
 * | a form that blanks unknown fields       | new    | "" for each secret       | kept   |
 * | Toolkit 1.8.20 (sends typed only)       | old    | no secret fields         | kept (old Portal keeps omitted secrets) |
 * | Toolkit 1.8.20                          | new    | no secret fields         | kept   |
 * | any phone, user typed a new secret      | either | the new value            | replaced |
 */
class SourceSecretsCompatTest {
  private val immichKey = "imm-0123456789-abcdef-XY"
  private val stored = ScreensaverConfig.Settings(
      source = ScreensaverConfig.SOURCE_IMMICH,
      immichUrl = "http://192.168.1.5:2283", immichKey = immichKey, immichAlbumId = "a1",
      smbHost = "nas.local", smbShare = "photos", smbUser = "reader", smbPass = "smb-secret",
      davUrl = "https://dav.example/photos", davUser = "reader", davPass = "dav-secret")

  private val read = FleetScreensaver.sourcesJson(stored)

  /** Every non-secret field as a form would show it, plus [secret] for each secret slot. */
  private fun roundTrip(secret: (String) -> Any?): JSONObject {
    val out = JSONObject()
    for (key in listOf("immichUrl", "immichAlbumId", "immichAlbumName", "smbHost", "smbShare", "smbPath", "smbUser", "davUrl", "davUser")) {
      out.put(key, read.getString(key))
    }
    for (key in SourceSecrets.FIELDS) secret(key)?.let { out.put(key, it) }
    return out
  }

  private fun assertAllKept(body: JSONObject) {
    val prepared = RemoteCompanionSources.prepare(body, stored)
    assertNull(prepared.error)
    assertEquals(immichKey, prepared.body!!.getString("immichKey"))
    assertEquals("smb-secret", prepared.body.getString("smbPass"))
    assertEquals("dav-secret", prepared.body.getString("davPass"))
  }

  @Test fun readNeverContainsAnySecret() {
    val text = read.toString()
    for (secret in listOf(immichKey, "smb-secret", "dav-secret")) assertFalse(secret, text.contains(secret))
    for (key in SourceSecrets.FIELDS) assertEquals(SourceSecrets.MASK, read.getString(key))
    assertTrue(read.getBoolean("hasImmichKey") && read.getBoolean("smbPassConfigured") && read.getBoolean("davPassConfigured"))
    assertEquals("XY", read.getString("immichKeyHint"))
  }

  @Test fun unsetSecretsReadAsBlankWithoutAMask() {
    val json = FleetScreensaver.sourcesJson(ScreensaverConfig.Settings())
    for (key in SourceSecrets.FIELDS) assertEquals("", json.getString(key))
    assertEquals("", json.getString("immichKeyHint"))
  }

  @Test fun phoneThatOmitsSecretsKeepsThem() = assertAllKept(roundTrip { null })

  @Test fun oldPhoneThatRoundTripsTheMaskKeepsThem() = assertAllKept(roundTrip { read.getString(it) })

  @Test fun oldPhoneThatBlanksSecretsKeepsThem() = assertAllKept(roundTrip { "" })

  @Test fun anyRunOfBulletsCountsAsMasked() {
    assertTrue(SourceSecrets.isMasked("••••"))
    assertFalse(SourceSecrets.isMasked(""))
    assertFalse(SourceSecrets.isMasked("••••xy"))
    assertAllKept(roundTrip { "••••" })
  }

  @Test fun typedSecretReplacesOnlyThatSecret() {
    val prepared = RemoteCompanionSources.prepare(roundTrip { if (it == "smbPass") "new-smb" else read.getString(it) }, stored)
    assertEquals("new-smb", prepared.body!!.getString("smbPass"))
    assertEquals(immichKey, prepared.body.getString("immichKey"))
    assertEquals("dav-secret", prepared.body.getString("davPass"))
  }

  @Test fun maskNeverFollowsTheSecretToANewServer() {
    val moved = JSONObject().put("immichUrl", "http://192.168.1.99:2283").put("immichKey", SourceSecrets.MASK)
    assertEquals("immichKey_required", RemoteCompanionSources.prepare(moved, stored).error)
    val newAccount = JSONObject().put("smbHost", "nas.local").put("smbShare", "photos").put("smbUser", "other").put("smbPass", SourceSecrets.MASK)
    assertEquals("smbPass_required", RemoteCompanionSources.prepare(newAccount, stored).error)
  }

  @Test fun maskedAlbumPickerKeyUsesTheSavedKeyForTheSavedServer() {
    // /remote/immich/albums builds its candidate the same way; the mask resolves to the saved key.
    val candidate = JSONObject().put("immichUrl", stored.immichUrl).put("immichKey", SourceSecrets.MASK)
    assertEquals(immichKey, RemoteCompanionSources.prepare(candidate, stored).body!!.getString("immichKey"))
  }

  @Test fun nonStringSecretIsStillRefused() {
    assertEquals("invalid_source_field", RemoteCompanionSources.prepare(JSONObject().put("smbPass", 5), stored).error)
  }

  @Test fun shortSecretsGetNoHint() {
    assertEquals("", SourceSecrets.hint("pass"))
    assertEquals("", SourceSecrets.hint(null))
    assertEquals("cd", SourceSecrets.hint("0123456789abcdcd"))
  }
}

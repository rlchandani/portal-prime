/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteCompanionSourcesTest {
  private val stored = ScreensaverConfig.Settings(immichUrl="https://photos.example", immichKey="test-key", smbHost="nas.local", smbShare="photos", smbUser="reader", smbPass="test-smb-pass", davUrl="https://dav.example/photos", davUser="reader", davPass="test-dav-pass")
  @Test fun omittedSecretsPreservedOnlyForSameEndpointAndAccount() {
    val immich = RemoteCompanionSources.prepare(JSONObject().put("immichUrl", "https://PHOTOS.example/api/"), stored)
    assertNull(immich.error)
    assertEquals("test-key", immich.body!!.getString("immichKey"))
    val smb = RemoteCompanionSources.prepare(JSONObject().put("smbHost", "nas.local").put("smbShare", "photos").put("smbUser", "reader"), stored)
    assertNull(smb.error)
    assertEquals("test-smb-pass", smb.body!!.getString("smbPass"))
    val dav = RemoteCompanionSources.prepare(JSONObject().put("davUrl", "https://dav.example/photos/").put("davUser", "reader"), stored)
    assertNull(dav.error)
    assertEquals("test-dav-pass", dav.body!!.getString("davPass"))
  }
  @Test fun changedDestinationOrAccountCannotInheritSecret() {
    for (body in listOf(JSONObject().put("immichUrl", "https://other.example"), JSONObject().put("smbHost", "other.local").put("smbShare", "photos").put("smbUser", "reader"), JSONObject().put("davUrl", "https://other.example/photos").put("davUser", "reader"), JSONObject().put("davUrl", "https://dav.example/photos").put("davUser", "different"))) {
      val result = RemoteCompanionSources.prepare(body, stored)
      assertNotNull(result.error)
      assertNull(result.body)
    }
    val valid = RemoteCompanionSources.prepare(JSONObject().put("immichUrl", "https://other.example").put("immichKey", "new-test-key"), stored)
    assertNull(valid.error)
  }
  @Test fun anonymousNewSourcesDoNotReceiveSavedPasswords() {
    val body = RemoteCompanionSources.prepare(JSONObject().put("davUrl", "https://public.example/photos").put("davUser", ""), stored).body!!
    assertEquals("", body.getString("davPass"))
    assertEquals("", body.getString("davUser"))
  }
  @Test fun sourceReadbackIncludesBothExplicitConfiguredFlags() {
    val json = FleetScreensaver.sourcesJson(stored)
    for (key in listOf("hasImmichKey", "hasSmbPass", "hasDavPass", "immichKeyConfigured", "smbPassConfigured", "davPassConfigured")) assertTrue(key, json.getBoolean(key))
  }
}

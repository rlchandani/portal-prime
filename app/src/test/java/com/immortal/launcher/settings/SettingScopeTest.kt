package com.immortal.launcher.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tripwires for household settings sync. Every registered setting must say whether it is the same
 * on every Portal (household, synced) or belongs to this Portal; credentials and the settings that
 * open the camera or describe the room must never be household.
 */
class SettingScopeTest {
  private val domains = SettingsDomains.all + SettingsDomains.syncOnly
  private val all: List<Pair<String, SettingSpec<*>>> =
      domains.flatMap { d -> d.specs.map { "${d.id}.${it.key}" to it } }

  @Test
  fun everyRegisteredSettingDeclaresAScope() {
    val missing = all.filter { it.second.scope == null }.map { it.first }
    assertTrue("declare scope = SettingScope.HOUSEHOLD / DEVICE / DEVICE_SECRET on: $missing", missing.isEmpty())
  }

  @Test
  fun everySecretIsDeviceSecretAndNeverSyncs() {
    val secrets = all.filter { (it.second as? StringSpec<*>)?.secret == true }
    assertEquals(setOf("immortal.maPassword", "immortal.maApiToken", "mqtt.password"), secrets.map { it.first }.toSet())
    secrets.forEach { (key, spec) -> assertEquals(key, SettingScope.DEVICE_SECRET, spec.scope) }
    assertTrue(SettingsSyncCatalog.household.keys.none { it in secrets.map { s -> s.first } })
  }

  @Test
  fun roomCameraAndConnectionSettingsStayOnThisPortal() {
    val local = listOf("fleet.name", "calling.dropInsOn", "calling.dropInRooms", "controlcenter.sharedRoom",
        "controlcenter.portalMode", "controlcenter.planStyle", "controlcenter.planRoom", "mqtt.host", "mqtt.username", "calendar.url", "immortal.maPlayerId",
        "screensaver.darkRoomLux", "screensaver.batterySaver", "sunrise.enabled", "guests.ssid", "guests.welcome")
    val scopes = all.toMap()
    local.forEach { key ->
      val spec = scopes[key] ?: return@forEach fail("$key is not registered any more; update this test")
      assertFalse("$key must not sync", spec.effectiveScope.synced)
    }
    SettingsSyncCatalog.NEVER_SYNC.forEach { assertFalse("$it is household", scopes[it]?.effectiveScope?.synced == true) }
    assertTrue(SettingsSyncCatalog.household.keys.intersect(SettingsSyncCatalog.NEVER_SYNC).isEmpty())
  }

  @Test
  fun householdCoversTheSharedPolicies() {
    val keys = SettingsSyncCatalog.household.keys
    listOf("screensaver.mode", "screensaver.overnightNightClock", "digitalclock.face", "chime.quietHoursOn",
        "chime.hourlyChimeOn", "music.quietCap", "controlcenter.askUnlock", "controlcenter.mapPeople", "calling.ringtone", "calling.whoCanCall",
        "immortal.settingsTheme", "photoframe.faceId", "photoframe.sharedSource", "followme.mode").forEach {
      assertTrue("$it should sync", it in keys)
    }
  }

  @Test
  fun grandmaModeAndAutoAnswerAreOutsideSync() {
    val keys = SettingsSyncCatalog.household.keys.map { it.lowercase() }
    listOf("grandma", "autoanswer", "auto_answer", "whosthere", "recogn", "rotation", "screenbrightness", "debug", "householdkey")
        .forEach { word -> assertTrue("$word must not sync: ${keys.filter { word in it }}", keys.none { word in it }) }
  }

  @Test
  fun derivedAndNavigationRowsAreNeverCarried() {
    val carried = SettingsSyncCatalog.household.entries.values.map { it.spec }
    assertTrue(carried.none { it is DerivedSpec<*> || it is NavSpec<*> })
  }

  @Test
  fun aSecretCannotBeDeclaredHousehold() {
    try {
      StringSpec<Unit>("token", "Token", get = { "" }, set = { _, _ -> }, secret = true, scope = SettingScope.HOUSEHOLD)
      fail("a household secret must be refused")
    } catch (expected: IllegalArgumentException) {
      assertTrue(expected.message!!.contains("token"))
    }
  }

  @Test
  fun theCatalogRefusesADropInSwitchMarkedHousehold() {
    val forged = SettingsDomain<Unit>("calling", "Calls", load = { }, specs = listOf(
        BoolSpec("dropInsOn", "Drop-ins", get = { false }, set = { _, _ -> }, scope = SettingScope.HOUSEHOLD)))
    try {
      SettingsSyncCatalog(listOf(forged))
      fail("drop-ins must never be household")
    } catch (expected: IllegalStateException) {
      assertTrue(expected.message!!.contains("calling.dropInsOn"))
    }
  }

  @Test
  fun anUndeclaredSettingIsTreatedAsThisPortalOnly() {
    val spec = BoolSpec<Unit>("x", "X", get = { false }, set = { _, _ -> })
    assertEquals(SettingScope.DEVICE, spec.effectiveScope)
    val catalog = SettingsSyncCatalog(listOf(SettingsDomain("d", "D", load = { }, specs = listOf(spec))))
    assertTrue(catalog.keys.isEmpty())
  }
}

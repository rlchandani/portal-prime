package com.immortal.launcher.settings

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/** Schema validation of incoming values, and the apply path through the registry. */
class SettingsSyncCatalogTest {
  private val writes = LinkedHashMap<String, Any>()
  private var appliedBatches = 0

  private data class Snap(val on: Boolean = false, val level: Int = 5, val face: String = "classic",
                          val host: String = "", val token: String = "", val room: String = "Kitchen")

  private var snap = Snap()

  private val domain = SettingsDomain(
      id = "test", title = "Test", load = { snap },
      specs = listOf(
          BoolSpec<Snap>("on", "On", get = { it.on }, set = { _, v -> writes["on"] = v; snap = snap.copy(on = v) },
              scope = SettingScope.HOUSEHOLD),
          IntSpec<Snap>("level", "Level", get = { it.level }, set = { _, v -> writes["level"] = v }, min = 0, max = 10,
              scope = SettingScope.HOUSEHOLD),
          IntSpec<Snap>("minute", "Minute", get = { 0 }, set = { _, v -> writes["minute"] = v }, min = 0, max = 1439,
              wrap = true, scope = SettingScope.HOUSEHOLD),
          EnumSpec<Snap>("face", "Face", get = { it.face }, set = { _, v -> writes["face"] = v },
              options = listOf("classic" to "Classic", "neon" to "Neon"), scope = SettingScope.HOUSEHOLD),
          StringSpec<Snap>("host", "Host", get = { it.host }, set = { _, v -> writes["host"] = v },
              applyWhen = { it.isNotBlank() }, scope = SettingScope.HOUSEHOLD),
          StringSpec<Snap>("token", "Token", get = { it.token }, set = { _, v -> writes["token"] = v }, secret = true,
              scope = SettingScope.DEVICE_SECRET),
          StringSpec<Snap>("room", "Room", get = { it.room }, set = { _, v -> writes["room"] = v }, scope = SettingScope.DEVICE),
          DerivedSpec<Snap>("derived", get = { true }, scope = SettingScope.HOUSEHOLD),
      ),
      defaults = { Snap() },
      onApplied = { _, _ -> appliedBatches++ },
  )
  private val catalog = SettingsSyncCatalog(listOf(domain))
  private val context: Context = mock(Context::class.java)

  @Test
  fun onlyHouseholdValueSettingsAreCarried() {
    assertEquals(setOf("test.on", "test.level", "test.minute", "test.face", "test.host"), catalog.keys)
  }

  @Test
  fun valuesMustMatchTheSettingsSchema() {
    assertEquals(true, catalog.validate("test.on", true))
    assertNull(catalog.validate("test.on", "true"))
    assertNull(catalog.validate("test.on", 1))
    assertEquals(7, catalog.validate("test.level", 7))
    assertEquals(7, catalog.validate("test.level", 7L))
    assertEquals(7, catalog.validate("test.level", 7.0))
    assertNull(catalog.validate("test.level", 7.5))
    assertNull(catalog.validate("test.level", 11))
    assertNull(catalog.validate("test.level", -1))
    assertNull(catalog.validate("test.level", Long.MAX_VALUE))
    assertNull("a wrap field is still range-checked when synced", catalog.validate("test.minute", 1440))
    assertEquals("neon", catalog.validate("test.face", "neon"))
    assertNull(catalog.validate("test.face", "Neon"))
    assertNull(catalog.validate("test.face", "rainbow"))
    assertEquals("nas.local", catalog.validate("test.host", "nas.local"))
    assertNull(catalog.validate("test.host", ""))
    assertNull(catalog.validate("test.host", "nas\u0000"))
    assertNull(catalog.validate("test.host", "x".repeat(SettingsSyncCatalog.MAX_STRING + 1)))
  }

  @Test
  fun unknownSecretAndDeviceKeysAreNeverAccepted() {
    assertNull(catalog.validate("test.token", "abc"))
    assertNull(catalog.wellFormed("test.token", "abc"))
    assertNull(catalog.validate("test.room", "Attic"))
    assertNull(catalog.validate("test.derived", true))
    assertNull(catalog.validate("other.on", true))
    assertNull(catalog.wellFormed("test.nope", true))
  }

  @Test
  fun wellFormedKeepsValuesThisBuildCannotApply() {
    assertEquals("rainbow", catalog.wellFormed("test.face", "rainbow"))
    assertEquals(11, catalog.wellFormed("test.level", 11))
    assertNull(catalog.wellFormed("test.level", "11"))
    assertNull(catalog.wellFormed("test.face", "a\nb"))
  }

  @Test
  fun readsCurrentValuesAndDefaults() {
    snap = Snap(on = true, level = 3, face = "neon", host = "", token = "secret", room = "Attic")
    val current = catalog.read(context)
    assertEquals(mapOf("test.on" to true, "test.level" to 3, "test.minute" to 0, "test.face" to "neon", "test.host" to ""), current)
    assertTrue("no secret or device value is ever read for sync", current.keys.none { it == "test.token" || it == "test.room" })
    assertEquals(mapOf("test.on" to false, "test.level" to 5, "test.minute" to 0, "test.face" to "classic", "test.host" to ""),
        catalog.defaults())
  }

  @Test
  fun siblingsCoverTheWholeDomainSoSideEffectWritesAreNotEchoed() {
    assertEquals(catalog.keys, catalog.siblingKeys(listOf("test.face")))
    assertTrue(catalog.siblingKeys(listOf("other.x")).isEmpty())
    val face = SettingsSyncCatalog.household.siblingKeys(listOf("digitalclock.face"))
    assertTrue(face.containsAll(listOf("digitalclock.style", "digitalclock.font", "digitalclock.color", "digitalclock.glow")))
    assertTrue(face.none { !it.startsWith("digitalclock.") })
  }

  @Test
  fun applyWritesValidValuesThroughTheRegistryOncePerDomain() {
    val applied = catalog.apply(context, mapOf("test.on" to true, "test.face" to "neon", "test.level" to 99,
        "test.token" to "stolen", "test.room" to "Hall"))
    assertEquals(setOf("test.on", "test.face"), applied)
    assertEquals(mapOf<String, Any>("on" to true, "face" to "neon"), writes)
    assertEquals(1, appliedBatches)
  }
}

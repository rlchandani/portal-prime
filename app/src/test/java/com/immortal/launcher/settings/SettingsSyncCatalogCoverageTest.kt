/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.settings

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/** Catalog build guards, numeric coercion edges, strict enum coercion and the apply/read edge paths. */
class SettingsSyncCatalogCoverageTest {
  private data class Snap(val level: Int = 5, val face: String = "classic")

  private val writes = LinkedHashMap<String, Any>()
  private val context: Context = mock(Context::class.java)

  private fun domain(id: String = "test", vararg specs: SettingSpec<Snap>, defaults: (() -> Snap)? = { Snap() }) =
      SettingsDomain(id = id, title = "T", load = { Snap() }, specs = specs.toList(), defaults = defaults)

  private fun level(key: String = "level") = IntSpec<Snap>(key, "Level", get = { it.level }, set = { _, v -> writes[key] = v },
      min = -10, max = 10, scope = SettingScope.HOUSEHOLD)

  private val strictFace = EnumSpec<Snap>("face", "Face", get = { it.face }, set = { _, v -> writes["face"] = v },
      options = listOf("classic" to "Classic", "neon" to "Neon", "old" to "Old"),
      coerce = { if (it == "old") "classic" else it }, scope = SettingScope.HOUSEHOLD)

  private val catalog = SettingsSyncCatalog(listOf(domain("test", level(), strictFace)))

  @Test fun aNeverSyncKeyMarkedHouseholdRefusesToBuild() {
    val name = StringSpec<Snap>("name", "Name", get = { "" }, set = { _, _ -> }, scope = SettingScope.HOUSEHOLD)
    val error = assertThrows(IllegalStateException::class.java) { SettingsSyncCatalog(listOf(domain("fleet", name))) }
    assertTrue(error.message!!.contains("fleet.name"))
  }

  @Test fun anOverlongKeyRefusesToBuild() {
    val key = "k".repeat(SettingsSyncCatalog.MAX_KEY)
    assertThrows(IllegalStateException::class.java) { SettingsSyncCatalog(listOf(domain("test", level(key)))) }
    SettingsSyncCatalog(listOf(domain("t", level("k".repeat(SettingsSyncCatalog.MAX_KEY - 2)))))
  }

  @Test fun theHouseholdCatalogBuildsAndExcludesEveryNeverSyncKey() {
    val keys = SettingsSyncCatalog.household.keys
    assertTrue(keys.isNotEmpty())
    assertTrue(keys.none { it in SettingsSyncCatalog.NEVER_SYNC })
    assertTrue(keys.all { it.length <= SettingsSyncCatalog.MAX_KEY })
  }

  @Test fun intsAcceptEveryExactWholeNumberType() {
    assertEquals(3, catalog.validate("test.level", 3.toShort()))
    assertEquals(-3, catalog.validate("test.level", (-3).toByte()))
    assertEquals(4, catalog.validate("test.level", 4.0f))
    assertNull(catalog.validate("test.level", 4.5f))
    assertNull(catalog.validate("test.level", Double.NaN))
    assertNull(catalog.validate("test.level", Double.POSITIVE_INFINITY))
    assertNull(catalog.validate("test.level", 1e12))
    assertNull(catalog.validate("test.level", -1e12))
    assertNull(catalog.validate("test.level", Int.MIN_VALUE.toLong() - 1))
    assertNull(catalog.validate("test.level", "3"))
    assertNull(catalog.validate("test.level", null))
    assertEquals(-10, catalog.validate("test.level", -10))
    assertEquals(10, catalog.validate("test.level", 10L))
    assertEquals("wellFormed ignores range", Int.MAX_VALUE, catalog.wellFormed("test.level", Int.MAX_VALUE.toDouble()))
  }

  @Test fun enumMustSurviveItsOwnCoercerUnchanged() {
    assertEquals("neon", catalog.validate("test.face", "neon"))
    assertNull("coerced to something else", catalog.validate("test.face", "old"))
    assertNull(catalog.validate("test.face", 5))
    assertNull(catalog.validate("test.face", "x".repeat(SettingsSyncCatalog.MAX_STRING + 1)))
    assertEquals("old", catalog.wellFormed("test.face", "old"))
    assertNull(catalog.wellFormed("test.face", 5))
    assertNull(catalog.wellFormed("test.face", "x".repeat(SettingsSyncCatalog.MAX_STRING + 1)))
  }

  @Test fun applySkipsUnknownKeysAndDomainsWithNothingValid() {
    assertEquals(emptySet<String>(), catalog.apply(context, mapOf("nope.x" to 1, "test.level" to 99, "test.face" to "old")))
    assertTrue(writes.isEmpty())
    assertEquals(emptySet<String>(), catalog.apply(context, emptyMap()))
  }

  @Test fun applyWritesAcrossDomains() {
    val two = SettingsSyncCatalog(listOf(domain("a", level()), domain("b", strictFace)))
    assertEquals(setOf("a.level", "b.face"), two.apply(context, mapOf("a.level" to 2, "b.face" to "neon")))
    assertEquals(mapOf<String, Any>("level" to 2, "face" to "neon"), writes)
  }

  @Test fun defaultsSkipDomainsWithoutADefaultsFactory() {
    val two = SettingsSyncCatalog(listOf(domain("a", level()), domain("b", strictFace, defaults = null)))
    assertEquals(mapOf<String, Any>("a.level" to 5), two.defaults())
  }

  @Test fun readDropsValuesThatAreNotWellFormed() {
    val weird = SettingsSyncCatalog(listOf(domain("w", strictFace)))
    val bad = SettingsDomain(id = "w", title = "T", load = { Snap(face = "a\u0000b") }, specs = listOf(strictFace))
    assertEquals(mapOf<String, Any>("w.face" to "classic"), weird.read(context))
    assertEquals(emptyMap<String, Any>(), SettingsSyncCatalog(listOf(bad)).read(context))
  }

  @Test fun siblingKeysOfUnknownKeysAreEmpty() {
    assertTrue(catalog.siblingKeys(emptyList()).isEmpty())
    assertEquals(setOf("test.level", "test.face"), catalog.siblingKeys(listOf("test.level", "nope")))
  }
}

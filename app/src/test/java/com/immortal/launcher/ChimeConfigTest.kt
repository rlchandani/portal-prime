package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Unit tests for [ChimeConfig] — focused on the volume-consolidation migration (four legacy per-cue
 * volumes → one shared `volume`) and the pure quiet-hours window math. Uses a map-backed fake
 * [SharedPreferences] so the real [ChimeConfig.load] path is exercised end-to-end without Robolectric.
 */
class ChimeConfigTest {

  /** Minimal read-only SharedPreferences backed by a map (only the getters ChimeConfig.load uses). */
  private class FakePrefs(private val values: Map<String, Any?>) : SharedPreferences {
    override fun contains(key: String) = values.containsKey(key)
    override fun getInt(key: String, defValue: Int) = (values[key] as? Int) ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = (values[key] as? Boolean) ?: defValue
    override fun getString(key: String, defValue: String?) = (values[key] as? String) ?: defValue
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getLong(key: String, defValue: Long) = (values[key] as? Long) ?: defValue
    override fun getFloat(key: String, defValue: Float) = (values[key] as? Float) ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
  }

  private fun ctxWith(values: Map<String, Any?>): Context {
    val ctx = mock(Context::class.java)
    `when`(ctx.getSharedPreferences(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(FakePrefs(values))
    return ctx
  }

  // ---- volume migration (4 legacy per-cue keys → one shared `volume`) --------------------------

  @Test
  fun volume_defaultsTo70_whenNothingSet() {
    assertEquals(70, ChimeConfig.load(ctxWith(emptyMap())).volume)
  }

  @Test
  fun volume_newKeyWins_overLegacy() {
    val v = ChimeConfig.load(ctxWith(mapOf(
        "volume" to 42,
        "chime_volume" to 10, "spoken_volume" to 20, // legacy present but must be ignored
    ))).volume
    assertEquals(42, v)
  }

  @Test
  fun volume_averagesWhicheverLegacyKeysAreSet() {
    // Only two legacy keys set → average of those two (not diluted by unset ones).
    val v = ChimeConfig.load(ctxWith(mapOf(
        "chime_volume" to 40, "ping_volume" to 80,
    ))).volume
    assertEquals(60, v) // (40 + 80) / 2
  }

  @Test
  fun volume_averagesAllFourLegacyKeys() {
    val v = ChimeConfig.load(ctxWith(mapOf(
        "chime_volume" to 60, "spoken_volume" to 70, "golden_volume" to 60, "ping_volume" to 90,
    ))).volume
    assertEquals(70, v) // (60+70+60+90)/4 = 70
  }

  @Test
  fun volume_clampsLegacyValuesIntoRange() {
    // Out-of-range legacy values are coerced to 0..100 before averaging.
    val v = ChimeConfig.load(ctxWith(mapOf(
        "chime_volume" to 200, "spoken_volume" to -50,
    ))).volume
    assertEquals(50, v) // clamp(200)=100, clamp(-50)=0 → avg 50
  }

  // ---- quiet-hours window (pure) ---------------------------------------------------------------

  @Test
  fun inQuietWindow_sameStartEnd_isNeverQuiet() {
    assertFalse(ChimeConfig.inQuietWindow(nowMin = 600, startMin = 480, endMin = 480))
  }

  @Test
  fun inQuietWindow_nonWrapping_window() {
    // 09:00–17:00
    assertTrue(ChimeConfig.inQuietWindow(nowMin = 600, startMin = 540, endMin = 1020))  // 10:00 inside
    assertFalse(ChimeConfig.inQuietWindow(nowMin = 1080, startMin = 540, endMin = 1020)) // 18:00 outside
    assertTrue(ChimeConfig.inQuietWindow(nowMin = 540, startMin = 540, endMin = 1020))   // start inclusive
    assertFalse(ChimeConfig.inQuietWindow(nowMin = 1020, startMin = 540, endMin = 1020)) // end exclusive
  }

  @Test
  fun inQuietWindow_wrappingPastMidnight() {
    // 22:00–08:00 (default)
    assertTrue(ChimeConfig.inQuietWindow(nowMin = 23 * 60, startMin = 22 * 60, endMin = 8 * 60)) // 23:00
    assertTrue(ChimeConfig.inQuietWindow(nowMin = 3 * 60, startMin = 22 * 60, endMin = 8 * 60))  // 03:00
    assertFalse(ChimeConfig.inQuietWindow(nowMin = 12 * 60, startMin = 22 * 60, endMin = 8 * 60)) // noon
    assertFalse(ChimeConfig.inQuietWindow(nowMin = 8 * 60, startMin = 22 * 60, endMin = 8 * 60))  // end exclusive
  }
}

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.settings.StringSpec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class MusicConnectionSettingsTest {
  private val keys = setOf("multiRoomEnabled", "snapcastHost", "maPort", "maUsername",
      "maPassword", "maApiToken", "maPlayerId")

  @Test fun newFieldsDefaultToBlankWithoutChangingExistingConnectionDefaults() {
    val prefs = MusicConnectionTestPrefs()
    val loaded = ImmortalSettings.load(prefs.context)
    assertEquals(ImmortalSettings.Settings(), loaded)
    assertFalse(loaded.multiRoomEnabled)
    assertEquals("", loaded.snapcastHost)
    assertEquals(8095, loaded.maPort)
    assertEquals("", ImmortalSettings.maApiToken(prefs.context))
    assertEquals("", ImmortalSettings.maPlayerId(prefs.context))
    assertEquals(0, prefs.writeCount)
  }

  @Test fun connectionSettersAndSnapshotPreserveExactTokenAndRawPlayerIdentity() {
    val prefs = MusicConnectionTestPrefs(mapOf("ma_username" to "existing-user",
        "ma_password" to "existing-password", "ma_port" to 9095))
    ImmortalSettings.setMaApiToken(prefs.context, "test-token._~+/=-Case")
    ImmortalSettings.setMaPlayerId(prefs.context, "snapcast:Case-Sensitive/Raw_ID")
    val loaded = ImmortalSettings.load(prefs.context)
    assertEquals("test-token._~+/=-Case", loaded.maApiToken)
    assertEquals(loaded.maApiToken, ImmortalSettings.maApiToken(prefs.context))
    assertEquals("snapcast:Case-Sensitive/Raw_ID", loaded.maPlayerId)
    assertEquals(loaded.maPlayerId, ImmortalSettings.maPlayerId(prefs.context))
    assertEquals("existing-user", loaded.maUsername)
    assertEquals("existing-password", loaded.maPassword)
    assertEquals(9095, loaded.maPort)
    assertEquals(2, prefs.writeCount)
  }

  @Test fun connectionObserverIncludesNewAndLegacyFieldsAndIgnoresPresentation() {
    val prefs = MusicConnectionTestPrefs()
    var callbacks = 0
    val stop = ImmortalSettings.observeMusicConnection(prefs.context) { callbacks++ }
    listOf("multiroom_enabled", "snapcast_host", "ma_port", "ma_username", "ma_password",
        "ma_api_token", "ma_player_id").forEach(prefs::notify)
    assertEquals(7, callbacks)
    listOf("weather_unit", "settings_theme", "advanced_settings", "show_mini_player")
        .forEach(prefs::notify)
    assertEquals(7, callbacks)
    prefs.notify(null)
    assertEquals(8, callbacks)
    val queued = prefs.listeners.single()
    stop(); stop()
    queued.onSharedPreferenceChanged(prefs, "ma_api_token")
    assertEquals(8, callbacks)
    assertTrue(prefs.listeners.isEmpty())
    assertEquals(1, prefs.unregisterCount)
    assertEquals(0, prefs.writeCount)
  }

  @Test fun nativeConnectionSpecPinAndCompletenessIncludeBothNewFields() {
    val audio = SettingsDomains.immortal.specs.filter { SettingsDomains.immortal.sections[it.key] == "Audio" }
    assertEquals(keys, audio.map { it.key }.toSet())
    assertEquals(keys.size, audio.size)
    assertTrue(audio.filter { it.key in setOf("maApiToken", "maPlayerId") }.all { it is StringSpec })
  }

  @Test fun genericRemoteSchemaMasksTokenAndKeepsRawPlayerId() {
    val prefs = MusicConnectionTestPrefs(mapOf("multiroom_enabled" to true,
        "ma_api_token" to "test-token-never-in-schema", "ma_password" to "test-legacy-secret",
        "ma_player_id" to "snapcast:Raw_ID"))
    val schema = SettingsDomains.immortal.schemaJson(prefs.context)
    val array = schema.getJSONArray("controls")
    val controls = (0 until array.length()).map(array::getJSONObject).associateBy { it.getString("key") }
    val token = controls.getValue("maApiToken")
    assertEquals("", token.getString("value"))
    assertEquals("", token.getString("default"))
    assertTrue(token.getBoolean("secret"))
    assertTrue(token.getBoolean("hasValue"))
    assertEquals("advanced", token.getString("tier"))
    assertFalse(schema.toString().contains("test-token-never-in-schema"))
    assertFalse(schema.toString().contains("test-legacy-secret"))
    val player = controls.getValue("maPlayerId")
    assertFalse(player.getBoolean("secret"))
    assertEquals("snapcast:Raw_ID", player.getString("value"))
    assertEquals("", player.getString("default"))
    assertEquals("advanced", player.getString("tier"))
    assertTrue((keys - setOf("maUsername", "maPassword")).all { it in controls })
    assertFalse("maUsername" in controls)
    assertFalse("maPassword" in controls)
    assertEquals("test-legacy-secret", ImmortalSettings.load(prefs.context).maPassword)
    assertEquals(0, prefs.writeCount)
  }

  @Test fun gatedMusicFieldsPreserveSavedValuesWhenTheFeatureIsOff() {
    val prefs = MusicConnectionTestPrefs(mapOf("ma_api_token" to "saved-test-token",
        "ma_player_id" to "snapcast:saved-player"))
    val schema = SettingsDomains.immortal.schemaJson(prefs.context)
    val controls = schema.getJSONArray("controls")
    val visible = (0 until controls.length()).map { controls.getJSONObject(it).getString("key") }
    assertFalse("maApiToken" in visible)
    assertFalse("maPlayerId" in visible)
    assertEquals("saved-test-token", ImmortalSettings.maApiToken(prefs.context))
    assertEquals("snapcast:saved-player", ImmortalSettings.maPlayerId(prefs.context))
    assertEquals(0, prefs.writeCount)
  }

  @Test fun tokenRejectsWrongTypesControlCharactersWhitespaceAndOverlongInput() {
    val prefs = MusicConnectionTestPrefs(mapOf("ma_api_token" to "saved-test-token"))
    val invalid = listOf(7, true, JSONObject.NULL, JSONObject(), JSONArray(), "", "   ",
        "token with space", "token\t", "token\n", "token\r", "token\u0000", "token\u007f",
        "token\u0085", "t".repeat(4097))
    invalid.forEach { value ->
      assertTrue(SettingsDomains.immortal.applyValues(prefs.context,
          JSONObject().put("maApiToken", value)).isEmpty())
    }
    assertEquals("saved-test-token", ImmortalSettings.maApiToken(prefs.context))
    assertEquals(0, prefs.writeCount)
    val longest = "t".repeat(4096)
    assertEquals(setOf("maApiToken"), SettingsDomains.immortal.applyValues(prefs.context,
        JSONObject().put("maApiToken", longest)))
    assertEquals(longest, ImmortalSettings.maApiToken(prefs.context))
  }

  @Test fun playerIdAcceptsBlankAndPrintableRawIdsButRejectsControlsAndOverflow() {
    val prefs = MusicConnectionTestPrefs(mapOf("ma_player_id" to "snapcast:saved-player"))
    listOf(7, false, JSONObject.NULL, JSONObject(), JSONArray(), "   ", "id\n", "id\t",
        "id\u0000", "id\u007f", "id\u0085", "p".repeat(257)).forEach { value ->
      assertTrue(SettingsDomains.immortal.applyValues(prefs.context,
          JSONObject().put("maPlayerId", value)).isEmpty())
    }
    assertEquals("snapcast:saved-player", ImmortalSettings.maPlayerId(prefs.context))
    assertEquals(0, prefs.writeCount)
    listOf("p".repeat(256), "snapcast:Case-Sensitive/Raw_ID", "").forEach { value ->
      assertEquals(setOf("maPlayerId"), SettingsDomains.immortal.applyValues(prefs.context,
          JSONObject().put("maPlayerId", value)))
      assertEquals(value, ImmortalSettings.maPlayerId(prefs.context))
    }
  }

  @Test fun blankSecretsAreOmittedWhileAnExplicitPlayerClearApplies() {
    val prefs = MusicConnectionTestPrefs(mapOf("ma_api_token" to "saved-test-token",
        "ma_password" to "saved-test-password", "ma_player_id" to "snapcast:player"))
    assertEquals(setOf("maPlayerId"), SettingsDomains.immortal.applyValues(prefs.context,
        JSONObject().put("maApiToken", "").put("maPassword", " ").put("maPlayerId", "")))
    assertEquals("saved-test-token", ImmortalSettings.maApiToken(prefs.context))
    assertEquals("saved-test-password", ImmortalSettings.maPass(prefs.context))
    assertEquals("", ImmortalSettings.maPlayerId(prefs.context))
    assertEquals(1, prefs.writeCount)
  }

  @Test fun aConnectionBatchSyncsTheServiceOnceAfterAllRegisteredWrites() {
    val prefs = MusicConnectionTestPrefs()
    mockConstruction(Intent::class.java).use {
      val applied = SettingsDomains.immortal.apply(prefs.context, JSONObject()
          .put("multiRoomEnabled", true).put("snapcastHost", "server.test").put("maPort", 9095)
          .put("maUsername", "test-user").put("maPassword", "test-password")
          .put("maApiToken", "test-api-token").put("maPlayerId", "snapcast:raw-player"))
      assertEquals(keys, applied)
      verify(prefs.context, times(1)).startService(any(Intent::class.java))
      assertEquals("test-api-token", ImmortalSettings.maApiToken(prefs.context))
      assertEquals("snapcast:raw-player", ImmortalSettings.maPlayerId(prefs.context))
      assertEquals(7, prefs.writeCount)
    }
  }

  @Test fun eachNewConnectionFieldAloneTriggersOneServiceSync() {
    listOf("maApiToken" to "test-token", "maPlayerId" to "snapcast:raw-player").forEach { (key, value) ->
      val prefs = MusicConnectionTestPrefs()
      mockConstruction(Intent::class.java).use {
        assertEquals(setOf(key), SettingsDomains.immortal.apply(prefs.context, JSONObject().put(key, value)))
        // Disabled configurations stop the relay; neither write should enable it as a side effect.
        verify(prefs.context, times(1)).stopService(any(Intent::class.java))
        assertFalse(ImmortalSettings.multiRoomEnabled(prefs.context))
        assertEquals(1, prefs.writeCount)
      }
    }
  }

  @Test fun rejectedOrUnrelatedEditsNeverSyncTheMusicService() {
    val prefs = MusicConnectionTestPrefs()
    mockConstruction(Intent::class.java).use {
      assertTrue(SettingsDomains.immortal.apply(prefs.context,
          JSONObject().put("maApiToken", " ").put("maPlayerId", "id\n").put("maPort", 65536)).isEmpty())
      assertEquals(setOf("weatherUnit"), SettingsDomains.immortal.apply(prefs.context,
          JSONObject().put("weatherUnit", "c")))
      verify(prefs.context, never()).startService(any(Intent::class.java))
      verify(prefs.context, never()).startForegroundService(any(Intent::class.java))
      verify(prefs.context, never()).stopService(any(Intent::class.java))
      assertEquals(1, prefs.writeCount)
    }
  }
}

/** Real config storage semantics without Android files, credentials, network or service execution. */
private class MusicConnectionTestPrefs(initial: Map<String, Any?> = emptyMap()) : SharedPreferences {
  private val values = initial.toMutableMap()
  val listeners = linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
  var writeCount = 0
    private set
  var unregisterCount = 0
    private set
  val context: Context = mock(Context::class.java).also {
    `when`(it.getSharedPreferences("immortal_settings", Context.MODE_PRIVATE)).thenReturn(this)
  }
  fun notify(key: String?) = listeners.toList().forEach { it.onSharedPreferenceChanged(this, key) }
  override fun contains(key: String) = values.containsKey(key)
  override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
  override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
  override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
  override fun getAll(): MutableMap<String, *> = values.toMutableMap()
  override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
  override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
  @Suppress("UNCHECKED_CAST")
  override fun getStringSet(key: String, defValues: MutableSet<String>?) =
      (values[key] as? Set<String>)?.toMutableSet() ?: defValues
  override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {
    l?.let(listeners::add)
  }
  override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {
    l?.let(listeners::remove)
    unregisterCount++
  }
  override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
    private val pending = mutableMapOf<String, Any?>()
    private var clear = false
    override fun putString(key: String, value: String?) = apply { pending[key] = value }
    override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values?.toSet() }
    override fun putInt(key: String, value: Int) = apply { pending[key] = value }
    override fun putLong(key: String, value: Long) = apply { pending[key] = value }
    override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
    override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
    override fun remove(key: String) = apply { pending[key] = null }
    override fun clear() = apply { clear = true }
    override fun commit(): Boolean { apply(); return true }
    override fun apply() {
      if (clear) values.clear()
      val changed = pending.filter { (key, value) -> values[key] != value }.keys
      pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
      writeCount++
      changed.forEach(this@MusicConnectionTestPrefs::notify)
    }
  }
}

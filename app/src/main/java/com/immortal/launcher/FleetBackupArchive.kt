/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * The plaintext inside a sealed fleet backup, and the rules for what an import may apply. Pure JVM.
 *
 * An archive holds this Portal's preference files (typed values, not raw XML), a short allowlist of
 * data files, and its household-scoped settings as the settings sync catalog reads them. Parsing is
 * strict: an unknown field, a wrong type, a name outside the allowlist or a value over a cap rejects
 * the whole archive before anything is written.
 *
 * Scope on import follows settings sync. Restoring onto the Portal that made the archive applies
 * everything in it. A `crossDevice` import (required on any other Portal) takes only the household
 * settings, each checked by [com.immortal.launcher.settings.SettingsSyncCatalog.validate]; preference files and
 * data files are device-local or secret (room name, phone sessions, the household key, tokens) and
 * are skipped. Who's there templates are never in an archive at all: they are sealed under a
 * non-exportable Keystore key that a reinstall destroys, so they would only add exposure.
 */
internal object FleetBackupArchive {
  const val FORMAT = "hearthpane-backup"
  const val VERSION = 1
  const val MAX_PLAIN_BYTES = 8 * 1024 * 1024
  const val MAX_PREF_FILES = 160
  const val MAX_KEYS_PER_FILE = 4096
  const val MAX_KEY_CHARS = 512
  const val MAX_STRING_CHARS = 1024 * 1024
  const val MAX_SET_ITEMS = 4096
  const val MAX_DATA_FILE_BYTES = 2 * 1024 * 1024
  const val MAX_HOUSEHOLD_KEYS = 2048

  /** Preference files that never leave or enter through a backup. */
  val NEVER_PREFS: Set<String> = setOf(
      "fleet_agent", // the fleet token and port: a restore keeps the current provisioning
      FleetBackupKeyStore.PREFS, // the enrolled backup key itself
      "immortal_http_cache", "crash_guard", "hearthpane_ota", // caches and runtime state
      "immortal_generated_bridge_write_probe", "screensaver_test_source", // verification hooks
  )

  /** Data files an archive may carry, relative to the app's data directory. All device-local. */
  val DATA_FILES: List<String> = listOf(
      "files/household_chores.json", "files/household_reminders.json", "files/household_list.json",
      "no_backup/remote-companion.json",
  )

  private val PREF_NAME = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,95}")
  private val DEVICE_ID = Regex("[0-9a-f]{32}")
  private val TOP_LEVEL = setOf("format", "version", "device", "createdAt", "app", "prefs", "files", "household")

  class ArchiveException(val code: String) : Exception(code)

  sealed class PrefValue {
    data class Str(val v: String) : PrefValue()
    data class IntV(val v: Int) : PrefValue()
    data class LongV(val v: Long) : PrefValue()
    data class FloatV(val v: Float) : PrefValue()
    data class Bool(val v: Boolean) : PrefValue()
    data class StrSet(val v: Set<String>) : PrefValue()
  }

  class Archive(
      val device: String,
      val createdAt: Long,
      val versionCode: Long,
      val versionName: String,
      val prefs: Map<String, Map<String, PrefValue>>,
      val files: Map<String, ByteArray>,
      val household: Map<String, Any>,
  )

  /** What an import will write, and what it leaves out (names only, never values). */
  class Plan(
      val prefs: Map<String, Map<String, PrefValue>>,
      val files: Map<String, ByteArray>,
      val household: Map<String, Any>,
      val skipped: List<String>,
  ) {
    fun summary(): JSONObject = JSONObject()
        .put("prefFiles", prefs.size).put("prefValues", prefs.values.sumOf { it.size })
        .put("dataFiles", JSONArray(files.keys.sorted())).put("householdSettings", household.size)
        .put("skipped", JSONArray(skipped))
  }

  fun isExportablePref(name: String): Boolean = PREF_NAME.matches(name) && name !in NEVER_PREFS

  /** A preference value as the archive types it, or null for a type preferences can't hold. */
  fun prefValue(raw: Any?): PrefValue? = when (raw) {
    is String -> PrefValue.Str(raw)
    is Int -> PrefValue.IntV(raw)
    is Long -> PrefValue.LongV(raw)
    is Float -> PrefValue.FloatV(raw)
    is Boolean -> PrefValue.Bool(raw)
    is Set<*> -> if (raw.all { it is String }) PrefValue.StrSet(raw.mapTo(LinkedHashSet()) { it as String }) else null
    else -> null
  }

  fun encode(archive: Archive): ByteArray {
    val prefs = JSONObject()
    archive.prefs.toSortedMap().forEach { (name, values) ->
      val file = JSONObject()
      values.toSortedMap().forEach { (key, value) -> file.put(key, encodeValue(value)) }
      prefs.put(name, file)
    }
    val files = JSONObject()
    archive.files.toSortedMap().forEach { (path, bytes) -> files.put(path, Base64.getEncoder().encodeToString(bytes)) }
    val household = JSONObject()
    archive.household.toSortedMap().forEach { (key, value) -> household.put(key, value) }
    return JSONObject().put("format", FORMAT).put("version", VERSION).put("device", archive.device)
        .put("createdAt", archive.createdAt)
        .put("app", JSONObject().put("versionCode", archive.versionCode).put("versionName", archive.versionName))
        .put("prefs", prefs).put("files", files).put("household", household)
        .toString().toByteArray(Charsets.UTF_8)
  }

  /**
   * Parses and validates [bytes]. [wellFormed] is the settings sync catalog's shape check: a
   * household key it doesn't know (e.g. from a newer build) is dropped, not applied.
   */
  fun decode(bytes: ByteArray, wellFormed: (String, Any?) -> Any?): Archive {
    if (bytes.size > MAX_PLAIN_BYTES) throw ArchiveException("backup_too_large")
    val root = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: throw ArchiveException("bad_archive")
    if (root.keyNames().toSet() != TOP_LEVEL || root.opt("format") != FORMAT || root.opt("version") != VERSION) {
      throw ArchiveException("bad_archive")
    }
    val device = (root.opt("device") as? String)?.takeIf(DEVICE_ID::matches) ?: throw ArchiveException("bad_archive")
    val createdAt = exactLong(root.opt("createdAt"))?.takeIf { it >= 0 } ?: throw ArchiveException("bad_archive")
    val app = root.opt("app") as? JSONObject ?: throw ArchiveException("bad_archive")
    val versionCode = exactLong(app.opt("versionCode")) ?: throw ArchiveException("bad_archive")
    val versionName = (app.opt("versionName") as? String)?.takeIf { it.length <= 64 } ?: throw ArchiveException("bad_archive")
    return Archive(device, createdAt, versionCode, versionName, decodePrefs(root.opt("prefs")),
        decodeFiles(root.opt("files")), decodeHousehold(root.opt("household"), wellFormed))
  }

  /**
   * The import plan for this archive on a Portal whose device id is [here]. Without [crossDevice]
   * the archive must come from this Portal and everything in it applies. [operatorConfirmed] is
   * the Mac vouching, from the adb serial it provisioned, that this is the same Portal although
   * its app identity changed (a reinstall with another signing key gives the app a new
   * `ANDROID_ID`). With [crossDevice] only the household settings apply, on any Portal.
   */
  fun plan(archive: Archive, here: String, crossDevice: Boolean, operatorConfirmed: Boolean = false): Plan {
    if (!crossDevice) {
      if (archive.device != here && !operatorConfirmed) throw ArchiveException("different_device")
      return Plan(archive.prefs, archive.files, emptyMap(), listOf("household settings (already in the preference files)"))
    }
    val skipped = listOf(
        "${archive.prefs.size} preference files (device-local and secret: room name, sessions, household key, tokens)",
        "${archive.files.size} data files (device-local)")
    return Plan(emptyMap(), emptyMap(), archive.household, skipped)
  }

  private fun encodeValue(value: PrefValue): JSONArray = when (value) {
    is PrefValue.Str -> JSONArray().put("s").put(value.v)
    is PrefValue.IntV -> JSONArray().put("i").put(value.v)
    is PrefValue.LongV -> JSONArray().put("l").put(value.v)
    // As text, so the float survives JSON's double round trip bit for bit.
    is PrefValue.FloatV -> JSONArray().put("f").put(value.v.toString())
    is PrefValue.Bool -> JSONArray().put("b").put(value.v)
    is PrefValue.StrSet -> JSONArray().put("ss").put(JSONArray(value.v.sorted()))
  }

  private fun decodePrefs(raw: Any?): Map<String, Map<String, PrefValue>> {
    val prefs = raw as? JSONObject ?: throw ArchiveException("bad_archive")
    if (prefs.length() > MAX_PREF_FILES) throw ArchiveException("backup_too_large")
    val out = LinkedHashMap<String, Map<String, PrefValue>>()
    for (name in prefs.keyNames().sorted()) {
      if (!isExportablePref(name)) throw ArchiveException("bad_pref_file")
      val file = prefs.opt(name) as? JSONObject ?: throw ArchiveException("bad_archive")
      if (file.length() > MAX_KEYS_PER_FILE) throw ArchiveException("backup_too_large")
      val values = LinkedHashMap<String, PrefValue>()
      for (key in file.keyNames().sorted()) {
        if (key.isEmpty() || key.length > MAX_KEY_CHARS) throw ArchiveException("bad_pref_value")
        values[key] = decodeValue(file.opt(key))
      }
      out[name] = values
    }
    return out
  }

  private fun decodeValue(raw: Any?): PrefValue {
    val pair = raw as? JSONArray ?: throw ArchiveException("bad_pref_value")
    if (pair.length() != 2) throw ArchiveException("bad_pref_value")
    val v = pair.opt(1)
    return when (pair.opt(0)) {
      "s" -> PrefValue.Str(string(v))
      "i" -> PrefValue.IntV(exactLong(v)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt() ?: throw ArchiveException("bad_pref_value"))
      "l" -> PrefValue.LongV(exactLong(v) ?: throw ArchiveException("bad_pref_value"))
      "f" -> PrefValue.FloatV((v as? String)?.takeIf { it.length <= 64 }?.toFloatOrNull() ?: throw ArchiveException("bad_pref_value"))
      "b" -> PrefValue.Bool(v as? Boolean ?: throw ArchiveException("bad_pref_value"))
      "ss" -> {
        val items = v as? JSONArray ?: throw ArchiveException("bad_pref_value")
        if (items.length() > MAX_SET_ITEMS) throw ArchiveException("backup_too_large")
        val set = LinkedHashSet<String>()
        for (i in 0 until items.length()) if (!set.add(string(items.opt(i)))) throw ArchiveException("bad_pref_value")
        PrefValue.StrSet(set)
      }
      else -> throw ArchiveException("bad_pref_value")
    }
  }

  private fun decodeFiles(raw: Any?): Map<String, ByteArray> {
    val files = raw as? JSONObject ?: throw ArchiveException("bad_archive")
    val out = LinkedHashMap<String, ByteArray>()
    for (path in files.keyNames().sorted()) {
      if (path !in DATA_FILES) throw ArchiveException("bad_data_file")
      val text = files.opt(path) as? String ?: throw ArchiveException("bad_data_file")
      if (text.length > (MAX_DATA_FILE_BYTES / 3 + 1) * 4) throw ArchiveException("backup_too_large")
      val bytes = runCatching { Base64.getDecoder().decode(text) }.getOrNull() ?: throw ArchiveException("bad_data_file")
      if (bytes.size > MAX_DATA_FILE_BYTES) throw ArchiveException("backup_too_large")
      out[path] = bytes
    }
    return out
  }

  private fun decodeHousehold(raw: Any?, wellFormed: (String, Any?) -> Any?): Map<String, Any> {
    val household = raw as? JSONObject ?: throw ArchiveException("bad_archive")
    if (household.length() > MAX_HOUSEHOLD_KEYS) throw ArchiveException("backup_too_large")
    val out = LinkedHashMap<String, Any>()
    for (key in household.keyNames().sorted()) wellFormed(key, household.opt(key))?.let { out[key] = it }
    return out
  }

  private fun JSONObject.keyNames(): List<String> = keys().asSequence().toList()

  private fun string(raw: Any?): String =
      (raw as? String)?.takeIf { it.length <= MAX_STRING_CHARS } ?: throw ArchiveException("bad_pref_value")

  private fun exactLong(raw: Any?): Long? = when (raw) {
    is Int -> raw.toLong()
    is Long -> raw
    else -> null
  }
}

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/** Thirty public list fixtures and one private, RAM-only secure-settings fixture. */
internal object BarWatchServiceListDeviceChecks {
  private const val OWN = "public.immortal/public.immortal.BarWatchService"
  private const val A = "public.alpha/.Reader"
  private const val B = "public.beta/.Reader"
  private data class Fixture(val label: String, val current: String, val on: Boolean,
      val expected: String, val repeat: Boolean = false)

  // The baseline removes only the production preservation shortcut.
  private fun old(current: String, component: String, on: Boolean): String {
    val foreign = current.split(':').filter { it.isNotBlank() && !it.equals(component, true) }
    return (if (on) foreign + component else foreign).joinToString(":")
  }

  fun run(context: Context): Bundle {
    val lower = OWN.lowercase()
    val upper = OWN.uppercase()
    val fixtures = listOf(
        Fixture("enabled_front", "$OWN:$A:$B", true, "$OWN:$A:$B"),
        Fixture("enabled_middle", "$A:$OWN:$B", true, "$A:$OWN:$B"),
        Fixture("enabled_end", "$A:$B:$OWN", true, "$A:$B:$OWN"),
        Fixture("front_raw_colons", ":$OWN::$A:$B:", true, ":$OWN::$A:$B:"),
        Fixture("middle_raw_colons", "::$A:$OWN:::$B:", true, "::$A:$OWN:::$B:"),
        Fixture("end_raw_colons", ":$A::$B:$OWN::", true, ":$A::$B:$OWN::"),
        Fixture("canonical_only_raw", "::$OWN::", true, "::$OWN::"),
        Fixture("canonical_blank_entries", " \t:$OWN: :$A:\t", true, " \t:$OWN: :$A:\t"),
        Fixture("missing_empty", "", true, OWN),
        Fixture("missing_blanks", ": :\t::", true, OWN),
        Fixture("missing_append", "$B:$A", true, "$B:$A:$OWN"),
        Fixture("missing_normalizes_blanks", ":$A:: :$B:", true, "$A:$B:$OWN"),
        Fixture("off_front", "$OWN:$A:$B", false, "$A:$B"),
        Fixture("off_middle", "$A:$OWN:$B", false, "$A:$B"),
        Fixture("off_end", "$A:$B:$OWN", false, "$A:$B"),
        Fixture("off_all_own_cases", "$OWN:$A:$upper:$B:$lower", false, "$A:$B"),
        Fixture("off_only_own", "$OWN:$upper", false, ""),
        Fixture("off_missing_foreign_duplicates", ":$B::$A:$A:", false, "$B:$A:$A"),
        Fixture("repair_canonical_duplicates", "$OWN:$A:$OWN:$B", true, "$A:$B:$OWN"),
        Fixture("repair_uppercase_front", "$upper:$A:$B", true, "$A:$B:$OWN"),
        Fixture("repair_lowercase_middle", "$A:$lower:$B", true, "$A:$B:$OWN"),
        Fixture("repair_wrongcase_end", "$A:$B:$upper", true, "$A:$B:$OWN"),
        Fixture("repair_mixed_duplicates", "$upper:$B:$OWN:$A:$lower", true, "$B:$A:$OWN"),
        Fixture("repair_with_blank_entries", ":$OWN::$A:$upper::", true, "$A:$OWN"),
        Fixture("foreign_near_match_and_duplicates", "$OWN.extra:$A:$A", true, "$OWN.extra:$A:$A:$OWN"),
        Fixture("foreign_spaces_preserved", " $OWN : $A :$B", true, " $OWN : $A :$B:$OWN"),
        Fixture("repeat_front", "$OWN:$B:$A", true, "$OWN:$B:$A", true),
        Fixture("repeat_middle_raw", ":$B::$OWN:$A:", true, ":$B::$OWN:$A:", true),
        Fixture("repeat_repair", "$upper:$B:$OWN:$A", true, "$B:$A:$OWN", true),
        Fixture("repeat_off", ":$A:$OWN:$upper:$B:", false, "$A:$B", true),
    )
    val checks = JSONArray()
    var unexpectedExceptionCount = 0
    var passedCount = 0
    fun record(label: String, passed: Boolean) {
      checks.put(JSONObject().put("label", label).put("passed", passed))
      if (passed) passedCount++
    }
    val baselinePublicFailureCount = fixtures.count { old(it.current, OWN, it.on) != it.expected }
    for (fixture in fixtures) {
      var passed = false
      try {
        val updated = BarWatchServiceList.updated(fixture.current, OWN, fixture.on)
        val baseline = old(fixture.current, OWN, fixture.on)
        passed = updated == fixture.expected &&
            ((updated == baseline) == (fixture.expected == baseline))
        if (fixture.repeat) {
          var repeated = updated
          repeat(3) { repeated = BarWatchServiceList.updated(repeated, OWN, fixture.on) }
          passed = passed && repeated == fixture.expected
        }
      } catch (_: Throwable) {
        unexpectedExceptionCount++
        passed = false
      }
      record(fixture.label, passed)
    }

    var originalList: String? = null
    var originalMaster: String? = null
    var originalsRead = false
    var restoreRequired = false
    var restored = false
    var reordered = false
    var realPassed = false
    var preconditionsMet = false
    val resolver = context.contentResolver
    val listKey = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    val masterKey = Settings.Secure.ACCESSIBILITY_ENABLED
    try {
      originalList = Settings.Secure.getString(resolver, listKey)
      originalMaster = Settings.Secure.getString(resolver, masterKey)
      originalsRead = true
      val component = ComponentName(context, BarWatchService::class.java).flattenToString()
      val entries = (originalList ?: "").split(':').filter { it.isNotBlank() }
      val own = entries.filter { it.equals(component, true) }
      val foreign = entries.filterNot { it.equals(component, true) }
      preconditionsMet = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
          PackageManager.PERMISSION_GRANTED && originalMaster == "1" &&
          own.size == 1 && own.single() == component && foreign.isNotEmpty()
      if (preconditionsMet) {
        val prepared = (listOf(component) + foreign).joinToString(":")
        val atEnd = (foreign + component).joinToString(":")
        restoreRequired = true // Includes a write throwing after changing the setting.
        val wrote = Settings.Secure.putString(resolver, listKey, prepared)
        val preparedRead = Settings.Secure.getString(resolver, listKey)
        reordered = preparedRead != originalList
        val preparationValid = wrote && preparedRead == prepared && prepared != atEnd &&
            prepared.split(':').sorted() == entries.sorted() &&
            Settings.Secure.getString(resolver, masterKey) == "1"
        if (preparationValid) {
          SettingsGuard.setBarWatchEnabled(context, true)
          realPassed = Settings.Secure.getString(resolver, listKey) == prepared &&
              Settings.Secure.getString(resolver, masterKey) == "1"
        }
      }
    } catch (_: Throwable) { unexpectedExceptionCount++ }
    finally {
      // Both attempts run even if either fails. Private values/errors never enter the report.
      var listRestored = false
      var masterRestored = false
      if (originalsRead) {
        try {
          val wrote = !restoreRequired || Settings.Secure.putString(resolver, listKey, originalList)
          listRestored = wrote && Settings.Secure.getString(resolver, listKey) == originalList
        } catch (_: Throwable) { unexpectedExceptionCount++ }
        try {
          val wrote = !restoreRequired || Settings.Secure.putString(resolver, masterKey, originalMaster)
          masterRestored = wrote && Settings.Secure.getString(resolver, masterKey) == originalMaster
        } catch (_: Throwable) { unexpectedExceptionCount++ }
      }
      restored = originalsRead && listRestored && masterRestored
    }
    record("real_setter_preserves_front_and_restores", realPassed && restored)
    val failureCount = checks.length() - passedCount
    val completed = checks.length() == 31
    val passed = completed && failureCount == 0 && unexpectedExceptionCount == 0 && restored
    val report = JSONObject().put("completed", completed).put("passed", passed)
        .put("checkCount", checks.length()).put("passedCount", passedCount)
        .put("failureCount", failureCount).put("unexpectedExceptionCount", unexpectedExceptionCount)
        .put("fixturesPublic", false).put("networkUsed", false).put("stateMutations", true)
        .put("originalSecureValuesRestored", restored).put("requiredRestore", restored)
        .put("deviceSecureSettingReordered", reordered).put("realFixturePreconditionsMet", preconditionsMet)
        .put("expectedBaselinePublicFailureCount", baselinePublicFailureCount)
        .put("expectedBaselineFailureCount", baselinePublicFailureCount + 1).put("checks", checks)
    return Bundle().apply {
      putBoolean("barWatchServiceListCompleted", completed)
      putBoolean("barWatchServiceListPassed", passed)
      putString("barWatchServiceListJson", report.toString())
    }
  }
}

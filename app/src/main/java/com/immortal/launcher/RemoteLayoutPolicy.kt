/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class RemoteCompanionValidation(val errors: List<String>) {
  val valid: Boolean get() = errors.isEmpty()
}
internal fun JSONObject.fieldKeys(): Set<String> = keys().asSequence().toSet()
internal fun JSONObject.only(vararg keys: String): Boolean = fieldKeys().all { it in keys }
internal fun Any?.integer(): Long? = when (this) { is Int -> toLong(); is Long -> this; else -> null }
internal fun Any?.text(max: Int = 256): Boolean = this is String && length <= max && none { it.code < 32 }

/** Strict wire preflight; native codecs deliberately tolerate and repair old on-device data. */
internal object RemoteLayoutPolicy {
  val kinds = setOf("weather", "world_clock", "timers", "calendar", "alarm", "crypto", "todo", "news", "stocks", "options", "ha", "today", "quick_controls", "photo_memory", "routines", "wifi")
  private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
  fun packageName(value: Any?): Boolean = value is String && value.length <= 200 && packagePattern.matches(value)

  /** Preserve the native flattened ComponentName, including owned shortcut components. */
  fun homeId(value: Any?): Boolean {
    if (packageName(value)) return true // compatibility with older package-only exports
    if (value !is String || value.length > 400 || value.count { it == '/' } != 1) return false
    val (pkg, cls) = value.split('/')
    return packageName(pkg) && Regex("\\.?[A-Za-z_$][A-Za-z0-9_.$]*").matches(cls)
  }

  fun widgets(layout: JSONObject): List<HomeWidgetStore.HomeWidget> =
      HomeWidgetStore.deserialize(layout.getJSONArray("widgets").toString()).map {
        // Native old-data parsing defaults a missing app-widget ID to -1, even for custom kinds.
        if (it.isAppWidget) it else it.copy(appWidgetId = 0)
      }

  fun validate(layout: JSONObject, existing: List<HomeWidgetStore.HomeWidget>): RemoteCompanionValidation {
    val errors = mutableListOf<String>()
    if (!layout.only("assignments", "homeOrder", "gridOrder", "gridSlots", "widgets")) errors += "unknown_layout_field"
    val assignments = layout.optJSONObject("assignments")
    if (assignments == null || assignments.length() > 256) errors += "assignments_required"
    else assignments.fieldKeys().forEach { if (!packageName(it) || !assignments.opt(it).text(48)) errors += "invalid_assignment" }
    val widgets = layout.optJSONArray("widgets")
    val widgetKeys = mutableSetOf<String>()
    if (widgets == null || widgets.length() > 64) errors += "widgets_required"
    else for (i in 0 until widgets.length()) {
      val w = widgets.optJSONObject(i)
      if (w == null) { errors += "invalid_widget"; continue }
      if (!w.only("id", "package", "class", "spanX", "spanY", "kind", "customId", "explicitSize", "page", "column", "row")) errors += "unknown_widget_field"
      if (w.opt("spanX").integer() !in 1L..4L || w.opt("spanY").integer() !in 1L..4L) errors += "invalid_span"
      // Free-placement slot: all three or none, within the 2-row × 4-column grid.
      val slotFields = listOf("page", "column", "row").count { w.has(it) }
      if (slotFields != 0 && (slotFields != 3 || w.opt("page").integer() !in 0L..63L ||
          w.opt("column").integer() !in 0L..3L || w.opt("row").integer() !in 0L..1L)) errors += "invalid_slot"
      if (w.has("explicitSize") && w.opt("explicitSize") !is Boolean) errors += "invalid_explicitSize"
      val kind = w.opt("kind") as? String
      val key = if (kind == "appwidget") {
        val id = w.opt("id").integer()
        val bound = existing.firstOrNull { it.isAppWidget && it.appWidgetId.toLong() == id }
        if (bound == null || w.opt("package") != bound.providerPackage || w.opt("class") != bound.providerClass || w.optString("customId").isNotEmpty()) errors += "unbound_widget"
        "widget:$id"
      } else {
        val id = w.opt("customId")
        if (kind !in kinds || id !is String || !id.text(120) || id.isBlank() || !id.startsWith("$kind:") || (w.has("id") && w.opt("id").integer() != 0L) || w.optString("package").isNotEmpty() || w.optString("class").isNotEmpty()) errors += "invalid_custom_widget"
        "custom:$id"
      }
      if (!widgetKeys.add(key)) errors += "duplicate_widget"
    }
    fun validTile(value: String): Boolean = when {
      value.startsWith("app:") -> homeId(value.removePrefix("app:"))
      value.startsWith("folder:") -> value.removePrefix("folder:").let { it.isNotBlank() && it.text(48) }
      value.startsWith("widget-tile:") -> value.removePrefix("widget-tile:") in widgetKeys
      else -> value in setOf("builtin:calls", "builtin:store", "builtin:tools", "builtin:music", "builtin:settings", "builtin:dashboard")
    }
    for (field in listOf("homeOrder", "gridOrder", "gridSlots")) {
      val arr = layout.optJSONArray(field)
      if (arr == null || arr.length() > 512) { errors += "${field}_required"; continue }
      val seen = mutableSetOf<String>()
      for (i in 0 until arr.length()) {
        val v = arr.opt(i)
        if (field == "gridSlots" && (v == JSONObject.NULL || v == "")) continue
        if (v !is String || !(if (field == "homeOrder") homeId(v) else validTile(v))) errors += "invalid_$field"
        else if (!seen.add(v)) errors += "duplicate_$field"
      }
    }
    return RemoteCompanionValidation(errors.distinct())
  }
}

internal object RemoteLayoutStore {
  fun read(c: Context): JSONObject = JSONObject()
      .put("assignments", JSONObject(UserLayout.load(c)))
      .put("homeOrder", JSONArray(UserLayout.loadOrder(c)))
      .put("gridOrder", JSONArray(UserLayout.loadGridOrder(c)))
      .put("gridSlots", JSONArray(UserLayout.loadGridSlots(c).map { it ?: JSONObject.NULL }))
      .put("widgets", JSONArray(HomeWidgetStore.serialize(HomeWidgetStore.load(c))))

  /** Caller holds the companion mutation lock and has preflighted the whole candidate. */
  fun apply(c: Context, layout: JSONObject) {
    val assignments = layout.getJSONObject("assignments")
    UserLayout.save(c, assignments.fieldKeys().associateWith { assignments.getString(it) })
    fun order(key: String) = layout.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } }
    UserLayout.saveOrder(c, order("homeOrder"))
    UserLayout.saveGridOrder(c, order("gridOrder"))
    val slots = layout.getJSONArray("gridSlots")
    UserLayout.saveGridSlots(c, (0 until slots.length()).map { if (slots.isNull(it) || slots.optString(it).isBlank()) null else slots.getString(it) })
    HomeWidgetStore.save(c, RemoteLayoutPolicy.widgets(layout))
  }
}

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.json.JSONArray
import org.json.JSONObject

/** Explicit, authenticated functional probes. No polling, UI strings, pixels or preferences. */
internal object HomeUiDiagnostics {
  const val MAX_NODES = 256
  const val MAX_DEPTH = 32
  const val LONG_PRESS_MS = 800L
  private val main by lazy { Handler(Looper.getMainLooper()) }

  internal data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun visible(width: Int, height: Int) = right > left && bottom > top &&
        right > 0 && bottom > 0 && left < width && top < height
    fun contains(other: Bounds) = left <= other.left && top <= other.top &&
        right >= other.right && bottom >= other.bottom
    fun json() = JSONArray(listOf(left, top, right, bottom))
  }

  // Fixed public Settings titles/row descriptions, shell labels and dialog choices.
  // These public selectors do not export stored values or grant Home input eligibility.
  private val settingsLabels = mapOf(
      "Settings" to "settingsTitle", "App appearance" to "appAppearance",
      "Appearance" to "appearance", "immortal.settingsTheme" to "settingsTheme",
      "App tile size" to "tileSize", "immortal.tileSize" to "tileSize",
      "Clock" to "clockFormat", "immortal.clockFormat" to "clockFormat",
      "Now-playing mini-player" to "showMiniPlayer", "immortal.showMiniPlayer" to "showMiniPlayer",
      "Hide status bar" to "hideStatusBar", "immortal.hideStatusBar" to "hideStatusBar",
      "Display" to "display", "Wallpaper" to "wallpaper",
      "Home screen" to "homeScreen", "Advanced options" to "advancedOptions",
      "Standard" to "choiceStandard", "Large" to "choiceLarge", "XL" to "choiceXL",
      "Auto" to "choiceAuto", "12-hour" to "choice12", "24-hour" to "choice24",
      "Light" to "choiceLight", "Dark" to "choiceDark",
      "Close dialog" to "menuDismiss", "Show advanced options" to "advancedHint",
      "Advanced settings" to "advancedTitle",
      "Turn on Advanced settings" to "consentEnable", "Cancel" to "consentCancel",
      // Static category and embedded page titles from ApprovedSettingsNavigation. Shared
      // titles use one key; their separate nodes/bounds identify the sidebar and detail pane.
      "Photos & screensaver" to "photosScreensaver", "Home & widgets" to "homeWidgets",
      "Sound & wake" to "soundWake", "Connections" to "connections",
      "Input & accessibility" to "inputAccessibility", "Device" to "device", "Advanced" to "advanced",
      "Photo clock faces" to "photoClockFaces", "Digital clock" to "digitalClock",
      "Photo sources" to "photoSources", "Weather" to "weather", "Calendar" to "calendar",
      "Alarm" to "alarm", "Quick buttons" to "quickButtons", "Touch sounds" to "touchSounds",
      "Sounds" to "sounds", "Welcome greeting" to "welcomeGreeting", "Sunrise & wake" to "sunriseWake",
      "Home dashboard" to "homeDashboard", "Intercom" to "intercom", "Device name" to "deviceName",
      "Sleep timer" to "sleepTimer", "About & device health" to "aboutDeviceHealth",
      "Android & Portal settings" to "androidPortalSettings",
  )
  private val settingsKeys = settingsLabels.values.toSet()

  // Only these fixed source labels can become response keys. Never serialize a node label.
  private val labels = mapOf(
      "Home" to "home", "Apps" to "apps", "Tools" to "tools",
      "Search apps and settings" to "searchButton",
      "Search installed apps" to "searchInstalledApps", "Search apps" to "searchApps",
      "Switch to day theme" to "themeDayAction", "Switch to night theme" to "themeNightAction",
      "Open" to "menuOpen", "App info" to "menuAppInfo", "Move to folder" to "menuMove",
      "Add to favorites" to "menuAddFavorite", "Remove from favorites" to "menuRemoveFavorite",
      "Close (free up memory)" to "menuCloseApp", "Close app" to "menuCloseApp",
      "Uninstall" to "menuUninstall", "Close" to "menuDismiss",
      "Browser" to "browser", "Chromium" to "chromium", "Jarvis" to "jarvis",
      "Your apps" to "appsHeading", "Installed apps on this Portal." to "appsSubtitle",
      "Small things that make the day easier." to "toolsSubtitle",
  ) + settingsLabels
  private val fixedKeys = labels.values.toSet()
  internal fun fixedKey(value: CharSequence?): String? {
    if (value == null || value.length > 80) return null
    return labels.entries.firstOrNull { (label, _) -> label.contentEquals(value) }?.value
  }

  internal data class Node(
      val parent: Int, val bounds: Bounds, val key: String?, val clickable: Boolean,
      val longClickable: Boolean, val focused: Boolean, val accessibilityFocused: Boolean,
      val editable: Boolean, val password: Boolean, val editText: Boolean,
      val searchHint: String?, val textLength: Int?,
      val id: Int = -1,
  )
  /** Compose can expose a label as a non-clickable child of its actual input row. IDs are
   * traversal IDs, not positions in the visible-node list. Unknown and editable nodes remain
   * intact so normalization cannot hide fields from the search or app-menu admission guards.
   */
  private fun normalizedControls(nodes: List<Node>): List<Node> {
    val byId = nodes.filter { it.id >= 0 }.groupBy { it.id }
        .mapNotNull { (id, matches) -> matches.singleOrNull()?.let { id to it } }.toMap()
    fun resolve(label: Node): Node {
      if (label.clickable || label.editable || label.password || label.key !in fixedKeys || label.id < 0)
        return label
      var parent = label.parent
      val seen = hashSetOf(label.id)
      repeat(3) {
        if (!seen.add(parent)) return label
        val ancestor = byId[parent] ?: return label
        if (ancestor.editable || ancestor.password || !ancestor.bounds.contains(label.bounds) ||
            (ancestor.key != null && ancestor.key != label.key)) return label
        if (ancestor.clickable) return ancestor.copy(key = label.key, searchHint = null, textLength = null)
        parent = ancestor.parent
      }
      return label
    }
    val seenControls = HashSet<Pair<Int, String>>()
    return nodes.map(::resolve).filter { node ->
      // A merged row and its unmerged label can describe the same control. Retain it once;
      // never deduplicate unknown nodes or fields used by admission checks.
      val key = node.key
      if (node.id >= 0 && key != null && key in fixedKeys && !node.editable && !node.password)
        seenControls.add(node.id to key) else true
    }
  }

  internal data class State(
      val width: Int, val height: Int, val visited: Int, val truncated: Boolean,
      val nodes: List<Node>,
  ) {
    private val controls = normalizedControls(nodes)
    fun dockPresent(): Boolean {
      val home = controls.filter { it.key == "home" && it.clickable && !it.editable }
      val apps = controls.filter { it.key == "apps" && it.clickable && !it.editable }
      val tools = controls.filter { it.key == "tools" && it.clickable && !it.editable }
      return home.any { h -> apps.any { a -> tools.any { t ->
        h.parent == a.parent && a.parent == t.parent &&
            h.bounds.top >= height / 2 &&
            maxOf(h.bounds.top, a.bounds.top, t.bounds.top) <
                minOf(h.bounds.bottom, a.bounds.bottom, t.bounds.bottom) &&
            h.bounds.right <= a.bounds.left && a.bounds.right <= t.bounds.left
      } } }
    }
    fun appMenuPresent(): Boolean {
      val keys = controls.filter { it.clickable && !it.editable }.mapNotNull { it.key }.toSet()
      return controls.none { it.editable } && keys.containsAll(setOf("menuOpen", "menuAppInfo", "menuMove")) &&
          ("menuAddFavorite" in keys || "menuRemoveFavorite" in keys)
    }
    fun eligible(): Boolean = !truncated && (dockPresent() || appMenuPresent())
    fun publicLongPressTarget(x: Double, y: Double): Boolean = eligible() && dockPresent() &&
        validPoint(x, y, width, height) && controls.any { node ->
          node.key in setOf("browser", "chromium", "jarvis") && node.clickable && node.longClickable &&
              !node.editable && !node.password && x >= node.bounds.left && x < node.bounds.right &&
              y >= node.bounds.top && y < node.bounds.bottom
        }
    fun appsPageVisible(): Boolean = eligible() && dockPresent() &&
        controls.any { it.key == "appsHeading" } && controls.any { it.key == "appsSubtitle" }
    fun toolsLibraryVisible(): Boolean = eligible() && dockPresent() &&
        controls.any { it.key == "tools" && !it.clickable } && controls.any { it.key == "toolsSubtitle" }

    // Compose's placeholder disappears after typing. The fixed Apps heading/subtitle and its
    // single EditText identify the same source-defined search field without reading its query.
    // Other Activities, dialogs, folder editors and Home widgets cannot satisfy this signature.
    fun searchNode(): Pair<Node, String>? {
      if (!eligible() || !dockPresent()) return null
      val edits = controls.filter { it.editable }
      val field = edits.singleOrNull()?.takeIf { !it.password && it.editText } ?: return null
      field.searchHint?.takeIf { it == "searchApps" || it == "searchInstalledApps" }
          ?.let { return field to it }
      val heading = controls.singleOrNull { it.key == "appsHeading" } ?: return null
      val subtitle = controls.singleOrNull { it.key == "appsSubtitle" } ?: return null
      val dockTop = controls.filter { it.key in setOf("home", "apps", "tools") && it.clickable }
          .minOfOrNull { it.bounds.top } ?: return null
      return if (field.bounds.top >= maxOf(heading.bounds.bottom, subtitle.bounds.bottom) &&
          field.bounds.bottom <= dockTop) field to "searchInstalledApps" else null
    }
    /** Failed admission explains only fixed navigation labels. Credentials, search fields and
     * all other node identities stay redacted; this evidence never participates in admission.
     */
    private fun ineligibleDockEvidence(): JSONObject {
      val keys = listOf("home", "apps", "tools")
      val candidates = controls.filter { it.key in keys && !it.editable && !it.password }
      val labelCounts = JSONObject()
      val clickableCounts = JSONObject()
      keys.forEach { key ->
        labelCounts.put(key, candidates.count { it.key == key })
        clickableCounts.put(key, candidates.count { it.key == key && it.clickable })
      }
      val rows = JSONArray()
      candidates.forEach { node ->
        rows.put(JSONObject().put("key", node.key).put("parent", node.parent)
            .put("bounds", node.bounds.json()).put("clickable", node.clickable))
      }
      return JSONObject().put("labelCounts", labelCounts).put("clickableCounts", clickableCounts)
          .put("nodeLimitReached", visited >= MAX_NODES).put("candidates", rows)
    }
    fun json(): JSONObject {
      val dock = dockPresent()
      val menu = appMenuPresent()
      val eligible = eligible()
      val search = searchNode()
      val rows = JSONArray()
      controls.forEach { node ->
        val searchKey = search?.takeIf { it.first === node }?.second
        if (searchKey != null) {
          rows.put(row(node, searchKey).put("inputTextLength", node.textLength ?: 0))
        } else {
          val key = node.key
          if (!node.editable && !node.password && key != null && key in fixedKeys &&
              (eligible || (!truncated && key in settingsKeys)) &&
              key !in setOf("appsHeading", "appsSubtitle", "toolsSubtitle", "searchInstalledApps", "searchApps"))
            rows.put(row(node, key))
        }
      }
      return JSONObject().put("ok", true).put("eligible", eligible)
          .put("dockPresent", dock).put("appMenuPresent", menu).put("truncated", truncated)
          .put("appsPageVisible", appsPageVisible()).put("toolsLibraryVisible", toolsLibraryVisible())
          .put("visitedNodes", visited).put("screenWidth", width).put("screenHeight", height)
          .put("focusedEditable", eligible && controls.any { it.focused && it.editable && !it.password })
          .put("nodes", rows)
          .also { if (!eligible) it.put("ineligibleDockEvidence", ineligibleDockEvidence()) }
    }
    private fun row(node: Node, key: String) = JSONObject().put("key", key)
        .put("bounds", node.bounds.json()).put("clickable", node.clickable)
        .put("longClickable", node.longClickable).put("focused", node.focused)
        .put("accessibilityFocused", node.accessibilityFocused).put("editable", node.editable)
  }

  internal data class Read(val state: State? = null, val error: String? = null)
  internal fun validPoint(x: Double, y: Double, width: Int, height: Int): Boolean =
      width > 0 && height > 0 && x.isFinite() && y.isFinite() &&
          x >= 0 && y >= 0 && x < width && y < height &&
          x.toFloat().toDouble() < width && y.toFloat().toDouble() < height

  internal fun collectorBusy(): Boolean {
    val state = FramePerformanceCapture.stateSnapshot()
    return state.optBoolean("active", true) || state.optBoolean("collectorRunning", true)
  }
  private fun guard(context: Context): FleetHttpServer.Response? = when {
    !BuildConfig.PORTAL_NATIVE_HOME_TABS && !BuildConfig.PORTAL_HOME_UI_DIAGNOSTICS ->
        error(403, "native_home_disabled")
    !DevMode.isEnabled(context) -> error(403, "dev_mode_required")
    collectorBusy() -> error(409, "home_ui_perf_busy")
    else -> null
  }
  internal fun error(status: Int, code: String) = FleetHttpServer.Response(status,
      JSONObject().put("ok", false).put("error", code).toString())

  fun handle(context: Context, req: FleetHttpServer.Request): FleetHttpServer.Response {
    if (req.method != "GET" && req.method != "POST") return error(405, "method_not_allowed")
    guard(context)?.let { return it }
    var point: Pair<Double, Double>? = null
    if (req.method == "POST") {
      val body = runCatching { JSONObject(req.bodyText()) }.getOrNull()
          ?: return error(400, "invalid_home_ui_command")
      val keys = body.keys().asSequence().toSet()
      val x = body.opt("x") as? Number
      val y = body.opt("y") as? Number
      if (keys != setOf("action", "x", "y") || body.opt("action") != "longPress" ||
          x == null || y == null || !x.toDouble().isFinite() || !y.toDouble().isFinite())
        return error(400, "invalid_home_ui_command")
      point = x.toDouble() to y.toDouble()
    }
    val selectedPoint = point
    val deadline = SystemClock.elapsedRealtime() + 2_000L
    val gate = FramePerformanceCommandGate()
    val task = FutureTask {
      if (!gate.begin()) error(503, "home_ui_cancelled")
      else if (SystemClock.elapsedRealtime() >= deadline) error(503, "home_ui_main_timeout") else {
        guard(context) ?: if (selectedPoint == null) {
          val read = RemoteInput.homeUiSnapshot(context)
          read.state?.let { FleetHttpServer.Response(200, it.json().toString()) }
              ?: error(409, read.error ?: "home_ui_unavailable")
        } else RemoteInput.homeUiLongPress(context, selectedPoint.first, selectedPoint.second, deadline)
      }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) return error(503, "home_ui_worker_required")
    if (!main.post(task)) return error(503, "home_ui_main_unavailable")
    return try { task.get(2, TimeUnit.SECONDS) }
    catch (_: TimeoutException) { cancelled(gate, task, req.method == "POST") }
    catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      cancelled(gate, task, req.method == "POST")
    } catch (_: Exception) { error(503, "home_ui_read_failed") }
  }
  private fun cancelled(gate: FramePerformanceCommandGate,
      task: FutureTask<FleetHttpServer.Response>, action: Boolean): FleetHttpServer.Response {
    if (gate.cancelBeforeBegin()) task.cancel(false)
    else if (action) return FleetHttpServer.Response(202,
        """{"ok":false,"result":"completion_unconfirmed","checkStateBeforeRetry":true}""")
    return error(503, "home_ui_main_timeout")
  }
}

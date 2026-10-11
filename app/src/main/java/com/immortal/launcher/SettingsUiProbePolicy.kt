/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.ceil
import kotlin.math.floor

internal data class SettingsUiProbeRoute(val category: String, val page: String?, val control: String?) {
  val scope: String get() = "$category/${page ?: "overview"}"
  val geometryScope: String get() = scope + control?.let { "#$it" }.orEmpty()
}

internal data class SettingsUiProbeBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
  companion object {
    fun fromWindow(left: Float, top: Float, right: Float, bottom: Float,
        screenOffsetX: Float, screenOffsetY: Float): SettingsUiProbeBounds? {
      if (listOf(left, top, right, bottom, screenOffsetX, screenOffsetY).any { !it.isFinite() } ||
          right <= left || bottom <= top) return null
      return SettingsUiProbeBounds(floor(left + screenOffsetX).toInt(), floor(top + screenOffsetY).toInt(),
          ceil(right + screenOffsetX).toInt(), ceil(bottom + screenOffsetY).toInt())
    }
  }
  fun clipped(width: Int, height: Int): SettingsUiProbeBounds? {
    val clipped = SettingsUiProbeBounds(left.coerceIn(0, width), top.coerceIn(0, height),
        right.coerceIn(0, width), bottom.coerceIn(0, height))
    return clipped.takeIf { it.right > it.left && it.bottom > it.top }
  }
}

internal data class SettingsUiProbeTarget(
    val kind: String, val id: String, val scope: String?, val queryToken: Any?,
    val bounds: SettingsUiProbeBounds, val owner: Any? = null,
)

internal data class SettingsUiProbeSnapshot(
    val route: SettingsUiProbeRoute, val drawnScope: String, val queryLength: Int,
    val results: List<SettingsUiProbeRoute>, val targets: List<SettingsUiProbeTarget>,
)

/** Main-owned test data: fixed route identifiers and geometry, never labels, values or queries. */
internal class SettingsUiProbeStore(
    private val categories: Set<String>,
    private val pages: Set<String>,
    private val controls: Set<String>,
) {
  companion object { const val MAX_TARGETS = 96 }
  private var route: SettingsUiProbeRoute? = null
  private val drawnFrames = LinkedHashMap<SettingsUiProbeRoute, Any>()
  private var queryToken: Any? = null
  private var queryLength = 0
  private var results = emptyList<SettingsUiProbeRoute>()
  private val targets = ArrayList<SettingsUiProbeTarget>()
  val storedTargetCount: Int get() = targets.size

  private fun valid(route: SettingsUiProbeRoute) = route.category in categories &&
      (route.page == null || route.page in pages) &&
      (route.control == null || route.control in controls)

  private fun validScope(scope: String?): Boolean {
    if (scope == null) return false
    val frame = scope.split('#')
    if (frame.size > 2 || (frame.size == 2 && frame[1] !in controls)) return false
    val parts = frame[0].split('/')
    return parts.size == 2 && parts[0] in categories &&
        (parts[1] == "overview" || parts[1] in pages)
  }

  fun publish(next: SettingsUiProbeRoute, token: Any, length: Int,
      searchResults: List<SettingsUiProbeRoute>): Boolean {
    if (!valid(next) || length !in 0..4096 || searchResults.size > 12 ||
        searchResults.any { !valid(it) }) {
      // Failed publication cannot leave an old route/query reported as current and ready.
      route = null
      queryToken = null
      queryLength = 0
      results = emptyList()
      return false
    }
    route = next
    queryToken = token
    queryLength = length
    results = searchResults.toList()
    return true
  }

  fun markDrawn(drawn: SettingsUiProbeRoute, owner: Any) {
    if (drawn == route) {
      if (drawn !in drawnFrames && drawnFrames.size == MAX_TARGETS)
        drawnFrames.remove(drawnFrames.keys.first())
      drawnFrames[drawn] = owner
    }
  }

  fun retireDraw(drawn: SettingsUiProbeRoute, owner: Any) {
    if (drawnFrames[drawn] === owner) drawnFrames.remove(drawn)
  }

  fun removeTarget(kind: String, id: String, scope: String?, token: Any?, owner: Any?) {
    targets.removeAll {
      it.kind == kind && it.id == id && it.scope == scope && it.queryToken === token && it.owner === owner
    }
  }

  fun target(kind: String, id: String, scope: String?, token: Any?,
      bounds: SettingsUiProbeBounds, owner: Any? = null): Boolean {
    val allowed = when (kind) {
      "category" -> id in categories && scope == null && token == null
      "nav" -> id in pages && validScope(scope) && token == null
      "home" -> id == "home" && scope == null && token == null
      "search" -> id == "input" && scope == null && token == null
      "result" -> id.toIntOrNull()?.let { it in 0..11 && id == it.toString() } == true &&
          scope == null && token != null
      else -> false
    }
    if (!allowed) return false
    val old = targets.indexOfFirst {
      it.kind == kind && it.id == id && it.scope == scope && it.queryToken === token
    }
    // A formerly visible node may have scrolled away. Remove its old rectangle atomically.
    if (bounds.right <= bounds.left || bounds.bottom <= bounds.top) {
      if (old >= 0 && targets[old].owner === owner) targets.removeAt(old)
      return false
    }
    if (old >= 0) targets.removeAt(old)
    if (targets.size == MAX_TARGETS) targets.removeAt(0)
    targets.add(SettingsUiProbeTarget(kind, id, scope, token, bounds, owner))
    return true
  }

  fun snapshot(width: Int, height: Int, requested: SettingsUiProbeRoute): SettingsUiProbeSnapshot? {
    val committed = route ?: return null
    if (width <= 0 || height <= 0 || committed != requested || committed !in drawnFrames) return null
    val visible = targets.mapNotNull { target ->
      // A search Popup covers ordinary rows. Its underlying rectangles are not touch targets.
      if (queryLength > 0 && target.kind != "search" && target.kind != "result") return@mapNotNull null
      if (target.kind == "nav" && target.scope != committed.geometryScope) return@mapNotNull null
      if (target.kind == "result" && (queryLength == 0 || target.queryToken !== queryToken ||
          target.id.toInt() !in results.indices)) return@mapNotNull null
      target.bounds.clipped(width, height)?.let { target.copy(bounds = it) }
    }
    return SettingsUiProbeSnapshot(committed, committed.scope, queryLength, results.toList(), visible)
  }

  fun clear() {
    route = null
    drawnFrames.clear()
    queryToken = null
    queryLength = 0
    results = emptyList()
    targets.clear()
  }
}

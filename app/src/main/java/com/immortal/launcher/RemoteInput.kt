/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicLong

/**
 * The bridge from the fleet agent's HTTP thread ([RemoteRoutes]) to the accessibility
 * layer, so a phone "remote" can drive the Portal. The Portal is non-root Android 9/10
 * and we hold no `INJECT_EVENTS` (signature) permission, so raw D-pad/key events can't
 * be injected into other apps. Instead an enabled [AccessibilityService] gives us three
 * working surfaces with no extra permission:
 *  - **global actions** ([globalAction]) — Back / Home / Power.
 *  - **text entry** ([typeText]) — set the focused editable field's text directly.
 *
 * [BarWatchService] — a connected, general-purpose accessibility service (the remote
 * self-enables it, see [SettingsGuard.reconcileBarWatch]) — registers itself here on
 * connect (the same way it hosts [QuickBar]); the routes call into us. A no-op until a
 * service is registered, so a device with no enabled service reports "unavailable".
 *
 * Directional/pointer navigation is deliberately NOT done here: D-pad focus movement via
 * accessibility (`focusSearch` + `ACTION_FOCUS`) was tried and proved non-functional on the
 * Portal's Compose/custom UIs (no input-focus node to search from). It's delivered instead
 * by the gesture touchpad in a later phase — the right fit for a touchscreen. This object is
 * the single seam those gestures will extend.
 */
object RemoteInput {
  private const val TAG = "ImmortalRemote"

  private class Connection(val service: AccessibilityService) {
    @Volatile var gestures: Boolean? = null
  }
  @Volatile private var connection: Connection? = null
  private val service: AccessibilityService? get() = connection?.service
  private val capabilityReads = AtomicLong()
  private val cachedCapabilityReads = AtomicLong()
  private val capabilityFailures = AtomicLong()

  private val portalAmbientExit = PortalAmbientExitSession()
  private val portalAmbientHandler by lazy { Handler(Looper.getMainLooper()) }
  private val portalAmbientCallbackToken = Any()

  /** Meta's stopped DreamService can leave its retained Home view in ambient mode. */
  internal fun preparePortalHomeDismissal() {
    if (service == null) return
    val ticket = DreamPolicy.currentBridgeTicket()
    val now = SystemClock.uptimeMillis()
    portalAmbientExit.request(ticket, now)
    portalAmbientHandler.removeCallbacksAndMessages(portalAmbientCallbackToken)
    // Meta may publish its window before the ambient view is ready; bound this one user action.
    for (delay in listOf(300L, 800L, 1_500L, 2_500L)) {
      portalAmbientHandler.postAtTime({
        if (DreamPolicy.isCurrentBridge(ticket)) portalHomeWindowChanged()
      }, portalAmbientCallbackToken, now + delay)
    }
  }

  /** Only an explicit, current Calls handoff may act on Meta's known ambient surface. */
  internal fun portalHomeWindowChanged() {
    if (!portalAmbientExit.pending) return
    portalAmbientExit.attempt(
        DreamPolicy.currentBridgeTicket(),
        DreamPolicy.inStockHandoff && DreamPolicy.portalHomeHandoff,
        SystemClock.uptimeMillis(),
        ::clickPortalAmbientSurface,
    )
    if (!portalAmbientExit.pending) portalAmbientHandler.removeCallbacksAndMessages(portalAmbientCallbackToken)
  }

  private fun clickPortalAmbientSurface(): Boolean? {
    val svc = service ?: return null
    val root = runCatching { svc.rootInActiveWindow }.getOrNull() ?: return null
    try {
      if (root.packageName?.toString() != "com.facebook.alohaapps.launcher") return null
      // These views have Meta's ambient-to-active click listener; contact/feed rows are excluded.
      for (id in listOf("slideshow", "fullscreenClock")) {
        val nodes = root.findAccessibilityNodeInfosByViewId("com.facebook.alohaapps.launcher:id/$id")
        try {
          for (node in nodes) {
            if (!node.isVisibleToUser || !node.isClickable || !node.isEnabled) continue
            if (service !== svc) return null
            val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) Log.i(TAG, "Requested Meta ambient Home dismissal")
            return clicked
          }
        } finally {
          @Suppress("DEPRECATION")
          nodes.forEach { it.recycle() }
        }
      }
      return null
    } catch (_: Exception) {
      return null
    } finally {
      @Suppress("DEPRECATION")
      root.recycle()
    }
  }


  /** Called by [BarWatchService.onServiceConnected]. */
  fun register(svc: AccessibilityService) {
    connection = Connection(svc)
  }

  /** Called by [BarWatchService.onUnbind] when the service goes away. */
  fun unregister() {
    connection = null
  }

  /** True when an accessibility service is connected and can perform actions. */
  fun available(): Boolean = service != null

  /**
   * Perform a named global action. Returns true only if a service was connected AND
   * the platform accepted the action. Unknown names return false without touching the
   * service. Safe to call from any thread — [AccessibilityService.performGlobalAction]
   * is documented thread-safe.
   */
  fun globalAction(name: String): Boolean {
    val code = globalActionCode(name) ?: return false
    val svc = service ?: return false
    return runCatching { svc.performGlobalAction(code) }
        .onFailure { Log.w(TAG, "globalAction($name) failed", it) }
        .getOrDefault(false)
  }

  /**
   * Map a remote button name to an [AccessibilityService] `GLOBAL_ACTION_*` constant,
   * or null if unknown. Pure (extracted for unit testing).
   *
   * Only the actions that hit a real surface on the Portal are mapped. Verified on a
   * PortalGo: BACK / HOME / POWER_DIALOG work; RECENTS, NOTIFICATIONS and QUICK_SETTINGS
   * are accepted by the framework (performGlobalAction returns true) but no-op — Meta's
   * Portal SystemUI ships no overview, notification shade, or quick-settings panel. So we
   * don't expose them; "recents" is served instead by launching the in-app app switcher
   * ([AppSwitcherActivity], handled in [RemoteRoutes]).
   */
  internal fun globalActionCode(name: String): Int? =
      when (name.lowercase().trim()) {
        "back" -> AccessibilityService.GLOBAL_ACTION_BACK
        "home" -> AccessibilityService.GLOBAL_ACTION_HOME
        "power" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
        else -> null
      }

  /** The global-action button names [globalActionCode] accepts (for the UI / docs). The
   *  "apps" button is handled separately (in-app switcher), not as a global action. */
  internal val ACTIONS = listOf("back", "home", "power")

  /**
   * Fire the system Back action [times] times, [delayMs] apart, off the main thread. Backs the
   * Calls→stock-home bridge: a short burst of system Back presses is the reliable way back to
   * Meta's stock launcher on the Portal (where deep-link / HOME launches are flaky). No-op if no
   * accessibility service is connected.
   */
  fun backRepeat(times: Int, delayMs: Long) {
    if (service == null) return
    thread(name = "immortal-back") {
      repeat(times) { i ->
        globalAction("back")
        if (i < times - 1) runCatching { Thread.sleep(delayMs) }
      }
    }
  }

  // --- text entry -------------------------------------------------------------

  /**
   * Edit the currently focused editable field on the device. [mode] is "set" (replace
   * with [text]), "append", "backspace" (drop last char), or "clear". Returns true only
   * if a service is connected, an editable field has input focus, and the platform
   * accepted the edit. No IME swap needed — we set the node's text directly.
   */
  @Suppress("DEPRECATION") // Portal API 28/29 still pools accessibility nodes.
  fun typeText(text: String, mode: String): Boolean {
    val svc = service ?: return false
    val root = svc.rootInActiveWindow ?: return false
    var focused: AccessibilityNodeInfo? = null
    try {
      focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
      val node = focused ?: return false
      if (!node.isEditable) return false
      val next = nextText(node.text?.toString() ?: "", text, mode)
      val args =
          Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next)
          }
      return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
          .onFailure { Log.w(TAG, "typeText failed", it) }
          .getOrDefault(false)
    } finally {
      // findFocus can return root itself; release each object once, after its last use.
      try {
        if (focused !== root) focused?.recycle()
      } finally {
        root.recycle()
      }
    }
  }

  // --- private native Home functional diagnostics ----------------------------

  /** The service and pooled nodes remain private; only finite sanitized data escapes. */
  internal fun homeUiSnapshot(context: Context): HomeUiDiagnostics.Read {
    val svc = service ?: return HomeUiDiagnostics.Read(error = "home_ui_service_unavailable")
    return readHomeUi(svc, context)
  }

  internal fun homeUiLongPress(context: Context, x: Double, y: Double,
      deadline: Long): FleetHttpServer.Response {
    val svc = service ?: return HomeUiDiagnostics.error(409, "home_ui_service_unavailable")
    val read = readHomeUi(svc, context)
    val state = read.state ?: return HomeUiDiagnostics.error(409, read.error ?: "home_ui_unavailable")
    // A same-package settings/credential Activity or active dialog is not a Home touch target.
    if (state.truncated || !state.dockPresent()) return HomeUiDiagnostics.error(409, "home_ui_dock_required")
    if (!HomeUiDiagnostics.validPoint(x, y, state.width, state.height))
      return HomeUiDiagnostics.error(400, "home_ui_point_outside_screen")
    // Functional runs use the fixed public app row. A hold on an ordinary setting/control
    // could become its click on release; restrict this endpoint to actual app long-click nodes.
    if (!state.publicLongPressTarget(x, y)) return HomeUiDiagnostics.error(409, "home_ui_public_app_required")
    if (HomeUiDiagnostics.collectorBusy()) return HomeUiDiagnostics.error(409, "home_ui_perf_busy")
    if (service !== svc || !gesturesAvailable()) return HomeUiDiagnostics.error(409, "home_ui_gestures_unavailable")
    // A busy Main may finish a tree read after its caller timed out. Never send a late touch.
    if (SystemClock.elapsedRealtime() >= deadline) return HomeUiDiagnostics.error(503, "home_ui_main_timeout")
    val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
    val accepted = dispatch(svc, path, HomeUiDiagnostics.LONG_PRESS_MS)
    // Dispatch acceptance is not functional success. The operator must GET the resulting menu.
    return FleetHttpServer.Response(if (accepted) 200 else 503,
        org.json.JSONObject().put("ok", accepted).put("accepted", accepted)
            .put("durationMs", HomeUiDiagnostics.LONG_PRESS_MS).put("verifyWithGet", true).toString())
  }

  @Suppress("DEPRECATION") // Portal API 28/29 pools both nodes and windows.
  private fun readHomeUi(svc: AccessibilityService, context: Context): HomeUiDiagnostics.Read {
    val root = svc.rootInActiveWindow ?: return HomeUiDiagnostics.Read(error = "home_ui_no_active_root")
    var window: AccessibilityWindowInfo? = null
    try {
      if (root.packageName?.toString() != context.packageName)
        return HomeUiDiagnostics.Read(error = "home_ui_foreign_window")
      window = root.window
      if (window?.type != AccessibilityWindowInfo.TYPE_APPLICATION || window?.isActive != true)
        return HomeUiDiagnostics.Read(error = "home_ui_application_window_required")
      val dm = svc.resources.displayMetrics
      val width = dm.widthPixels
      val height = dm.heightPixels
      if (width <= 0 || height <= 0) return HomeUiDiagnostics.Read(error = "home_ui_screen_unavailable")
      // Query only this immutable public allowlist. No inventory refresh or labels are read.
      val installed = listOf("org.chromium.chrome", "com.android.chrome", "com.android.browser",
          "com.portal.assistant").filter { pkg ->
        runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess
      }.toSet()
      val allowedPublic = buildSet {
        if (installed.any { it != "com.portal.assistant" }) { add("browser"); add("chromium") }
        if ("com.portal.assistant" in installed) add("jarvis")
      }
      val nodes = ArrayList<HomeUiDiagnostics.Node>()
      var visited = 0
      var childReads = 0
      var truncated = false
      fun walk(node: AccessibilityNodeInfo, parent: Int, depth: Int) {
        if (visited >= HomeUiDiagnostics.MAX_NODES) { truncated = true; return }
        val index = visited++
        if (node.packageName?.toString() != context.packageName) return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val bounds = HomeUiDiagnostics.Bounds(rect.left, rect.top, rect.right, rect.bottom)
        val visible = node.isVisibleToUser && bounds.visible(width, height)
        val editable = node.isEditable
        val password = node.isPassword
        if (visible) {
          // Editable values never enter label matching. Only a fixed hint can match a search.
          val textKey = if (!editable && !password) HomeUiDiagnostics.fixedKey(node.text) else null
          val descriptionKey = if (!password) HomeUiDiagnostics.fixedKey(node.contentDescription) else null
          val candidate = textKey ?: descriptionKey
          val key = candidate?.takeUnless { it in setOf("browser", "chromium", "jarvis") && it !in allowedPublic }
          val hint = if (!password && Build.VERSION.SDK_INT >= 26)
            HomeUiDiagnostics.fixedKey(node.hintText) else null
          val searchHint = listOf(hint, descriptionKey).firstOrNull { it == "searchApps" || it == "searchInstalledApps" }
          nodes.add(HomeUiDiagnostics.Node(parent, bounds, key, node.isClickable, node.isLongClickable,
              node.isFocused, node.isAccessibilityFocused, editable, password,
              node.className?.toString() == "android.widget.EditText", searchHint,
              if (editable && !password) node.text?.length ?: 0 else null, id = index,
              systemToggleState = if (!editable && !password && node.isCheckable) node.isChecked else null))
        }
        val children = node.childCount
        if (depth >= HomeUiDiagnostics.MAX_DEPTH) { if (children > 0) truncated = true; return }
        for (i in 0 until children) {
          if (visited >= HomeUiDiagnostics.MAX_NODES || childReads >= HomeUiDiagnostics.MAX_NODES - 1) {
            truncated = true; break
          }
          childReads++
          val child = node.getChild(i) ?: continue
          try { walk(child, index, depth + 1) } finally { child.recycle() }
        }
      }
      walk(root, -1, 0)
      return HomeUiDiagnostics.Read(HomeUiDiagnostics.State(width, height, visited, truncated, nodes))
    } catch (_: Exception) {
      return HomeUiDiagnostics.Read(error = "home_ui_read_failed")
    } finally {
      try { window?.recycle() } finally { root.recycle() }
    }
  }

  // --- touchpad (gesture dispatch + on-TV cursor) -----------------------------
  // The reboot-proof, no-INJECT_EVENTS way to navigate any app: synthesize touches via
  // the accessibility service (needs canPerformGestures) and draw the pointer with
  // [RemoteCursor]. The cursor is tracked in screen pixels; the phone sends relative deltas.

  private val cursorLock = Any()
  private var curX = -1f
  private var curY = -1f

  /** True if the connected service was granted gesture dispatch (canPerformGestures). */
  fun gesturesAvailable(): Boolean {
    val current = connection ?: return false
    current.gestures?.let { known ->
      if (BuildConfig.PORTAL_HOME_UI_DIAGNOSTICS) cachedCapabilityReads.incrementAndGet()
      return connection === current && known
    }
    val supported = synchronized(current) {
      if (connection !== current) return@synchronized null
      current.gestures?.let { known ->
        if (BuildConfig.PORTAL_HOME_UI_DIAGNOSTICS) cachedCapabilityReads.incrementAndGet()
        return@synchronized known
      }
      // Capability is static metadata. Unknown/transient failures stay retryable.
      val info = try {
        if (BuildConfig.PORTAL_HOME_UI_DIAGNOSTICS) capabilityReads.incrementAndGet()
        current.service.serviceInfo
      } catch (_: Exception) {
        if (BuildConfig.PORTAL_HOME_UI_DIAGNOSTICS) capabilityFailures.incrementAndGet()
        null
      } ?: return@synchronized null
      val known = info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0
      if (connection === current) current.gestures = known
      known
    }
    // A replaced/unbound connection cannot authorize a later command.
    return connection === current && supported == true
  }

  /** Diagnostic-build aggregate counters and pointer coordinates; never reads serviceInfo. */
  internal fun gestureDiagnostics(): org.json.JSONObject {
    val current = connection
    val dm = current?.service?.resources?.displayMetrics
    val (x, y) = synchronized(cursorLock) { curX to curY }
    return org.json.JSONObject().put("connected", current != null)
        .put("capabilityKnown", current?.gestures != null)
        .put("gestureSupported", current?.gestures ?: org.json.JSONObject.NULL)
        .put("capabilityReads", capabilityReads.get()).put("cachedReads", cachedCapabilityReads.get())
        .put("capabilityFailures", capabilityFailures.get())
        .put("width", dm?.widthPixels ?: 0).put("height", dm?.heightPixels ?: 0)
        .put("cursorX", x).put("cursorY", y)
  }

  /** Move the on-TV pointer by a relative delta (px), clamped to the screen. */
  fun cursorMove(dx: Float, dy: Float): Boolean {
    val svc = service ?: return false
    val dm = svc.resources.displayMetrics
    synchronized(cursorLock) {
      initCursor(dm.widthPixels, dm.heightPixels)
      curX = (curX + dx).coerceIn(0f, dm.widthPixels - 1f)
      curY = (curY + dy).coerceIn(0f, dm.heightPixels - 1f)
      // Publish in the same order as state updates; showAt only queues a coalesced paint.
      RemoteCursor.showAt(curX.toInt(), curY.toInt())
    }
    return true
  }

  /** Tap at the current pointer position (a synthesized touch). */
  fun tap(): Boolean {
    val svc = service ?: return false
    val dm = svc.resources.displayMetrics
    val (x, y) = synchronized(cursorLock) {
      initCursor(dm.widthPixels, dm.heightPixels)
      RemoteCursor.showAt(curX.toInt(), curY.toInt())
      curX to curY
    }
    // Android path construction and gesture dispatch stay outside the state lock.
    val path = Path().apply { moveTo(x, y) }
    return dispatch(svc, path, durationMs = 50)
  }

  /** Swipe/scroll from the current pointer by a relative delta (px) — content scrolls. */
  fun swipe(dx: Float, dy: Float): Boolean {
    val svc = service ?: return false
    val dm = svc.resources.displayMetrics
    val (x, y) = synchronized(cursorLock) {
      initCursor(dm.widthPixels, dm.heightPixels)
      curX to curY
    }
    val x2 = (x + dx).coerceIn(0f, dm.widthPixels - 1f)
    val y2 = (y + dy).coerceIn(0f, dm.heightPixels - 1f)
    val path = Path().apply { moveTo(x, y); lineTo(x2, y2) }
    return dispatch(svc, path, durationMs = 120)
  }

  /**
   * Page-scroll the foreground content with ONE long vertical swipe down the screen centre —
   * the reliable scroll on the Portal. [down] = true reveals content below (finger swipes up);
   * false reveals above. (A stream of tiny per-frame swipes gets cancelled and ignored, which is
   * why the remote uses discrete scroll buttons instead of a two-finger drag.)
   */
  fun scrollPage(down: Boolean): Boolean {
    val svc = service ?: return false
    val dm = svc.resources.displayMetrics
    val cx = dm.widthPixels / 2f
    val y1 = dm.heightPixels * (if (down) 0.72f else 0.28f)
    val y2 = dm.heightPixels * (if (down) 0.28f else 0.72f)
    val path = Path().apply { moveTo(cx, y1); lineTo(cx, y2) }
    return dispatch(svc, path, durationMs = 250)
  }

  /** Centre the pointer on first use. All callers hold cursorLock. */
  private fun initCursor(w: Int, h: Int) {
    if (curX < 0f) {
      curX = w / 2f
      curY = h / 2f
    }
  }

  private fun dispatch(svc: AccessibilityService, path: Path, durationMs: Long): Boolean {
    val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
    val gesture = GestureDescription.Builder().addStroke(stroke).build()
    return runCatching { svc.dispatchGesture(gesture, null, null) }
        .onFailure { Log.w(TAG, "dispatchGesture failed", it) }
        .getOrDefault(false)
  }

  // --- pure helpers (unit-tested) ---------------------------------------------

  /** Compute the new field text for an edit [mode]. Pure. */
  internal fun nextText(current: String, text: String, mode: String): String =
      when (mode.lowercase().trim()) {
        "append" -> current + text
        "backspace" -> if (current.isNotEmpty()) current.dropLast(1) else ""
        "clear" -> ""
        else -> text // "set"
      }
}

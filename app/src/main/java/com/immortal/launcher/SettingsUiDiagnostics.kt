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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import java.lang.ref.WeakReference
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.json.JSONArray
import org.json.JSONObject

/** Explicit read-only functional evidence; excluded from ordinary and timing builds. */
internal object SettingsUiDiagnostics {
  private val main by lazy { Handler(Looper.getMainLooper()) }
  private var resumed: WeakReference<ImmortalSettingsActivity>? = null

  fun resumed(activity: ImmortalSettingsActivity) {
    if (BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS) resumed = WeakReference(activity)
  }

  fun paused(activity: ImmortalSettingsActivity) {
    if (resumed?.get() === activity) {
      resumed?.clear()
      resumed = null
    }
  }

  private fun error(status: Int, code: String) = FleetHttpServer.Response(status,
      JSONObject().put("ok", false).put("error", code).toString())

  private fun guard(context: Context): FleetHttpServer.Response? = when {
    !BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS -> error(403, "settings_ui_disabled")
    !DevMode.isEnabled(context) -> error(403, "dev_mode_required")
    HomeUiDiagnostics.collectorBusy() -> error(409, "settings_ui_perf_busy")
    else -> null
  }

  fun handle(context: Context, req: FleetHttpServer.Request): FleetHttpServer.Response {
    if (req.method != "GET") return error(405, "method_not_allowed")
    guard(context)?.let { return it }
    if (Looper.myLooper() == Looper.getMainLooper()) return error(503, "settings_ui_worker_required")
    val deadline = SystemClock.elapsedRealtime() + 2_000L
    val gate = FramePerformanceCommandGate()
    val task = FutureTask {
      if (!gate.begin()) error(503, "settings_ui_cancelled")
      else if (SystemClock.elapsedRealtime() >= deadline) error(503, "settings_ui_main_timeout")
      else guard(context) ?: read(context)
    }
    if (!main.post(task)) return error(503, "settings_ui_main_unavailable")
    return try {
      task.get(2, TimeUnit.SECONDS)
    } catch (_: TimeoutException) {
      if (gate.cancelBeforeBegin()) task.cancel(false)
      main.removeCallbacks(task)
      error(503, "settings_ui_main_timeout")
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      if (gate.cancelBeforeBegin()) task.cancel(false)
      main.removeCallbacks(task)
      error(503, "settings_ui_main_timeout")
    } catch (_: Exception) {
      if (gate.cancelBeforeBegin()) task.cancel(false)
      main.removeCallbacks(task)
      error(503, "settings_ui_read_failed")
    }
  }

  private fun routeJson(route: SettingsUiProbeRoute) = JSONObject()
      .put("category", route.category).put("page", route.page ?: JSONObject.NULL)
      .put("control", route.control ?: JSONObject.NULL)

  private fun read(context: Context): FleetHttpServer.Response {
    val activity = resumed?.get()?.takeIf {
      it.packageName == context.packageName && !it.isFinishing && !it.isDestroyed && it.hasWindowFocus()
    } ?: return error(409, "settings_ui_not_focused")
    val store = activity.settingsUiProbe ?: return error(409, "settings_ui_unavailable")
    val dm = activity.resources.displayMetrics
    val snapshot = store.snapshot(dm.widthPixels, dm.heightPixels, activity.settingsUiRequestedRoute())
        ?: return error(409, "settings_ui_not_ready")
    val targets = JSONArray()
    snapshot.targets.forEach {
      targets.put(JSONObject().put("kind", it.kind).put("id", it.id)
          .put("bounds", JSONArray(listOf(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom))))
    }
    val results = JSONArray()
    snapshot.results.forEachIndexed { index, route ->
      results.put(routeJson(route).put("id", index))
    }
    return FleetHttpServer.Response(200, JSONObject().put("ok", true).put("ready", true)
        .put("route", routeJson(snapshot.route)).put("drawnScope", snapshot.drawnScope)
        .put("historyDepth", activity.settingsUiHistoryDepth())
        .put("windowIdentity", System.identityHashCode(activity.window))
        .put("windowAnimationStyle", activity.window.attributes.windowAnimations)
        .put("screenWidth", dm.widthPixels).put("screenHeight", dm.heightPixels)
        .put("search", JSONObject().put("inputTextLength", snapshot.queryLength).put("results", results))
        .put("targets", targets).toString())
  }
}

/** Call sites also gate this modifier so normal builds add no layout observer. */
@Composable
internal fun Modifier.settingsUiTarget(kind: String, id: String, scope: String? = null,
    queryToken: Any? = null, control: String? = null): Modifier {
  if (!BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS) return this
  val activity = LocalContext.current as? ImmortalSettingsActivity ?: return this
  val store = activity.settingsUiProbe ?: return this
  val targetScope = scope?.let { it + control?.let { key -> "#$key" }.orEmpty() }
  val owner = remember(store, kind, id, targetScope, queryToken) { Any() }
  DisposableEffect(store, kind, id, targetScope, queryToken, owner) {
    onDispose { store.removeTarget(kind, id, targetScope, queryToken, owner) }
  }
  return onGloballyPositioned { coordinates ->
    if (coordinates.isAttached) {
      val rect = coordinates.boundsInWindow()
      // Popups have their own Window; the Activity decor origin cannot locate their rows.
      val windowPosition = coordinates.positionInWindow()
      val screenPosition = coordinates.positionOnScreen()
      val bounds = SettingsUiProbeBounds.fromWindow(rect.left, rect.top, rect.right, rect.bottom,
          screenPosition.x - windowPosition.x, screenPosition.y - windowPosition.y)
      store.target(kind, id, targetScope, queryToken, bounds ?: SettingsUiProbeBounds(0, 0, 0, 0), owner)
    }
  }
}

@Composable
internal fun Modifier.settingsUiDrawn(scope: String, control: String?): Modifier {
  if (!BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS) return this
  val activity = LocalContext.current as? ImmortalSettingsActivity ?: return this
  val store = activity.settingsUiProbe ?: return this
  val route = remember(scope, control) {
    SettingsUiProbeRoute(scope.substringBefore('/'),
        scope.substringAfter('/').takeUnless { it == "overview" }, control)
  }
  val owner = remember(store, route) { Any() }
  DisposableEffect(store, route, owner) { onDispose { store.retireDraw(route, owner) } }
  return drawWithContent {
    drawContent()
    store.markDrawn(route, owner)
  }
}

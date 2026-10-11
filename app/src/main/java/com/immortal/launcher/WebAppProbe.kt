/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Opt-in functional fixture. Never reads or injects script into a user's website. */
internal object WebAppProbe {
  private const val BASE = "file:///android_asset/webapp-retention-probe.html"
  private val commands = setOf("start-probe", "seed-probe", "second-probe", "close-probe")
  private val main by lazy { Handler(Looper.getMainLooper()) }
  private var current: WeakReference<WebAppActivity>? = null

  internal fun isProbeUrl(url: String?): Boolean = url == "$BASE?page=one" || url == "$BASE?page=two"
  internal fun command(body: JSONObject): String? = if (body.length() == 1)
      (body.opt("action") as? String)?.takeIf { it in commands } else null

  fun created(activity: WebAppActivity) {
    if (BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS) current = WeakReference(activity)
  }
  fun destroyed(activity: WebAppActivity) {
    if (current?.get() === activity) current = null
  }
  private fun response(status: Int, value: JSONObject) = FleetHttpServer.Response(status, value.toString())
  private fun error(status: Int, code: String) = response(status, JSONObject().put("ok", false).put("error", code))
  private fun ok() = JSONObject().put("ok", true)
  private fun guard(context: Context): FleetHttpServer.Response? = when {
    !BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS -> error(403, "webapp_probe_disabled")
    !DevMode.isEnabled(context) -> error(403, "dev_mode_required")
    HomeUiDiagnostics.collectorBusy() -> error(409, "webapp_probe_perf_busy")
    else -> null
  }

  fun handle(context: Context, request: FleetHttpServer.Request): FleetHttpServer.Response {
    guard(context)?.let { return it }
    if (request.method !in setOf("GET", "POST")) return error(405, "method_not_allowed")
    val action = if (request.method == "POST") runCatching {
      command(JSONObject(request.bodyText()))
    }.getOrNull() ?: return error(400, "invalid_probe_command") else null
    if (Looper.myLooper() == Looper.getMainLooper()) return error(503, "worker_required")
    val result = CompletableFuture<FleetHttpServer.Response>()
    val gate = FramePerformanceCommandGate()
    val task = Runnable {
      if (!gate.begin()) return@Runnable
      guard(context)?.let { result.complete(it); return@Runnable }
      try {
        val activity = current?.get()?.takeUnless { it.isFinishing || it.isDestroyed }
        if (action == null) {
          if (activity == null) result.complete(response(200, ok().put("active", false).put("probe", false)))
          else if (!activity.isGeneratedProbe()) result.complete(response(200, ok().put("active", true).put("probe", false)))
          else if (!activity.hasWindowFocus()) result.complete(response(200, ok().put("active", true).put("probe", true).put("focused", false)))
          else activity.generatedProbeSnapshot { state ->
            result.complete(state?.let { response(200, it) } ?: error(409, "probe_not_ready"))
          }
        } else if (activity != null && !activity.isGeneratedProbe()) {
          result.complete(error(409, "real_webapp_active"))
        } else when (action) {
          "start-probe", "second-probe" -> {
            val url = BASE + if (action == "start-probe") "?page=one" else "?page=two"
            context.startActivity(Intent(context, WebAppActivity::class.java)
                .putExtra(WebAppActivity.EXTRA_URL, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            result.complete(response(200, ok().put("dispatched", true)))
          }
          "seed-probe" -> result.complete(if (activity?.seedGeneratedProbe() == true)
              response(200, ok().put("dispatched", true)) else error(409, "probe_not_ready"))
          "close-probe" -> {
            activity?.finish()
            result.complete(response(200, ok().put("dispatched", true)))
          }
        }
      } catch (_: Exception) { result.complete(error(503, "probe_failed")) }
    }
    if (!main.post(task)) return error(503, "main_unavailable")
    return try { result.get(2, TimeUnit.SECONDS) } catch (_: Exception) {
      val notStarted = gate.cancelBeforeBegin()
      if (notStarted) main.removeCallbacks(task)
      if (!notStarted && action != null) response(202, ok().put("result", "completion_unconfirmed")
          .put("checkStateBeforeRetry", true)) else error(503, "probe_timeout")
    }
  }
}

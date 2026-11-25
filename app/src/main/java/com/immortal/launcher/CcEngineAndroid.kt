/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** The engine's view of the Portal: each member is one call into preferences, files or banners. */
internal class CcAndroidHost(private val app: Context) : CcHost {
  override fun settings() = ControlCenterConfig.load(app)
  override fun haConfigured() = HaConfig.isConfigured(app)
  override fun haBaseUrl() = HaConfig.baseUrl(app)
  override fun haToken() = HaConfig.token(app)

  override fun online(): Boolean = runCatching {
    @Suppress("DEPRECATION")
    (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetworkInfo?.isConnected == true
  }.getOrDefault(true)

  override fun loadPlan() = CcPlanStore.load(app)
  override fun savePlan(plan: CcPlan) = CcPlanStore.save(app, plan)

  override fun loadLastKnown() = CcLastKnownStore.load(app)
  override fun saveLastKnown(entities: Map<String, CcEntity>) = CcLastKnownStore.save(app, entities)

  override fun dryRun() = CcDebugStore.dryRun(app)
  override fun setDryRun(on: Boolean) = CcDebugStore.setDryRun(app, on)
  override fun testPlan() = CcDebugStore.testPlan(app)
  override fun saveTestPlan(plan: CcPlan) = CcDebugStore.saveTestPlan(app, plan)
  override fun clearTestPlan() = CcDebugStore.clearTestPlan(app)

  override fun scenesSeen() = ControlCenterConfig.scenesSeen(app)
  override fun markSceneSeen(id: String) = ControlCenterConfig.markSceneSeen(app, id)

  override fun presentAlerts(plan: CcBackgroundPolicy.Plan, settings: ControlCenterConfig.Settings, appVisible: Boolean, dryRun: Boolean) =
      CcAlertRelay.present(app, plan, settings, appVisible, dryRun)

  override fun offerBanner(alert: CcAlert, settings: ControlCenterConfig.Settings, appVisible: Boolean, dryRun: Boolean) =
      ControlCenterBanners.offer(app, alert, settings, appVisible, dryRun)

  override fun homeDot(state: CcUiState, visible: Boolean) = HomeControlDotStore.update(state, visible)
}

/** Home Assistant websockets over OkHttp (no read timeout: the heartbeat finds a dead socket). */
internal class OkHttpCcSockets(client: OkHttpClient? = null) : CcSocketFactory {
  private val client by lazy {
    client ?: OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).connectTimeout(CONNECT_S, TimeUnit.SECONDS).build()
  }

  override fun open(url: String, events: CcSocketEvents): CcSocket? {
    val request = runCatching { Request.Builder().url(url).build() }.getOrElse { return null }
    return Socket(client.newWebSocket(request, Listener(events)))
  }

  private class Socket(private val ws: WebSocket) : CcSocket {
    override fun send(text: String) = ws.send(text)
    override fun close(code: Int) { ws.close(code, null) }
    override fun cancel() = ws.cancel()
  }

  private class Listener(private val events: CcSocketEvents) : WebSocketListener() {
    override fun onMessage(webSocket: WebSocket, text: String) = events.onMessage(text)
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = events.onClosed(code)
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = events.onFailure(t)
  }

  private companion object {
    const val CONNECT_S = 8L
  }
}

/** The engine thread: one daemon thread, or [exec] when given (tests). */
internal class ExecutorCcScheduler(exec: ScheduledExecutorService? = null) : CcScheduler {
  private val exec = exec ?: Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hearthpane-control").apply { isDaemon = true } }

  override fun execute(task: () -> Unit) = exec.execute(Runnable { task() })
  override fun schedule(delayMs: Long, task: () -> Unit): CcTimer =
      Timer(exec.schedule(Runnable { task() }, delayMs, TimeUnit.MILLISECONDS))
  override fun repeat(periodMs: Long, task: () -> Unit): CcTimer =
      Timer(exec.scheduleWithFixedDelay(Runnable { task() }, periodMs, periodMs, TimeUnit.MILLISECONDS))
  override fun <T> call(timeoutMs: Long, task: () -> T): T = exec.submit(Callable { task() }).get(timeoutMs, TimeUnit.MILLISECONDS)

  private class Timer(private val f: ScheduledFuture<*>) : CcTimer {
    override val isDone: Boolean get() = f.isDone
    override fun cancel() { f.cancel(false) }
  }
}

internal object AndroidCcLog : CcLog {
  override fun i(message: String) { Log.i(CcEngine.TAG, message) }
  override fun w(message: String, error: Throwable?) { if (error != null) Log.w(CcEngine.TAG, message, error) else Log.w(CcEngine.TAG, message) }
}

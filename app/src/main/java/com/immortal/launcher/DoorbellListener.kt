/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Keeps one Home Assistant websocket open while this Portal watches for the doorbell
 * ([DoorbellConfig.Settings.listen], a doorbell or motion entity chosen, HA connected). The
 * conversation is [DoorbellSocketProtocol]; changes go to [DoorbellAlerts.onChange]. A dropped or
 * refused connection retries with [DoorbellBackoff] (a rejected token waits the longest). [restart]
 * re-reads the settings (the domain's onApplied and the HA connection form call it).
 *
 * Every socket belongs to a generation; callbacks from an older one are ignored, so a restart can
 * never leave two listeners. Logs never include the address or the token.
 */
internal object DoorbellListener {
  private const val TAG = "HearthpaneDoorbell"
  private const val PING_S = 30L
  private const val CLOSE_NORMAL = 1000

  private val client by lazy {
    OkHttpClient.Builder().pingInterval(PING_S, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
  }
  private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hearthpane-doorbell").apply { isDaemon = true } }
  private val lock = Any()
  private var generation = 0L
  private var socket: WebSocket? = null
  private var retry: ScheduledFuture<*>? = null
  private var attempt = 0

  /** True while subscribed to Home Assistant (for `/remote/doorbell`). */
  @Volatile var connected = false
    private set

  fun restart(context: Context) {
    val app = context.applicationContext
    scheduler.execute { connect(app, newGeneration()) }
  }

  private fun newGeneration(): Long = synchronized(lock) {
    generation++
    retry?.cancel(false); retry = null
    socket?.close(CLOSE_NORMAL, null); socket = null
    connected = false
    attempt = 0
    generation
  }

  private fun current(gen: Long) = synchronized(lock) { gen == generation }

  private fun connect(app: Context, gen: Long) {
    // Clear the running retry first: a failure callback may arrive before this task returns.
    synchronized(lock) { if (gen != generation) return; retry = null }
    val settings = DoorbellConfig.load(app)
    val url = DoorbellUrls.websocket(HaConfig.baseUrl(app))
    if (!settings.listen || settings.watched.isEmpty() || !HaConfig.isConfigured(app) || url == null) {
      Log.i(TAG, "doorbell listener idle (not set up here)")
      return
    }
    val protocol = DoorbellSocketProtocol(HaConfig.token(app), settings.watched)
    val request = runCatching { Request.Builder().url(url).build() }.getOrElse {
      Log.w(TAG, "doorbell listener: Home Assistant address is not usable"); return
    }
    val ws = client.newWebSocket(request, Callbacks(app, gen, protocol))
    synchronized(lock) { if (gen == generation) socket = ws else ws.cancel() }
  }

  private class Callbacks(val app: Context, val gen: Long, val protocol: DoorbellSocketProtocol) : WebSocketListener() {
    override fun onMessage(webSocket: WebSocket, text: String) {
      if (!current(gen)) return
      protocol.onMessage(text).forEach { out ->
        when (out) {
          is DoorbellSocketProtocol.Out.Send -> webSocket.send(out.text)
          is DoorbellSocketProtocol.Out.Change -> DoorbellAlerts.onChange(app, out.entityId, out.old, out.new)
          DoorbellSocketProtocol.Out.Ready -> ready()
          DoorbellSocketProtocol.Out.AuthFailed -> fail(webSocket, "token refused", DoorbellBackoff.AUTH_RETRY_MS)
          DoorbellSocketProtocol.Out.Failed -> fail(webSocket, "subscription refused", null)
        }
      }
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped("closed ($code)")

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(t.javaClass.simpleName)

    private fun ready() {
      synchronized(lock) { if (gen != generation) return; attempt = 0 }
      connected = true
      Log.i(TAG, "doorbell listener subscribed")
    }

    private fun fail(webSocket: WebSocket, why: String, delayMs: Long?) {
      Log.w(TAG, "doorbell listener: $why")
      webSocket.close(CLOSE_NORMAL, null)
      reconnect(delayMs)
    }

    private fun dropped(why: String) {
      if (!current(gen)) return
      Log.i(TAG, "doorbell listener $why; reconnecting")
      reconnect(null)
    }

    private fun reconnect(delayMs: Long?) = synchronized(lock) {
      if (gen != generation) return@synchronized
      connected = false
      socket = null
      if (retry?.isDone == false) return@synchronized
      val wait = delayMs ?: DoorbellBackoff.delayMs(attempt++)
      retry = scheduler.schedule({ connect(app, gen) }, wait, TimeUnit.MILLISECONDS)
    }
  }
}

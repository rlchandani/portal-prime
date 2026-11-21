/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * LAN bus for signed Portal↔Portal [HouseholdMessage]s. Features register a [Handler] per message
 * type in their own files and call [send]; the bus drops anything unsigned, stale, replayed, from
 * this device itself, or of an unregistered type. Until the phone provisions a [HouseholdKey] the
 * bus neither sends nor accepts anything.
 *
 * Handlers run on the bus's listener thread and must hand off to the main thread themselves.
 */
internal object HouseholdBus {
  private const val TAG = "ImmortalHouseholdBus"
  const val PORT = 50122
  private const val SEND_REPEATS = 2 // a dropped Wi-Fi frame should not lose a dinner call
  /** An unreachable network (Wi-Fi down or roaming) is logged at debug, at most this often. */
  const val UNREACHABLE_LOG_INTERVAL_MS = 10L * 60 * 1000
  private val unreachableLog = MemoryHealthPolicy.LogThrottle(UNREACHABLE_LOG_INTERVAL_MS)
  /** Music move messages are logged on arrival, to diagnose "Move to…" / "Bring it back". */
  private const val MUSIC_PREFIX = "music_"
  private val RESTART_MS = longArrayOf(1_000L, 5_000L, 30_000L)

  fun interface Handler {
    fun onMessage(context: Context, message: HouseholdMessage)
  }

  private val handlers = ConcurrentHashMap<String, Handler>()
  private val replay = HouseholdMessage.ReplayGuard()
  private val random = SecureRandom()
  @Volatile private var running = false
  @Volatile private var socket: DatagramSocket? = null
  private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

  /**
   * Wraps work a bus handler hands to the main thread or an executor, so a bug triggered by a peer's
   * message is logged and dropped there too: on Android an uncaught throw on either kills the
   * launcher, and a message every Portal receives would take them all down at once.
   */
  fun contained(type: String, block: () -> Unit): Runnable = Runnable {
    runCatching(block).onFailure { Log.w(TAG, "deferred $type work failed: ${it.javaClass.simpleName} at ${it.stackTrace.firstOrNull()}") }
  }

  /** Registers the handler for [type] (one per type; a feature re-registering replaces itself). */
  fun register(type: String, handler: Handler) { handlers[type] = handler }

  /** Starts listening (idempotent). Called once from process start. */
  fun start(context: Context) {
    if (running) return
    running = true
    val app = context.applicationContext
    // Wi-Fi drops broadcast frames unless a multicast lock is held.
    runCatching {
      val wifi = app.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
      multicastLock = wifi.createMulticastLock("immortal-household").apply { setReferenceCounted(false); acquire() }
    }.onFailure { Log.w(TAG, "multicast lock failed (household messages may not arrive)", it) }
    thread(name = "immortal-household-bus", isDaemon = true) {
      var failures = 0
      while (running) {
        runCatching { listen(app) { failures = 0 } }
            .onFailure { if (running) Log.w(TAG, "household bus stopped: ${it.javaClass.simpleName}") }
        runCatching { socket?.close() }
        socket = null
        // The socket died (Wi-Fi bounce, port briefly taken): rebind with backoff, never give up.
        if (running) Thread.sleep(RESTART_MS[minOf(failures++, RESTART_MS.size - 1)])
      }
    }
  }

  private fun listen(app: Context, onPacket: () -> Unit) {
    val s = DatagramSocket(PORT).apply { broadcast = true; reuseAddress = true }
    socket = s
    val buf = ByteArray(HouseholdMessage.MAX_WIRE_CHARS * 4)
    while (running) {
      val pkt = DatagramPacket(buf, buf.size)
      s.receive(pkt)
      onPacket()
      handlePacket(app, String(pkt.data, 0, pkt.length, Charsets.UTF_8))
    }
  }

  /** One datagram, fully contained: nothing a packet does can stop the listener. */
  internal fun handlePacket(context: Context, wire: String) {
    runCatching { receive(context, wire) }
        .onFailure { Log.w(TAG, "household packet dropped: ${it.javaClass.simpleName}") }
  }

  private fun receive(context: Context, wire: String) {
    val key = HouseholdKey.get(context) ?: return
    val message = HouseholdMessage.decode(wire, key, System.currentTimeMillis(), replay) ?: return
    val self = RemoteCompanionStore.cachedId(context)
    if (message.from == self) return
    if (message.type.startsWith(MUSIC_PREFIX)) Log.i(TAG, "received ${message.type}")
    val handler = handlers[message.type] ?: return Unit.also { Log.i(TAG, "no handler for ${message.type}") }
    runCatching { handler.onMessage(context, message) }
        // Class and frame only: an exception message can quote the (household-private) body.
        .onFailure { Log.w(TAG, "handler for ${message.type} failed: ${it.javaClass.simpleName} at ${it.stackTrace.firstOrNull()}") }
  }

  /**
   * Broadcasts a signed [type] message with [body] to every Portal on the LAN. Returns false (and
   * sends nothing) when no household key is provisioned yet or the message cannot be encoded.
   */
  fun send(context: Context, type: String, body: JSONObject): Boolean {
    val key = HouseholdKey.get(context) ?: return false
    val from = RemoteCompanionStore.cachedId(context) ?: return false
    val nonce = ByteArray(12).also(random::nextBytes).let(HouseholdKey::toHex)
    val wire = HouseholdMessage.encodeOrNull(
        HouseholdMessage(type, from, System.currentTimeMillis(), nonce, body), key) ?: return false
    thread(name = "immortal-household-send", isDaemon = true) {
      runCatching {
        DatagramSocket().use { s ->
          s.broadcast = true
          val bytes = wire.toByteArray(Charsets.UTF_8)
          val addr = InetAddress.getByName("255.255.255.255")
          repeat(SEND_REPEATS) { // same nonce: receivers drop the repeat via the replay guard
            s.send(DatagramPacket(bytes, bytes.size, addr, PORT))
            Thread.sleep(120)
          }
        }
      }.onFailure { logSendFailure(it, unreachableLog, System.nanoTime() / 1_000_000) }
    }
    return true
  }

  /** True when [error] (or a cause) is a "no route" failure: expected while Wi-Fi is down. */
  internal fun isNetworkUnreachable(error: Throwable): Boolean =
      generateSequence(error) { it.cause }.take(8).any { e ->
        e is java.net.NoRouteToHostException ||
            e.message?.let { "ENETUNREACH" in it || "EHOSTUNREACH" in it } == true
      }

  /** Unreachable networks are a rate-limited debug line; any other failure warns with its stack. */
  internal fun logSendFailure(error: Throwable, throttle: MemoryHealthPolicy.LogThrottle, nowMs: Long) {
    if (!isNetworkUnreachable(error)) {
      Log.w(TAG, "household send failed", error)
      return
    }
    if (synchronized(throttle) { throttle.shouldLog(nowMs) }) {
      Log.d(TAG, "household send skipped: network unreachable")
    }
  }
}

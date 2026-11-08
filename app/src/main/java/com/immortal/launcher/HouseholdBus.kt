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
  /** Music move messages are logged on arrival, to diagnose "Move to…" / "Bring it back". */
  private const val MUSIC_PREFIX = "music_"

  fun interface Handler {
    fun onMessage(context: Context, message: HouseholdMessage)
  }

  private val handlers = ConcurrentHashMap<String, Handler>()
  private val replay = HouseholdMessage.ReplayGuard()
  private val random = SecureRandom()
  @Volatile private var running = false
  @Volatile private var socket: DatagramSocket? = null
  private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

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
      runCatching {
        val s = DatagramSocket(PORT).apply { broadcast = true; reuseAddress = true }
        socket = s
        val buf = ByteArray(HouseholdMessage.MAX_WIRE_CHARS * 4)
        while (running) {
          val pkt = DatagramPacket(buf, buf.size)
          s.receive(pkt)
          receive(app, String(pkt.data, 0, pkt.length, Charsets.UTF_8))
        }
      }.onFailure { if (running) Log.w(TAG, "household bus stopped", it) }
    }
  }

  private fun receive(context: Context, wire: String) {
    val key = HouseholdKey.get(context) ?: return
    val message = HouseholdMessage.decode(wire, key, System.currentTimeMillis(), replay) ?: return
    val self = runCatching { RemoteCompanionStore.id(context) }.getOrNull()
    if (message.from == self) return
    if (message.type.startsWith(MUSIC_PREFIX)) Log.i(TAG, "received ${message.type}")
    val handler = handlers[message.type] ?: return Unit.also { Log.i(TAG, "no handler for ${message.type}") }
    runCatching { handler.onMessage(context, message) }
        .onFailure { Log.w(TAG, "handler for ${message.type} failed", it) }
  }

  /**
   * Broadcasts a signed [type] message with [body] to every Portal on the LAN. Returns false (and
   * sends nothing) when no household key is provisioned yet or the message cannot be encoded.
   */
  fun send(context: Context, type: String, body: JSONObject): Boolean {
    val key = HouseholdKey.get(context) ?: return false
    val from = runCatching { RemoteCompanionStore.id(context) }.getOrNull() ?: return false
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
      }.onFailure { Log.w(TAG, "household send failed", it) }
    }
    return true
  }
}

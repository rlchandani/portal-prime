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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * Sends one recorded message to the recipient Portal over the LAN, sealed chunk by chunk under the
 * household-derived transit key while it streams, and only reports success for an answer signed
 * over our header nonce. Once confirmed, the sender keeps its own copy sealed at rest
 * ([CallMessageStore.keepSent]) so Recent can play what was sent. Blocking: call off the main thread.
 */
internal object CallMessageSender {
  private const val TAG = "HearthpaneCallMsg"
  private const val CONNECT_MS = 4_000
  private const val READ_MS = 30_000
  private const val MAX_RESPONSE = 16 * 1024
  private val random = SecureRandom()

  /** Outcome: null when the recipient confirmed it, else a short reason for the screen. */
  fun send(context: Context, take: File, peer: CallPeer, durationMs: Long, audioOnly: Boolean, callId: String? = null): String? = runCatching {
    val host = peer.host ?: return "no_address"
    if (!HouseholdListPeerAuth.isPrivateLan(host) || peer.port !in 1..65535) return "no_address"
    val key = HouseholdKey.get(context) ?: return "no_household_key"
    val self = Calling.selfId(context) ?: return "no_id"
    val plain = take.length()
    if (plain <= 0 || plain > CallMessageLimits.MAX_BYTES) return CallMessageRefusal.TOO_LARGE.code
    val id = ByteArray(ID_BYTES).also(random::nextBytes).let(HouseholdKey::toHex)
    val sealed = CallMessageCrypto.sealedLength(plain)
    val put = CallMessageWire.Put(id, peer.id, Calling.selfName(context), durationMs, sealed, CallMessageCrypto.chunkCount(plain), audioOnly,
        callId?.takeIf(CallWire::isCallId))
    // Opened now so this Portal's own copy can still be kept if the screen closes (unlinking the take) mid-upload.
    val keep = runCatching { FileInputStream(take) }.getOrNull()
    val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes).let(HouseholdKey::toHex)
    val header = CallMessageWire.requestHeader(key, self, nonce, System.currentTimeMillis(), put) ?: return "bad_header"
    val conn = URL("http", host, peer.port, CallMessageWire.PATH).openConnection() as HttpURLConnection
    try {
      conn.connectTimeout = CONNECT_MS
      conn.readTimeout = READ_MS
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.instanceFollowRedirects = false
      conn.setRequestProperty("Content-Type", "application/octet-stream")
      conn.setRequestProperty(CallMessageWire.HEADER, header)
      conn.setFixedLengthStreamingMode(sealed)
      BufferedInputStream(FileInputStream(take)).use { input ->
        BufferedOutputStream(conn.outputStream, CallMessageCrypto.SEALED_CHUNK).use { out ->
          CallMessageCrypto.seal(CallMessageCrypto.transitKey(key, id), id, input, plain, out)
        }
      }
      val code = conn.responseCode
      if (code != HTTP_OK) {
        val err = runCatching { JSONObject(conn.errorStream.use(::readCapped)).optString("error") }.getOrNull()
        Log.i(TAG, "message refused: $code $err")
        return err?.takeIf { it.isNotBlank() } ?: "http_$code"
      }
      val text = conn.inputStream.use(::readCapped)
      val env = JSONObject(text)
      val payload = env.optString("payload")
      if (!CallMessageWire.verifyAck(key, nonce, payload, env.optString("sig"), id)) return "bad_ack"
      Log.i(TAG, "message ${id.take(4)}… delivered (${sealed / 1024} KiB)")
      keep?.let { CallMessageStore.keepSent(context, it, plain, id, peer, durationMs, audioOnly, put.callId, System.currentTimeMillis()) }
      null
    } finally {
      conn.disconnect()
      runCatching { keep?.close() }
    }
  }.getOrElse { Log.w(TAG, "message send failed: ${it.javaClass.simpleName}"); "unreachable" }

  /** A reply is a few hundred bytes; never read more than [MAX_RESPONSE] from the other side. */
  private fun readCapped(input: java.io.InputStream): String {
    val buf = ByteArray(MAX_RESPONSE)
    var n = 0
    while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
    return String(buf, 0, n, Charsets.UTF_8)
  }

  private const val ID_BYTES = 8
  private const val NONCE_BYTES = 12
  private const val HTTP_OK = 200
}

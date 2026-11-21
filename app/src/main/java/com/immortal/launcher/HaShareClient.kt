/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A household Portal that announced where its fleet agent listens (from its signed hello). */
internal data class HaSharePeer(val id: String, val name: String, val host: String, val port: Int)

/** One POST to a peer's `/remote/hapeer`: the status and (bounded) body, or null when unreachable. May throw. */
internal fun interface HaShareTransport {
  fun post(host: String, port: Int, authHeader: String, body: String): HaShareTransport.Reply?

  data class Reply(val status: Int, val body: String?)
}

/** What a peer said. [code] is the route's error code for a refusal (never a secret). */
internal sealed interface HaShareAnswer {
  data class Connected(val name: String) : HaShareAnswer
  data class Shared(val name: String, val connection: HaShareWire.Connection) : HaShareAnswer
  data class Refused(val status: Int, val code: String) : HaShareAnswer
  object Unreachable : HaShareAnswer
  /** It answered 200, but the reply failed its signature or seal: treated as hostile and dropped. */
  object Invalid : HaShareAnswer
}

/** The requesting Portal's end of `/remote/hapeer`: sign, POST, verify the reply against our nonce, open the seal. */
internal class HaShareClient(
    private val key: () -> ByteArray?,
    private val selfId: () -> String?,
    private val transport: HaShareTransport,
    private val clock: () -> Long,
    private val nonce: () -> String,
) : HaAdoption.Asker {
  override fun probe(peer: HaSharePeer): HaShareAnswer = ask(peer, HaShareWire.Op.PROBE)

  override fun share(peer: HaSharePeer): HaShareAnswer = ask(peer, HaShareWire.Op.SHARE)

  private fun ask(peer: HaSharePeer, op: HaShareWire.Op): HaShareAnswer {
    // Only household Portals on the private LAN, on the agent's unprivileged port, are ever asked.
    if (!HouseholdListPeerAuth.isPrivateLan(peer.host) || peer.port !in SettingsSyncWire.MIN_PEER_PORT..MAX_PORT)
      return HaShareAnswer.Refused(0, "not_household_lan")
    val key = key() ?: return HaShareAnswer.Refused(0, "no_household_key")
    val self = selfId() ?: return HaShareAnswer.Refused(0, "no_identity")
    val n = nonce()
    val body = HaShareWire.requestBody(op)
    val header = HaShareWire.requestHeader(key, self, n, clock(), body) ?: return HaShareAnswer.Refused(0, "unsigned")
    val reply = runCatching { transport.post(peer.host, peer.port, header, body) }.getOrNull()
        ?: return HaShareAnswer.Unreachable
    if (reply.status != HTTP_OK) return HaShareAnswer.Refused(reply.status, errorCode(reply.body))
    val envelope = reply.body?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return HaShareAnswer.Invalid
    val payload = envelope.opt("payload") as? String ?: return HaShareAnswer.Invalid
    if (!HaShareWire.verifyResponse(key, n, payload, envelope.opt("sig") as? String)) return HaShareAnswer.Invalid
    return when (op) {
      HaShareWire.Op.PROBE -> HaShareWire.parseProbe(payload)
          ?.let { if (it.connected) HaShareAnswer.Connected(it.name) else HaShareAnswer.Refused(HTTP_OK, "not_connected") }
          ?: HaShareAnswer.Invalid
      HaShareWire.Op.SHARE -> HaShareWire.openShare(payload, key, self, peer.id, n)
          ?.let { HaShareAnswer.Shared(it.name, it.connection) } ?: HaShareAnswer.Invalid
    }
  }

  private fun errorCode(body: String?): String =
      body?.let { runCatching { JSONObject(it).optString("error") }.getOrNull() }
          ?.takeIf { ERROR_CODE.matches(it) } ?: "http_error"

  companion object {
    const val HTTP_OK = 200
    private const val MAX_PORT = 65_535
    private val ERROR_CODE = Regex("^[a-z_]{1,40}$")
  }
}

/** One bounded POST over HttpURLConnection; the reply (or error body) is capped while streaming. */
internal object HttpHaShareTransport : HaShareTransport {
  private const val CONNECT_MS = 3_000
  private const val READ_MS = 5_000
  private const val MAX_ERROR = 1_024

  override fun post(host: String, port: Int, authHeader: String, body: String): HaShareTransport.Reply? {
    val conn = URL("http", host, port, HaShareWire.PATH).openConnection() as HttpURLConnection
    try {
      conn.connectTimeout = CONNECT_MS
      conn.readTimeout = READ_MS
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.instanceFollowRedirects = false
      conn.useCaches = false
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty(HaShareWire.HEADER, authHeader)
      val bytes = body.toByteArray(Charsets.UTF_8)
      conn.setFixedLengthStreamingMode(bytes.size)
      conn.outputStream.use { it.write(bytes) }
      val status = conn.responseCode
      val text = if (status == HaShareClient.HTTP_OK) BoundedRead.reply(conn, HaShareWire.MAX_REPLY)
          else BoundedRead.errorReply(conn, MAX_ERROR)
      return HaShareTransport.Reply(status, text)
    } finally {
      conn.disconnect()
    }
  }
}

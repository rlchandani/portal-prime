/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom

/** A video message waiting on this Portal for a phone to collect it (sealed under the transit key). */
@Immutable
internal data class PhoneOutMessage(
    val id: String,
    /** This Portal's id for the phone (token-bound). */
    val phoneId: String,
    val fromName: String,
    val durationMs: Long,
    val sealedBytes: Long,
    val chunks: Int,
    val audioOnly: Boolean,
    val atMs: Long,
    val callId: String? = null,
)

/**
 * Pure rules for the phone outbox (JVM-tested). A phone has no server, so a message for it waits
 * here, already sealed chunk by chunk under the household-derived transit key, until Portal
 * Toolkit collects it (`/remote/call/msg/get`) and confirms (`/remote/call/msg/ack`). It is then
 * deleted. Uncollected messages go after [KEEP_MS] (the mock's "deleted on delivery, ≤ 7 days").
 */
internal object PhoneOutbox {
  const val MAX_PER_PHONE = 5
  const val MAX_TOTAL_BYTES = 150L * 1024 * 1024
  const val KEEP_MS = 7L * 24 * 60 * 60_000L

  enum class Refusal(val code: String) { FULL("full"), TOO_LARGE("too_large"), NO_SPACE("no_space") }

  fun canQueue(list: List<PhoneOutMessage>, phoneId: String, sealedBytes: Long, freeBytes: Long): Refusal? = when {
    sealedBytes <= 0 || CallMessageCrypto.plainLength(sealedBytes) > CallMessageLimits.MAX_BYTES -> Refusal.TOO_LARGE
    list.count { it.phoneId == phoneId } >= MAX_PER_PHONE -> Refusal.FULL
    list.sumOf { it.sealedBytes } + sealedBytes > MAX_TOTAL_BYTES -> Refusal.FULL
    freeBytes - sealedBytes < CallMessageLimits.MIN_FREE_BYTES -> Refusal.NO_SPACE
    else -> null
  }

  fun prune(list: List<PhoneOutMessage>, nowMs: Long): List<PhoneOutMessage> = list.filter { nowMs - it.atMs in 0..KEEP_MS }

  /** What this phone's poll lists (oldest first, so it collects in order). */
  fun forPhone(list: List<PhoneOutMessage>, phoneId: String): List<PhoneOutMessage> =
      list.filter { it.phoneId == phoneId }.sortedBy { it.atMs }

  fun find(list: List<PhoneOutMessage>, phoneId: String, id: String): PhoneOutMessage? =
      list.firstOrNull { it.id == id && it.phoneId == phoneId }

  /** The poll's `vm` entry: everything the phone needs before it fetches a byte. */
  fun announce(m: PhoneOutMessage): JSONObject = JSONObject().put("i", m.id).put("fn", m.fromName).put("ms", m.durationMs)
      .put("z", m.sealedBytes).put("c", m.chunks).put("a", m.audioOnly).put("at", m.atMs).apply { m.callId?.let { put("ci", it) } }

  fun encode(list: List<PhoneOutMessage>): String = JSONArray(list.map { m ->
    announce(m).put("p", m.phoneId)
  }).toString()

  /** Stored data is validated like wire data: anything malformed is dropped. */
  fun decode(raw: String?): List<PhoneOutMessage> {
    val arr = runCatching { JSONArray(raw ?: return emptyList()) }.getOrNull() ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::entry) }
  }

  private fun entry(o: JSONObject): PhoneOutMessage? {
    val id = o.optString("i").takeIf(CallMessageList::isId) ?: return null
    val phone = o.optString("p").takeIf(CallPhoneId::isPhone) ?: return null
    val name = DinnerWire.cleanName(o.optString("fn")) ?: return null
    val ms = (o.opt("ms") as? Number)?.toLong()?.takeIf { it > 0 } ?: return null
    val z = (o.opt("z") as? Number)?.toLong()?.takeIf { it > 0 } ?: return null
    val c = (o.opt("c") as? Number)?.toInt() ?: return null
    if (CallMessageCrypto.chunkCount(CallMessageCrypto.plainLength(z)) != c) return null
    val at = (o.opt("at") as? Number)?.toLong() ?: return null
    return PhoneOutMessage(id, phone, name, ms, z, c, o.optBoolean("a"), at, o.optString("ci").takeIf(CallWire::isCallId))
  }
}

/**
 * The phone outbox on disk: `no_backup/hearthpane_vmsg_phone/<id>.hpt` (transit-sealed, never
 * plaintext) plus an index. Fleet `/fs` can't reach it ([FleetSecretPaths]). Thread-safe.
 */
internal object CallPhoneOutbox {
  private const val TAG = "HearthpaneCallMsg"
  const val DIR = "hearthpane_vmsg_phone"
  private const val INDEX = "index.json"
  private const val SEALED = ".hpt"
  private const val PART = ".part"
  private const val ID_BYTES = 8
  private val lock = Any()
  private val random = SecureRandom()
  @Volatile private var cache: List<PhoneOutMessage>? = null

  fun dir(c: Context): File = File(c.noBackupFilesDir, DIR).apply { mkdirs() }
  fun file(c: Context, id: String): File? = if (CallMessageList.isId(id)) File(dir(c), id + SEALED) else null

  fun list(c: Context): List<PhoneOutMessage> = synchronized(lock) { loadLocked(c.applicationContext) }

  fun waiting(c: Context, phoneId: String): List<PhoneOutMessage> = PhoneOutbox.forPhone(list(c), phoneId)

  /**
   * Seals [take] for [phone] into the outbox and keeps this Portal's own sent copy. Null on
   * success, else a short reason for the compose screen. Blocking: call off the main thread.
   */
  fun put(context: Context, take: File, phone: CallPeer, durationMs: Long, audioOnly: Boolean, callId: String?): String? = runCatching {
    val c = context.applicationContext
    val key = HouseholdKey.get(c) ?: return "no_household_key"
    if (!CallPhoneId.isPhone(phone.id)) return "no_phone"
    val plain = take.length()
    if (plain <= 0 || plain > CallMessageLimits.MAX_BYTES) return CallMessageRefusal.TOO_LARGE.code
    val sealedBytes = CallMessageCrypto.sealedLength(plain)
    val id = ByteArray(ID_BYTES).also(random::nextBytes).let(HouseholdKey::toHex)
    synchronized(lock) {
      PhoneOutbox.canQueue(loadLocked(c), phone.id, sealedBytes, dir(c).usableSpace)?.let { return it.code }
    }
    val part = File(dir(c), id + PART)
    try {
      FileOutputStream(part).use { fos ->
        val out = BufferedOutputStream(fos, CallMessageCrypto.SEALED_CHUNK)
        BufferedInputStream(FileInputStream(take), CallMessageLimits.CHUNK).use { input ->
          CallMessageCrypto.seal(CallMessageCrypto.transitKey(key, id), id, input, plain, out)
        }
        out.flush()
        fos.fd.sync()
      }
      if (!part.renameTo(File(dir(c), id + SEALED))) throw java.io.IOException("rename failed")
    } finally {
      part.delete()
    }
    val now = System.currentTimeMillis()
    val m = PhoneOutMessage(id, phone.id, Calling.selfName(c), durationMs, sealedBytes, CallMessageCrypto.chunkCount(plain), audioOnly, now,
        callId?.takeIf(CallWire::isCallId))
    synchronized(lock) { saveLocked(c, PhoneOutbox.prune(loadLocked(c) + m, now)) }
    FileInputStream(take).use { CallMessageStore.keepSent(c, it, plain, id, phone, durationMs, audioOnly, m.callId, now) }
    // A parked background poll from that phone returns now and lists the message.
    CallPhoneMailbox.wake()
    Log.i(TAG, "message ${id.take(4)}… queued for a phone (${sealedBytes / 1024} KiB)")
    null
  }.getOrElse { Log.w(TAG, "phone message not queued: ${it.javaClass.simpleName}"); "store_failed" }

  /** The phone confirmed it holds the message: delete ours. */
  fun delivered(c: Context, phoneId: String, id: String): Boolean = synchronized(lock) {
    val app = c.applicationContext
    val list = loadLocked(app)
    val m = PhoneOutbox.find(list, phoneId, id) ?: return false
    file(app, m.id)?.delete()
    saveLocked(app, list - m)
    Log.i(TAG, "message ${id.take(4)}… collected by the phone")
    true
  }

  /** Drops expired entries and any file the index doesn't name (or vice versa). */
  fun prune(c: Context) = synchronized(lock) {
    val app = c.applicationContext
    val now = System.currentTimeMillis()
    val kept = PhoneOutbox.prune(loadLocked(app), now).filter { file(app, it.id)?.exists() == true }
    val names = kept.map { it.id + SEALED }.toSet()
    dir(app).listFiles()?.filter { it.name != INDEX && it.name !in names }?.forEach { it.delete() }
    saveLocked(app, kept)
  }

  private fun loadLocked(c: Context): List<PhoneOutMessage> =
      cache ?: PhoneOutbox.decode(runCatching { File(dir(c), INDEX).readText() }.getOrNull()).also { cache = it }

  private fun saveLocked(c: Context, list: List<PhoneOutMessage>) {
    cache = list
    val tmp = File(dir(c), "$INDEX.tmp")
    runCatching {
      tmp.writeText(PhoneOutbox.encode(list))
      if (!tmp.renameTo(File(dir(c), INDEX))) throw java.io.IOException("rename failed")
    }.onFailure { Log.w(TAG, "phone outbox index not saved: ${it.javaClass.simpleName}") }
  }
}

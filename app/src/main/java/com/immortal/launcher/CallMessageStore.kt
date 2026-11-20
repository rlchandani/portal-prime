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
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Video messages received by this Portal, plus its own copies of the ones it sent: sealed files under
 * `no_backup/hearthpane_vmsg` (blocked for the fleet file routes, excluded from backups), plus a
 * metadata index. Files are re-sealed at rest under the Keystore-wrapped data key as the upload
 * streams in; nothing is ever decrypted to disk. Also owns the sender's temp directory, which must
 * be empty whenever no message is being composed.
 */
internal object CallMessageStore {
  private const val TAG = "HearthpaneCallMsg"
  const val DIR = "hearthpane_vmsg"
  const val OUT_DIR = "hearthpane_vmsg_out"
  private const val INDEX = "index.json"
  private const val SEALED = ".hpv"
  private const val PART = ".part"
  private const val PRUNE_EVERY_MS = 6 * 60 * 60_000L

  private val lock = Any()
  private val inflight = HashSet<String>()
  private val mutable = MutableStateFlow<List<CallMessage>>(emptyList())
  val messages: StateFlow<List<CallMessage>> = mutable.asStateFlow()
  @Volatile private var app: Context? = null

  fun dir(c: Context): File = File(c.noBackupFilesDir, DIR).apply { mkdirs() }
  fun outDir(c: Context): File = File(c.noBackupFilesDir, OUT_DIR).apply { mkdirs() }
  fun file(c: Context, id: String): File? = if (CallMessageList.isId(id)) File(dir(c), id + SEALED) else null

  /** Loads once per process, prunes, removes leftovers, and keeps pruning every few hours. */
  fun ensureLoaded(context: Context) {
    if (app != null) return
    synchronized(lock) {
      if (app != null) return
      val c = context.applicationContext
      mutable.value = CallMessageList.decode(runCatching { String(AtomicFile(File(dir(c), INDEX)).readFully()) }.getOrNull())
      app = c
      pruneLocked(c)
      sweepOutgoing(c)
    }
    runCatching { CallPhoneOutbox.prune(context) }
    Handler(Looper.getMainLooper()).postDelayed(object : Runnable {
      override fun run() {
        app?.let { prune(it); CallPhoneOutbox.prune(it) }
        Handler(Looper.getMainLooper()).postDelayed(this, PRUNE_EVERY_MS)
      }
    }, PRUNE_EVERY_MS)
  }

  fun prune(context: Context) = synchronized(lock) { pruneLocked(context.applicationContext) }

  /**
   * Streams one verified upload into the store. [copyBody] copies the HTTP body into the given
   * stream. Returns null on success, a refusal before any byte is stored, or throws when the body
   * fails authentication (the partial file is deleted either way).
   */
  fun receive(context: Context, v: CallMessageWire.Verified, on: Boolean, now: Long, copyBody: (OutputStream) -> Long): CallMessageRefusal? {
    ensureLoaded(context)
    val c = context.applicationContext
    val put = v.put
    val plain = CallMessageCrypto.plainLength(put.sealedBytes)
    synchronized(lock) {
      CallMessageList.canAccept(mutable.value, put.id, plain, put.durationMs, dir(c).usableSpace, on, v.from)?.let { return it }
      // One upload at a time: the agent has four HTTP threads that calls and phones also need.
      if (inflight.isNotEmpty()) return CallMessageRefusal.BUSY
      inflight.add(put.id)
    }
    val part = File(dir(c), put.id + PART)
    try {
      val restKey = CallMessageCrypto.restKey(CallMessageKeys.dataKey(c, dir(c)), put.id)
      val transit = CallMessageCrypto.transitKey(requireNotNull(HouseholdKey.get(c)), put.id)
      FileOutputStream(part).use { fos ->
        val out = BufferedOutputStream(fos, CallMessageCrypto.SEALED_CHUNK)
        val opener = CallMessageCrypto.Opener(transit, put.id, put.sealedBytes) { i, final, bytes, len ->
          out.write(CallMessageCrypto.sealChunk(restKey, put.id, i, final, bytes, len))
        }
        copyBody(Deadline(opener, System.currentTimeMillis() + CallMessageLimits.UPLOAD_DEADLINE_MS))
        opener.finish()
        out.flush()
        fos.fd.sync()
      }
      val sealed = File(dir(c), put.id + SEALED)
      if (!part.renameTo(sealed)) throw java.io.IOException("rename failed")
      synchronized(lock) {
        save(c, CallMessageList.add(mutable.value, CallMessage(put.id, v.from, put.fromName, now, put.durationMs, sealed.length(),
            put.audioOnly, callId = put.callId), now))
      }
      Log.i(TAG, "message ${put.id.take(4)}… stored (${sealed.length() / 1024} KiB)")
      return null
    } finally {
      part.delete()
      synchronized(lock) { inflight.remove(put.id) }
    }
  }

  /**
   * After the recipient confirmed [id], keeps this Portal's own copy of the message it sent, sealed
   * at rest under this Portal's data key exactly like a received one (Recent plays it back). Reads
   * the take through [input] (opened before the upload, so it still works if the take was unlinked).
   * Best effort: a sent copy that can't be kept never fails the send.
   */
  fun keepSent(context: Context, input: java.io.InputStream, plainBytes: Long, id: String, to: CallPeer, durationMs: Long,
               audioOnly: Boolean, callId: String?, now: Long): Boolean {
    ensureLoaded(context)
    val c = context.applicationContext
    if (!CallMessageList.isId(id) || plainBytes <= 0 || plainBytes > CallMessageLimits.MAX_BYTES) return false
    val name = DinnerWire.cleanName(to.name) ?: return false
    if (!DinnerWire.isDeviceId(to.id)) return false
    synchronized(lock) {
      if (mutable.value.any { it.id == id } || id in inflight) return false
      if (dir(c).usableSpace - plainBytes < CallMessageLimits.MIN_FREE_BYTES) { Log.i(TAG, "sent copy skipped: low space"); return false }
      inflight.add(id)
    }
    val part = File(dir(c), id + PART)
    return try {
      val restKey = CallMessageCrypto.restKey(CallMessageKeys.dataKey(c, dir(c)), id)
      FileOutputStream(part).use { fos ->
        val out = BufferedOutputStream(fos, CallMessageCrypto.SEALED_CHUNK)
        CallMessageCrypto.seal(restKey, id, java.io.BufferedInputStream(input, CallMessageLimits.CHUNK), plainBytes, out)
        out.flush()
        fos.fd.sync()
      }
      val sealed = File(dir(c), id + SEALED)
      if (!part.renameTo(sealed)) throw java.io.IOException("rename failed")
      synchronized(lock) {
        save(c, CallMessageList.add(mutable.value, CallMessage(id, to.id, name, now, durationMs, sealed.length(), audioOnly,
            seen = true, outgoing = true, callId = callId?.takeIf(CallWire::isCallId)), now))
      }
      Log.i(TAG, "sent copy ${id.take(4)}… kept (${sealed.length() / 1024} KiB)")
      true
    } catch (e: Exception) {
      Log.w(TAG, "sent copy not kept: ${e.javaClass.simpleName}")
      false
    } finally {
      part.delete()
      synchronized(lock) { inflight.remove(id) }
    }
  }

  fun markSeen(context: Context, id: String) = synchronized(lock) {
    val next = CallMessageList.markSeen(mutable.value, id)
    if (next != mutable.value) save(context.applicationContext, next)
  }

  fun delete(context: Context, id: String) = synchronized(lock) {
    val c = context.applicationContext
    file(c, id)?.delete()
    CallMessagePosters.forget(id)
    save(c, mutable.value.filterNot { it.id == id })
  }

  /** Settings › Calls › Delete all messages: every file, the index, and the at-rest key. */
  fun deleteAll(context: Context) = synchronized(lock) {
    val c = context.applicationContext
    dir(c).listFiles()?.forEach { it.delete() }
    CallMessageKeys.forget(dir(c))
    CallMessagePosters.forgetAll()
    save(c, emptyList())
  }

  fun bytesUsed(context: Context): Long = dir(context).listFiles()?.filter { it.name.endsWith(SEALED) }?.sumOf { it.length() } ?: 0L

  /** No message is being composed when this runs: any sender temp file is a leftover. */
  fun sweepOutgoing(context: Context) {
    outDir(context).listFiles()?.forEach { f -> if (f.delete()) Log.i(TAG, "removed a leftover outgoing file") }
  }

  fun outgoingCount(context: Context): Int = outDir(context).listFiles()?.size ?: 0

  private fun pruneLocked(c: Context) {
    val kept = CallMessageList.prune(mutable.value, Calling.now())
    val keep = kept.map { it.id + SEALED }.toSet() + setOf(INDEX, "key.bin")
    dir(c).listFiles()?.forEach { f ->
      val busy = f.name.endsWith(PART) && f.name.removeSuffix(PART) in inflight
      if (f.name !in keep && !busy) f.delete()
    }
    // An index row whose file is gone is dropped too.
    save(c, kept.filter { File(dir(c), it.id + SEALED).exists() })
  }

  private fun save(c: Context, list: List<CallMessage>) {
    if (list == mutable.value && File(dir(c), INDEX).exists()) return
    mutable.value = list
    runCatching {
      val f = AtomicFile(File(dir(c), INDEX))
      val out = f.startWrite()
      try { out.write(CallMessageList.encode(list).toByteArray()); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out); throw e }
    }.onFailure { Log.w(TAG, "message index not saved", it) }
  }

  /** Fails the upload once its time is up, whatever the sender's pace. */
  private class Deadline(private val out: CallMessageCrypto.Opener, private val atMs: Long) : OutputStream() {
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
    override fun write(b: ByteArray, off: Int, len: Int) {
      if (System.currentTimeMillis() > atMs) throw CallMessageCrypto.Tampered("upload too slow")
      out.write(b, off, len)
    }
  }
}

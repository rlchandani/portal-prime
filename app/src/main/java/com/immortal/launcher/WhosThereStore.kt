/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log
import com.immortal.launcher.youtube.YtKeystoreSecureStore
import java.io.File
import java.security.KeyStore
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Household switches for Who's there? on this Portal (no biometric data here). */
internal data class WhosThereSettings(
    val enabled: Boolean = false,
    val pausedUntilMs: Long = 0L,
    val hidePrivateNear: Boolean = true,
    val consentAtMs: Long = 0L,
)

/**
 * Persistence for Who's there?. Templates and the people list live in ONE AES-256-GCM file
 * under a non-exportable Keystore key, in `noBackupFilesDir` (never backed up, never synced).
 * Switches are plain prefs; the audit is a plain counts-and-times file. All disk work runs on
 * one IO thread; readers get immutable snapshots from the [StateFlow]s.
 */
internal object WhosThereStore {
  private const val TAG = "HearthpaneWhosThere"
  private const val PREFS = "hearthpane_whos_there"
  private const val ALIAS = "hearthpane.whosthere.household"
  internal const val DIR = "whos_there"

  private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "whos-there-io").apply { isDaemon = true } }
  private val household = MutableStateFlow(WhosThereHousehold())
  private val settings = MutableStateFlow(WhosThereSettings())
  private val audit = MutableStateFlow<List<AuditEvent>>(emptyList())
  private val loadedFlow = MutableStateFlow(false)
  @Volatile private var app: Context? = null

  val householdState: StateFlow<WhosThereHousehold> = household.asStateFlow()
  val settingsState: StateFlow<WhosThereSettings> = settings.asStateFlow()
  val auditState: StateFlow<List<AuditEvent>> = audit.asStateFlow()
  val loaded: StateFlow<Boolean> = loadedFlow.asStateFlow()

  fun init(context: Context) {
    if (app != null) return
    app = context.applicationContext
    io.execute { load(context.applicationContext) }
  }

  private fun dir(c: Context) = File(c.noBackupFilesDir, DIR)
  private fun secure(c: Context) = YtKeystoreSecureStore(File(dir(c), "household.bin"), ALIAS)
  private fun auditFile(c: Context) = File(dir(c), "audit.txt")

  private fun load(c: Context) {
    val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    settings.value = WhosThereSettings(p.getBoolean("enabled", false), p.getLong("paused_until", 0L),
        p.getBoolean("hide_private_near", true), p.getLong("consent_at", 0L))
    household.value = runCatching { secure(c).load()?.let(WhosThereCodec::decode) }
        .onFailure { Log.w(TAG, "household file unreadable; starting empty (${it.javaClass.simpleName})") }
        .getOrNull() ?: WhosThereHousehold()
    audit.value = runCatching { auditFile(c).takeIf { it.isFile }?.readText()?.let(WhosThereAudit::decode) }
        .getOrNull().orEmpty().let { WhosThereAudit.prune(it, System.currentTimeMillis()) }
    loadedFlow.value = true
  }

  fun updateSettings(c: Context, edit: (WhosThereSettings) -> WhosThereSettings) {
    val next = synchronized(this) { edit(settings.value).also { settings.value = it } }
    io.execute {
      c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
          .putBoolean("enabled", next.enabled).putLong("paused_until", next.pausedUntilMs)
          .putBoolean("hide_private_near", next.hidePrivateNear).putLong("consent_at", next.consentAtMs).apply()
    }
  }

  /** Applies [edit] and writes the encrypted file. [onSaved] runs on the IO thread with the result. */
  fun updateHousehold(c: Context, edit: (WhosThereHousehold) -> WhosThereHousehold, onSaved: (Boolean) -> Unit = {}) {
    val next = synchronized(this) { edit(household.value).also { household.value = it } }
    io.execute {
      val ok = runCatching {
        if (next.people.isEmpty()) wipeFiles(c) else secure(c.applicationContext).save(WhosThereCodec.encode(next))
      }.onFailure { Log.w(TAG, "household save failed (${it.javaClass.simpleName})") }.isSuccess
      onSaved(ok)
    }
  }

  fun record(c: Context, kind: AuditKind, faces: Int = 0) {
    val now = System.currentTimeMillis()
    val next = synchronized(this) { WhosThereAudit.add(audit.value, AuditEvent(now, kind, faces), now).also { audit.value = it } }
    io.execute {
      runCatching {
        val f = auditFile(c.applicationContext)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(WhosThereAudit.encode(next))
        if (!tmp.renameTo(f)) tmp.delete()
      }.onFailure { Log.w(TAG, "audit save failed (${it.javaClass.simpleName})") }
    }
  }

  /** "Delete all recognition data on this Portal": people, templates, the key, and the audit. */
  fun deleteAll(c: Context, onDone: () -> Unit = {}) {
    synchronized(this) {
      household.value = WhosThereHousehold()
      audit.value = emptyList()
    }
    updateSettings(c) { it.copy(enabled = false, pausedUntilMs = 0L, consentAtMs = 0L) }
    io.execute {
      runCatching { wipeFiles(c.applicationContext); auditFile(c.applicationContext).delete() }
      onDone()
    }
  }

  private fun wipeFiles(c: Context) {
    secure(c).clear()
    runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
  }
}

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What asked for a run: the background tick installs only when idle; the two manual ones don't wait. */
internal enum class OtaTrigger { SCHEDULED, CHECK, INSTALL }

/**
 * Hearthpane's wireless updater (GitHub Releases feed, [OtaConstants.FEED_URL]). Checks at start and
 * every [OtaConstants.CHECK_INTERVAL_MS]; installs automatically only when [OtaIdlePolicy] allows.
 * Every install, manual or not, passes the manifest, size, SHA-256, package, version and pinned
 * signer checks first. Developer mode pauses it entirely unless a fleet operator forces a run, so
 * a pushed build is never overwritten. One run at a time on a private worker thread.
 */
internal object OtaUpdater {
  private const val TAG = "HearthpaneOta"
  private const val PREFS = "hearthpane_ota"
  private const val KEY_LAST_CHECK = "last_check_at"
  private const val KEY_AVAILABLE_CODE = "available_code"
  private const val KEY_AVAILABLE_SINCE = "available_since"
  private const val APK_DIR = "ota"
  private const val APK_NAME = "hearthpane.apk"

  private val mutable = MutableStateFlow(OtaState())
  val state: StateFlow<OtaState> = mutable.asStateFlow()

  private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, TAG).apply { isDaemon = true } }
  private val running = AtomicBoolean(false)
  private val started = AtomicBoolean(false)
  private val main by lazy { Handler(Looper.getMainLooper()) }
  @Volatile private var lastFetchElapsedMs: Long? = null
  @Volatile private var lastFetchFailed = false
  /** A version whose install failed is retried automatically only after the next fetch. */
  @Volatile private var autoInstallBlockedFor: Long? = null

  /** Arms the background tick once per process (called from [ImmortalApp]). */
  fun start(context: Context) {
    val app = context.applicationContext
    if (!started.compareAndSet(false, true)) return
    refresh(app)
    scheduleTick(app, OtaConstants.STARTUP_DELAY_MS)
  }

  /** Re-reads installed version, developer mode and the persisted last-check time. */
  fun refresh(context: Context): OtaState {
    val installed = OtaDevice.installedVersion(context)
    val lastCheck = prefs(context).getLong(KEY_LAST_CHECK, 0L).takeIf { it > 0L }
    mutable.update {
      it.copy(installedVersionCode = installed.code, installedVersionName = installed.name,
          devMode = DevMode.isEnabled(context), lastCheckAtMs = it.lastCheckAtMs ?: lastCheck)
    }
    return mutable.value
  }

  /** Starts a run, or returns null when one is already in progress. */
  fun submit(context: Context, trigger: OtaTrigger, force: Boolean = false): Future<OtaState>? {
    val app = context.applicationContext
    if (!running.compareAndSet(false, true)) return null
    return runCatching {
      worker.submit(Callable {
        try {
          run(app, trigger, force)
        } catch (t: Throwable) {
          Log.w(TAG, "update run failed", t)
          fail(OtaError.INSTALL)
        } finally {
          running.set(false)
        }
      })
    }.onFailure { running.set(false) }.getOrNull()
  }

  private fun run(context: Context, trigger: OtaTrigger, force: Boolean): OtaState {
    refresh(context)
    if (DevMode.isEnabled(context) && !force) {
      Log.i(TAG, "developer mode on; update run skipped")
      return mutable.value
    }
    // Without the installed version every build would look newer; never guess.
    if (mutable.value.installedVersionCode <= 0L) return fail(OtaError.INSTALL)
    // Manual runs always fetch; the 15-minute tick reuses the known manifest between fetches.
    val due = OtaSchedule.fetchDue(lastFetchElapsedMs, lastFetchFailed, SystemClock.elapsedRealtime())
    val manifest = (if (trigger != OtaTrigger.SCHEDULED || due) check(context) else mutable.value.available)
        ?: return mutable.value
    if (trigger == OtaTrigger.CHECK) return mutable.value
    if (trigger == OtaTrigger.SCHEDULED) {
      if (autoInstallBlockedFor == manifest.versionCode) return mutable.value
      val wait = OtaIdlePolicy.waitReason(OtaDevice.signals(context, pendingFor(context, manifest)), manual = false)
      mutable.update { it.copy(waitingFor = wait) }
      if (wait != null) return mutable.value
    }
    return install(context, manifest)
  }

  /** Fetches and validates the manifest; returns it only when a newer, valid build is published. */
  private fun check(context: Context): OtaManifest? {
    mutable.update { it.copy(phase = OtaPhase.CHECKING, error = null, waitingFor = null) }
    val installed = mutable.value.installedVersionCode
    val fetched = runCatching { OtaHttp.fetchManifest() }
        .onFailure { Log.w(TAG, "manifest fetch failed: ${it.javaClass.simpleName}") }
    lastFetchElapsedMs = SystemClock.elapsedRealtime()
    autoInstallBlockedFor = null
    val checkedAt = System.currentTimeMillis()
    prefs(context).edit().putLong(KEY_LAST_CHECK, checkedAt).apply()
    val json = fetched.getOrNull()
    val result = json?.let { OtaManifestParser.parse(it, context.packageName, installed, Build.VERSION.SDK_INT) }
    lastFetchFailed = json == null ||
        (result as? OtaManifestResult.Rejected)?.reason?.let(OtaIntegrity::errorFor) != null
    return when (result) {
      null -> null.also { publish(OtaPhase.FAILED, null, OtaError.NETWORK, checkedAt) }
      is OtaManifestResult.Valid -> result.manifest.also {
        Log.i(TAG, "update available: ${it.versionName} (${it.versionCode}), installed $installed")
        publish(OtaPhase.AVAILABLE, it, null, checkedAt)
      }
      is OtaManifestResult.Rejected -> null.also {
        val error = OtaIntegrity.errorFor(result.reason)
        if (error != null) Log.w(TAG, "manifest rejected: ${result.reason}")
        publish(if (error == null) OtaPhase.UP_TO_DATE else OtaPhase.FAILED, null, error, checkedAt)
      }
    }
  }

  private fun install(context: Context, manifest: OtaManifest): OtaState {
    if (InstallDaemon.installPaused(context)) return fail(OtaError.PAUSED)
    mutable.update { it.copy(phase = OtaPhase.DOWNLOADING, error = null, waitingFor = null) }
    val apk = File(File(context.filesDir, APK_DIR), APK_NAME)
    try {
      val downloaded = try {
        OtaHttp.downloadApk(manifest, apk)
      } catch (e: OtaIntegrityException) {
        Log.w(TAG, "download rejected: ${e.message}")
        return blocked(manifest, OtaError.INTEGRITY)
      } catch (e: IOException) {
        Log.w(TAG, "download failed: ${e.javaClass.simpleName}")
        return fail(OtaError.DOWNLOAD)
      }
      OtaIntegrity.checkDownload(manifest, downloaded.bytes, downloaded.sha256)?.let { return blocked(manifest, it) }
      OtaIntegrity.checkArchive(manifest, OtaDevice.archiveFacts(context, apk))?.let { return blocked(manifest, it) }
      mutable.update { it.copy(phase = OtaPhase.INSTALLING) }
      Log.i(TAG, "installing ${manifest.versionName} (${manifest.versionCode})")
      // On success Android replaces this process; reaching the lines below means it survived.
      if (!OtaDevice.install(context, apk)) return blocked(manifest, OtaError.INSTALL)
      refresh(context)
      mutable.update { it.copy(phase = OtaPhase.UP_TO_DATE, available = null) }
      return mutable.value
    } finally {
      if (apk.exists() && !apk.delete()) Log.w(TAG, "couldn't remove the downloaded APK")
    }
  }

  private fun blocked(manifest: OtaManifest, error: OtaError): OtaState {
    autoInstallBlockedFor = manifest.versionCode
    return fail(error)
  }

  private fun fail(error: OtaError): OtaState {
    mutable.update { it.copy(phase = OtaPhase.FAILED, error = error) }
    return mutable.value
  }

  private fun publish(phase: OtaPhase, available: OtaManifest?, error: OtaError?, checkedAt: Long) {
    mutable.update { it.copy(phase = phase, available = available, error = error, lastCheckAtMs = checkedAt) }
  }

  /** How long [manifest]'s version has been waiting, remembered across restarts. */
  private fun pendingFor(context: Context, manifest: OtaManifest): Long {
    val p = prefs(context)
    val now = System.currentTimeMillis()
    if (p.getLong(KEY_AVAILABLE_CODE, -1L) != manifest.versionCode) {
      p.edit().putLong(KEY_AVAILABLE_CODE, manifest.versionCode).putLong(KEY_AVAILABLE_SINCE, now).apply()
      return 0L
    }
    return (now - p.getLong(KEY_AVAILABLE_SINCE, now)).coerceAtLeast(0L)
  }

  private fun scheduleTick(context: Context, delayMs: Long) {
    val alarms = context.getSystemService(AlarmManager::class.java)
    val at = SystemClock.elapsedRealtime() + delayMs
    val listener = AlarmManager.OnAlarmListener {
      submit(context, OtaTrigger.SCHEDULED)
      scheduleTick(context, OtaConstants.TICK_MS)
    }
    val armed = alarms != null && runCatching {
      alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, TAG, listener, main)
    }.onFailure { Log.w(TAG, "update alarm not armed", it) }.isSuccess
    // Without AlarmManager the process-lifetime handler still keeps the schedule.
    if (!armed) main.postDelayed({ listener.onAlarm() }, delayMs)
  }

  private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

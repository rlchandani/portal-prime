/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Installs an APK via [PackageInstaller] with NO foreground UI of our own: when the
 * system asks for user confirmation we launch its dialog and let
 * [InstallConfirmService] tap the button. This is the fleet agent's install path
 * on devices where the silent shell daemon isn't running (e.g. after a reboot).
 *
 * Blocks the calling (worker) thread until the install reaches a terminal state or the
 * result timeout (sized to the APK, see [HeadlessInstallPolicy.resultTimeoutMs]) elapses.
 * Launching the confirm dialog from the background relies on Immortal holding
 * `SYSTEM_ALERT_WINDOW` (the background-activity-start exemption); without it the dialog
 * won't appear and the install times out — which the agent surfaces as a failure rather
 * than a silent hang.
 *
 * The outcome comes only from the session result. While waiting, the screen is held on (so the
 * dream can't start over the dialog) and, if the service never got to tap a ready button, the
 * confirmation is shown again in a fresh installer task.
 */
object HeadlessInstaller {
  private const val TAG = "ImmortalFleet"
  private const val ACTION = "com.immortal.launcher.FLEET_INSTALL_RESULT"
  /** The arm window and screen hold outlive the result wait slightly, never the other way round. */
  private const val HOLD_SLACK_MS = 10_000L

  /** Sessions with a live waiter in this process; everything else we own is an orphan. */
  private val liveSessions: MutableSet<Int> = ConcurrentHashMap.newKeySet()

  fun install(context: Context, apk: File, pkg: String, timeoutMs: Long? = null): Boolean =
      installForResult(context, apk, pkg, timeoutMs).ok

  internal fun installForResult(
      context: Context,
      apk: File,
      pkg: String,
      timeoutMs: Long? = null,
  ): HeadlessInstallOutcome {
    val app = context.applicationContext
    val waitMs = timeoutMs ?: HeadlessInstallPolicy.resultTimeoutMs(apk.length())
    val latch = CountDownLatch(1)
    val result = AtomicReference<HeadlessInstallOutcome?>(null)
    val ok = AtomicBoolean(false)
    val ownedSessionId = AtomicInteger(-1)
    val confirmation = AtomicReference<Intent?>(null)
    val presentations = AtomicInteger(0)
    val screen = AtomicReference<PowerManager.WakeLock?>(null)
    val present: (Intent?) -> Unit = { confirm ->
      presentations.incrementAndGet()
      runCatching {
        InstallerNavigation.launchConfirmation(app, confirm) {
          if (screen.get() == null) screen.set(holdScreen(app, waitMs + HOLD_SLACK_MS))
        }
      }.onFailure { Log.w(TAG, "couldn't launch installer dialog for $pkg", it) }
      Unit
    }
    val receiver =
        object : BroadcastReceiver() {
          override fun onReceive(c: Context, intent: Intent) {
            // Different package installs can overlap, but their result action is shared.
            val sessionId = ownedSessionId.get()
            if (sessionId < 0 ||
                intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != sessionId) return
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
              PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm =
                    if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirmation.set(confirm)
                // The receiver runs on the main thread: exit our dream before showing the
                // system dialog, so InstallConfirmService can reach its "Install" button.
                present(confirm)
              }
              PackageInstaller.STATUS_SUCCESS -> {
                ok.set(true)
                result.compareAndSet(null, HeadlessInstallOutcome.Installed)
                latch.countDown()
              }
              else -> { // any failure/abort is terminal
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                result.compareAndSet(null, HeadlessInstallOutcome.Failed(status, message))
                latch.countDown()
              }
            }
          }
        }

    val filter = IntentFilter(ACTION)
    if (Build.VERSION.SDK_INT >= 33)
        app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(receiver, filter)

    // The auto-confirm window is armed only once the session is staged, for the whole result
    // wait, so it taps "Install" on THIS install's dialog. Disarmed in `finally` so it can never
    // dismiss an interactive install the user later triggers themselves.
    var confirmationLease: InstallAutoConfirmWindow.Lease? = null
    val outcome = try {
      // This experiment is restricted to our own package on the profile format's supported APIs.
      // Prepare before opening a session: invalid metadata must fail, never silently measure APK-only.
      val dexMetadata =
          if (BuildConfig.PORTAL_SELF_INSTALL_DEX_METADATA && Build.VERSION.SDK_INT in 28..30 &&
              pkg == app.packageName &&
              SelfInstallDexMetadata.matchesTarget(apk, BuildConfig.PORTAL_SELF_INSTALL_DEX_METADATA_APK_SHA256)) {
            val archivePackage = app.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName
            require(
                SelfInstallDexMetadata.eligible(
                    true, Build.VERSION.SDK_INT, pkg, app.packageName, archivePackage)) {
                  "Invalid self-install package"
                }
            SelfInstallDexMetadata.prepareRequired(apk)
          } else null
      val pi = app.packageManager.packageInstaller
      val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
      val sessionId = pi.createSession(params).also(ownedSessionId::set)
      liveSessions.add(sessionId)
      pi.openSession(sessionId).use { session ->
        session.openWrite("base.apk", 0, apk.length()).use { out ->
          apk.inputStream().use { it.copyTo(out) }
          session.fsync(out)
        }
        if (dexMetadata != null) {
          // Android pairs dex metadata with the staged APK by basename in the same session.
          session.openWrite("base.dm", 0, dexMetadata.size.toLong()).use { out ->
            out.write(dexMetadata)
            session.fsync(out)
          }
        }
        val flags =
            if (Build.VERSION.SDK_INT >= 31)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
        val pending =
            PendingIntent.getBroadcast(
                app, sessionId, Intent(ACTION).setPackage(app.packageName), flags)
        confirmationLease = InstallAutoConfirm.arm(waitMs + HOLD_SLACK_MS)
        session.commit(pending.intentSender)
      }
      Log.i(TAG, "committed install of $pkg (session $sessionId, ${apk.length() / 1024} KiB); " +
          "waiting up to ${waitMs / 1000}s for its result")
      await(latch, waitMs, requireNotNull(confirmationLease), confirmation, presentations, present)
      result.get() ?: HeadlessInstallOutcome.ConfirmTimeout(
          waitMs, presentations.get(), confirmationLease?.let(InstallAutoConfirm::tapped) == true)
    } catch (t: Throwable) {
      Log.w(TAG, "headless install of $pkg failed", t)
      HeadlessInstallOutcome.Error(t.javaClass.simpleName + (t.message?.let { ": $it" } ?: ""))
    } finally {
      // Closing the handle does not delete Android's staged install. A failed or timed-out
      // attempt must abandon its own session, without sweeping other pending installs.
      val sessionId = ownedSessionId.get()
      if (!ok.get() && sessionId >= 0) {
        runCatching { app.packageManager.packageInstaller.abandonSession(sessionId) }
            .onFailure { Log.w(TAG, "couldn't abandon failed installer session $sessionId", it) }
      }
      liveSessions.remove(sessionId)
      confirmationLease?.let(InstallAutoConfirm::disarm)
      screen.getAndSet(null)?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
      runCatching { app.unregisterReceiver(receiver) }
    }
    val line = "headless install of $pkg (session ${ownedSessionId.get()}): " +
        HeadlessInstallPolicy.describe(outcome)
    if (outcome.ok) Log.i(TAG, line) else Log.w(TAG, line)
    return outcome
  }

  /**
   * Abandons sessions this app owns that no live install in this process is waiting on. Call
   * once at process start only (before any install can begin): a session whose waiter died with
   * the previous process — e.g. the launcher replaced over adb mid-confirmation — can never be
   * confirmed or cleaned up otherwise, and keeps its staged APK on /data.
   */
  fun abandonOrphanedSessions(context: Context): Int = runCatching {
    val pi = context.applicationContext.packageManager.packageInstaller
    val orphans = HeadlessInstallPolicy.orphans(pi.mySessions.map { it.sessionId }, liveSessions)
    orphans.count { id ->
      runCatching { pi.abandonSession(id) }
          .onSuccess { Log.i(TAG, "abandoned orphaned installer session $id from an earlier process") }
          .onFailure { Log.w(TAG, "couldn't abandon orphaned installer session $id", it) }
          .isSuccess
    }
  }.onFailure { Log.w(TAG, "couldn't list installer sessions", it) }.getOrDefault(0)

  /** Waits in slices; re-shows the confirmation when the service never got a ready button. */
  private fun await(
      latch: CountDownLatch,
      waitMs: Long,
      lease: InstallAutoConfirmWindow.Lease,
      confirmation: AtomicReference<Intent?>,
      presentations: AtomicInteger,
      present: (Intent?) -> Unit,
  ) {
    var waited = 0L
    while (waited < waitMs) {
      val slice = minOf(HeadlessInstallPolicy.REPRESENT_AFTER_MS, waitMs - waited)
      if (latch.await(slice, TimeUnit.MILLISECONDS)) return
      waited += slice
      val confirm = confirmation.get()
      if (waited < waitMs && HeadlessInstallPolicy.shouldRepresent(
              confirm != null, InstallAutoConfirm.tapped(lease), presentations.get())) {
        Log.i(TAG, "installer dialog not confirmed after ${waited / 1000}s; showing it again")
        // Dream navigation and the owned-dream bridge are main-thread state.
        runCatching { Handler(Looper.getMainLooper()).post { present(confirm) } }
            .onFailure { Log.w(TAG, "couldn't re-show the installer dialog", it) }
      }
    }
  }

  /**
   * Wakes from a dream we don't own (or one still starting) and keeps the screen from napping
   * while the dialog waits: a dream on top pauses the installer, which disables its button.
   */
  @Suppress("DEPRECATION") // The only app-level way to wake from a dream on Android 10.
  private fun holdScreen(context: Context, timeoutMs: Long): PowerManager.WakeLock? = runCatching {
    context.getSystemService(PowerManager::class.java)
        ?.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "Immortal:installConfirm")
        ?.apply {
          setReferenceCounted(false)
          acquire(timeoutMs)
        }
  }.onFailure { Log.w(TAG, "couldn't hold the screen for the installer dialog", it) }.getOrNull()
}

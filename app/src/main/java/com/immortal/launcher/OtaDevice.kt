/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.TimeZone

/** Thin Android reads and the install hand-off for [OtaUpdater]; all decisions live in pure code. */
internal object OtaDevice {
  private const val TAG = "HearthpaneOta"
  private const val SIGNING_INFO_SDK = 28

  data class InstalledVersion(val code: Long, val name: String)

  fun installedVersion(context: Context): InstalledVersion = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    InstalledVersion(versionCode(info), info.versionName.orEmpty())
  }.onFailure { Log.w(TAG, "couldn't read the installed version", it) }.getOrDefault(InstalledVersion(0, ""))

  /** Package, version and signer digests of the downloaded APK, or null when it can't be parsed. */
  fun archiveFacts(context: Context, apk: File): OtaArchiveFacts? = runCatching {
    val pm = context.packageManager
    val modern = if (Build.VERSION.SDK_INT >= SIGNING_INFO_SDK)
        pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) else null
    val modernSigners = modern?.let(::currentSigners).orEmpty()
    // Some Android builds leave signingInfo empty for archives; the legacy field is equivalent here.
    val info = if (modern != null && modernSigners.isNotEmpty()) modern
        else @Suppress("DEPRECATION") pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
    info ?: return@runCatching null
    val signers = if (info === modern) modernSigners
        else @Suppress("DEPRECATION") info.signatures.orEmpty().toList()
    OtaArchiveFacts(info.packageName, versionCode(info), signers.map { OtaIntegrity.sha256Hex(it.toByteArray()) })
  }.onFailure { Log.w(TAG, "couldn't read the downloaded APK", it) }.getOrNull()

  /** Same signals the Music share sheet uses, plus the launcher's last touch. */
  fun signals(context: Context, pendingForMs: Long): OtaIdleSignals {
    val now = System.currentTimeMillis()
    val zone = TimeZone.getDefault()
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    val musicActive = runCatching { audio?.isMusicActive == true }.getOrDefault(false)
    val lastTouch = SleepScheduler.lastInteractionElapsedMs()
    val quiet = runCatching { DinnerQuiet.isQuiet(ChimeConfig.load(context), now, zone) }.getOrDefault(false)
    return OtaIdleSignals(
        musicPlaying = musicActive || NowPlayingHub.current?.state == PlaybackState.PLAYING,
        inCall = RoutineScheduler.busy(context),
        routineOnScreen = RoutineScreenState.ownsScreen(),
        sinceTouchMs = if (lastTouch > 0L) SystemClock.elapsedRealtime() - lastTouch else null,
        minuteOfDay = DinnerQuiet.minuteOfDay(now, zone),
        quietHours = quiet,
        pendingForMs = pendingForMs,
    )
  }

  /** Same path as a fleet file install: the shell daemon when it runs, else [HeadlessInstaller]. */
  fun install(context: Context, apk: File): Boolean {
    val pkg = context.packageName
    return if (InstallDaemon.installPlan(context).mode == InstallLifecycle.MODE_SILENT)
        InstallDaemon.install(context, apk, "hearthpane-ota")
    else HeadlessInstaller.install(context, apk, pkg)
  }

  private fun currentSigners(info: PackageInfo): List<Signature> =
      if (Build.VERSION.SDK_INT >= SIGNING_INFO_SDK) info.signingInfo?.apkContentsSigners.orEmpty().toList()
      else emptyList()

  private fun versionCode(info: PackageInfo): Long =
      if (Build.VERSION.SDK_INT >= SIGNING_INFO_SDK) info.longVersionCode
      else @Suppress("DEPRECATION") info.versionCode.toLong()
}

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import org.json.JSONObject

/**
 * The fleet API's view of the app: each member is one call into Context, PackageManager,
 * preferences or a subsystem. No routing, checks or response shaping here (see [FleetRoutes]).
 */
internal class FleetAndroidHost(private val context: Context) :
    FleetDevice, FleetSettings, FleetPackages, FleetActions, FleetPerf {

  private val main by lazy { Handler(Looper.getMainLooper()) }

  // --- FleetDevice ------------------------------------------------------------

  override fun token(): String = FleetConfig.token(context)
  override fun name(): String = FleetConfig.name(context)
  override fun port(): Int = FleetConfig.port(context)
  override fun model(): String = Build.MODEL ?: ""
  override fun device(): String = Build.DEVICE ?: ""
  override fun apiLevel(): Int = Build.VERSION.SDK_INT

  override fun appVersion(): Pair<Long, String> =
      runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            val code =
                if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode
                else @Suppress("DEPRECATION") pi.versionCode.toLong()
            code to (pi.versionName ?: "")
          }
          .getOrDefault(0L to "")

  /** Current LAN IPv4, preferring the wlan interface (computed fresh — DHCP-safe). */
  override fun lanIp(): String? =
      runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .sortedByDescending { it.name.startsWith("wlan") }
                .flatMap { Collections.list(it.inetAddresses) }
                .firstOrNull {
                  it is Inet4Address &&
                      !it.isLoopbackAddress &&
                      (it.isSiteLocalAddress || it.isLinkLocalAddress)
                }
                ?.hostAddress
          }
          .getOrNull()

  override fun presence(): PresenceState = PresenceHub.current
  override fun devMode(): Boolean = DevMode.isEnabled(context)
  override fun setDevMode(on: Boolean) = DevMode.setEnabled(context, on)
  override fun canWriteSecureSettings(): Boolean = SettingsGuard.canWriteSecureSettings(context)
  override fun installConfirmEnabled(): Boolean = SettingsGuard.isInstallConfirmEnabled(context)

  override fun canReadLogs(): Boolean =
      context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

  /** `/diag` samples now; `/info` (the dashboard poll) reuses the 15-minute sample. Never fails the call. */
  override fun memoryHealth(fresh: Boolean): JSONObject = runCatching {
    MemoryHealth.toJson(if (fresh) MemoryHealth.sampleNow() else MemoryHealth.latestOrSample())
  }.getOrElse {
    Log.w("ImmortalFleet", "memory health unavailable", it)
    MemoryHealth.toJson(null)
  }

  override fun diagSnapshot(): JSONObject = FleetDiag.snapshot(context)
  override fun logcat(lines: Int): String = FleetDiag.logcat(lines)

  // --- FleetSettings ----------------------------------------------------------

  override fun setName(name: String) = FleetConfig.setName(context, name)
  override fun setConfigValue(key: String, value: String) = FleetConfig.setValue(context, key, value)
  override fun configValues(): Map<String, String> = FleetConfig.allValues(context)
  override fun calendarJson(): JSONObject = FleetCalendar.toJson(ScreensaverConfig.load(context))
  override fun applyCalendar(body: JSONObject): List<String> = FleetCalendar.apply(context, body)
  override fun screensaverJson(): JSONObject = FleetScreensaver.toJson(ScreensaverConfig.load(context))

  override fun applyScreensaver(body: JSONObject): List<String> =
      FleetScreensaver.apply(context, body).also { SettingsGuard.afterScreensaverApply(context, it) }

  // --- FleetPackages ----------------------------------------------------------

  override fun installPlan(): InstallLifecycle.Plan = InstallDaemon.installPlan(context)
  override fun installedVersions(packageNames: Set<String>) = InstalledPackageVersions.snapshot(context, packageNames)
  override fun installedVersionCode(packageName: String) = StoreCatalog.installedVersionCode(context, packageName)
  override fun isInstalled(packageName: String) = StoreCatalog.isInstalled(context, packageName)
  override fun invalidateInstalledVersions() = InstalledPackageVersions.invalidate()

  override fun archivePackageName(path: String): String? =
      runCatching { context.packageManager.getPackageArchiveInfo(path, 0)?.packageName }.getOrNull()

  override fun startStoreInstall(app: CatalogApp, status: (String) -> Unit) =
      StoreCatalog.install(context, app) { _, msg -> status(msg) }

  override fun daemonInstall(apk: File, packageName: String) = InstallDaemon.install(context, apk, packageName)
  override fun dialogInstall(apk: File, packageName: String) = HeadlessInstaller.installForResult(context, apk, packageName)
  override fun download(app: CatalogApp, dest: File) = StoreCatalog.download(StoreCatalog.resolveApkUrl(app), dest, app.downloadHosts)
  override fun downloadFile(packageName: String) = File(context.cacheDir, "$packageName.fleet.apk")

  override fun startUpdateCheck(apps: List<CatalogApp>, done: (Map<String, Long>) -> Unit): () -> Unit =
      StoreCatalog.findUpdates(context, apps, onResult = done)

  // --- FleetActions -----------------------------------------------------------

  override fun reaffirm() {
    SettingsGuard.reaffirmAdb(context)
    SettingsGuard.reaffirmScreensaver(context)
  }

  override fun identify() {
    main.post {
      runCatching {
        Toast.makeText(context, "Hearthpane Fleet: ${FleetConfig.name(context)}", Toast.LENGTH_LONG).show()
      }
    }
  }

  override fun settingsSync(op: String) {
    when (op) {
      "pause" -> SettingsSync.setPaused(true)
      "resume" -> SettingsSync.setPaused(false)
      "adopt" -> SettingsSync.resolveJoin(keepMine = false)
      "keep" -> SettingsSync.resolveJoin(keepMine = true)
      "rejoin" -> SettingsSync.rejoin()
    }
  }

  override fun settingsSyncStatus(): JSONObject = SettingsSync.statusJson()
  override fun settingsSyncValues(): JSONObject = SettingsSync.valuesJson()

  override fun haAdopt(from: String, replace: Boolean): HaAdoption.Outcome = HaShare.adoption(context).adopt(from, replace)

  override fun haPeers(): org.json.JSONArray = org.json.JSONArray().also { list ->
    HaShare.adoption(context).offers().forEach { list.put(JSONObject().put("id", it.peer.id).put("name", it.name)) }
  }

  override fun openControlCenter(screen: String): Boolean {
    val intent = ControlCenterActivity.intent(context).putExtra(ControlCenterActivity.EXTRA_SCREEN, screen)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    return runCatching { context.startActivity(intent) }.isSuccess
  }

  override fun remoteEnabled(): Boolean = RemotePairing.isEnabled(context)
  override fun mintTestSession(householdGrant: Boolean) = RemotePairing.mintTestSession(context, householdGrant = householdGrant)
  override fun newTestPin() = RemotePairing.newPin(forTest = true)
  override fun revokeSession(id: String) = RemotePairing.revokeSession(context, id)
  override fun ageTestSessions(ms: Long) = RemotePairing.ageTestSessions(context, ms)
  override fun revokeTestSessions() = RemotePairing.revokeTestSessions(context)

  override fun ccDebug(dryRun: Boolean?, testPlan: String?, testAlert: String?, haUnreachable: Boolean?, fake: CcFakeState?) =
      ControlCenter.debug(context, dryRun, testPlan, testAlert, haUnreachable, fake)
  override fun ccShowTest(kind: String) = CcOverlayTest.show(context, kind)

  override fun testSourceAvailable() = ScreensaverTestSource.available()
  override fun enableTestSource(minutes: Int, night: Boolean, pin: ScreensaverTestSource.Pin?) =
      ScreensaverTestSource.enable(context, minutes, night = night, pin = pin)
  override fun disableTestSource() = ScreensaverTestSource.disable(context)
  override fun testSourceActive() = ScreensaverTestSource.active(context)
  override fun testSourceNight() = ScreensaverTestSource.forcedNight(context)
  override fun testSourceUntil() = ScreensaverTestSource.until(context)
  override fun testSourcePin() = ScreensaverTestSource.pin(context)?.let {
    "${it.mode}@${it.intervalSec}s" + (it.face?.let { face -> "+$face" } ?: "")
  }

  override fun reopenFrame(enabled: Boolean) {
    main.post { runCatching { ImmortalDreamNavigation.dismissForNavigation() } }
    // The singleTask frame must finish before a fresh one is created on the new source.
    if (enabled) main.postDelayed({
      runCatching {
        context.startActivity(Intent(context, PhotoFramePreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      }.onFailure { Log.w("FleetRoutes", "test frame launch failed", it) }
    }, FRAME_REOPEN_DELAY_MS)
  }

  override fun clockLook(): FleetClockLook {
    val s = DigitalClockConfig.load(context)
    return FleetClockLook(s.style, s.font, s.color, s.glow, DigitalClockConfig.faceOf(s))
  }

  override fun isClockFace(id: String) = DigitalClockConfig.FACES.any { it.id == id }
  override fun setClockStyle(style: String) = DigitalClockConfig.setStyle(context, style)
  override fun setClockFont(font: String) = DigitalClockConfig.setFont(context, font)
  override fun setClockColor(color: String) = DigitalClockConfig.setColor(context, color)
  override fun setClockGlow(glow: String) = DigitalClockConfig.setGlow(context, glow)
  override fun setClockFace(face: String) = DigitalClockConfig.setFace(context, face)

  override fun openClockPreview() {
    main.post {
      runCatching {
        context.startActivity(Intent(context, DigitalClockPreviewActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
      }
    }
  }

  override fun whosThereDebug(body: JSONObject): JSONObject = WhosThereDebug.apply(context, body)
  override fun jarvisMemoryDebug(body: JSONObject): JSONObject =
      com.immortal.launcher.memory.MemoryDebug.apply(context, body)
  override fun isSettingsPage(page: String) = SettingsTree.contains(page)

  override fun openScreen(screen: String, page: String?): Boolean {
    val target = SCREEN_ACTIVITIES[screen] ?: return false
    return runCatching {
      val intent = Intent(context, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      page?.let { intent.putExtra(APPROVED_SETTINGS_PAGE, it) }
      context.startActivity(intent)
    }.isSuccess
  }

  override fun orientation(request: String?): JSONObject = RotationFollower.operator(context, request)

  // --- FleetPerf --------------------------------------------------------------

  override fun snapshot(): JSONObject = FramePerformanceCapture.snapshot()
  override fun stateSnapshot(): JSONObject = FramePerformanceCapture.stateSnapshot()
  override fun readinessSnapshot(): JSONObject = FramePerformanceCapture.readinessSnapshot()
  override fun start(command: FramePerformanceRequest): Boolean? =
      FilmstripScreenshot.withPerformanceStart { FramePerformanceCapture.start(command) }
  override fun stop(): Boolean = FramePerformanceCapture.stop()
  override fun homeDiagnosticsEnabled() = BuildConfig.PORTAL_HOME_UI_DIAGNOSTICS
  override fun recentApps(): JSONObject = RecentAppPreparationDiagnostics.snapshot()
  override fun webAppStartup(): JSONObject = WebAppOpeningDiagnostics.snapshot()
  override fun toolsDiscovery(): JSONObject = ToolsDiscoveryDiagnostics.snapshot()
  override fun remoteInput(): JSONObject = RemoteInput.gestureDiagnostics()
  override fun filmstripRender(): FleetHttpServer.Response = FilmstripScreenshot.render(context)

  private companion object {
    const val FRAME_REOPEN_DELAY_MS = 800L

    /** [FleetActionApi.SCREENS], each to its Activity. */
    val SCREEN_ACTIVITIES: Map<String, Class<*>> = mapOf(
        "calls" to CallsActivity::class.java,
        FleetActionApi.SETTINGS_SCREEN to ImmortalSettingsActivity::class.java,
        "help" to HelpActivity::class.java,
        "household_list" to HouseholdListActivity::class.java,
        "ha_dashboard" to HaDashboardActivity::class.java,
        "lamp" to LampActivity::class.java,
        "guest_card" to GuestCardActivity::class.java,
        "store" to StoreActivity::class.java,
        "web_app" to WebAppActivity::class.java,
        "whos_there_enrol" to WhosThereEnrolActivity::class.java)

    init {
      check(SCREEN_ACTIVITIES.keys == FleetActionApi.SCREENS) { "open_screen table out of sync" }
    }
  }
}

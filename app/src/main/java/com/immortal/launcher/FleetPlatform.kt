/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import java.io.OutputStream
import org.json.JSONObject

/*
 * The Android pieces the fleet API uses, as interfaces, so its routing, checks and responses run
 * in JVM tests. [FleetRoutes] implements them over Context, PackageManager, preferences and the
 * app's subsystems; each implementation is a one-line call with no decisions of its own.
 */

/** Device identity, permissions and state (Context, Build, preferences, network). */
internal interface FleetDevice {
  fun token(): String
  fun name(): String
  fun port(): Int
  fun model(): String
  fun device(): String
  fun apiLevel(): Int
  /** (versionCode, versionName) of this app, or (0, "") when unknown. */
  fun appVersion(): Pair<Long, String>
  fun lanIp(): String?
  fun presence(): PresenceState
  fun devMode(): Boolean
  fun setDevMode(on: Boolean)
  fun canWriteSecureSettings(): Boolean
  fun installConfirmEnabled(): Boolean
  fun canReadLogs(): Boolean
  /** Never throws: an unavailable sample is reported as such. */
  fun memoryHealth(fresh: Boolean): JSONObject
  fun diagSnapshot(): JSONObject
  fun logcat(lines: Int): String
}

/** Fleet config and the screensaver/calendar settings (preferences). */
internal interface FleetSettings {
  fun setName(name: String)
  fun setConfigValue(key: String, value: String)
  fun configValues(): Map<String, String>
  fun calendarJson(): JSONObject
  fun applyCalendar(body: JSONObject): List<String>
  fun screensaverJson(): JSONObject
  /** Applies the push and reaffirms screensaver ownership for what changed. */
  fun applyScreensaver(body: JSONObject): List<String>
}

/** Package state and installs (PackageManager, the store, the install daemon). */
internal interface FleetPackages {
  fun installPlan(): InstallLifecycle.Plan
  fun installedVersions(packageNames: Set<String>): InstalledPackageVersionSnapshot?
  fun installedVersionCode(packageName: String): Long?
  fun isInstalled(packageName: String): Boolean
  fun invalidateInstalledVersions()
  /** The package an APK on the device declares, or null. */
  fun archivePackageName(path: String): String?
  /** Start the store's daemon install; [status] receives every store status message. */
  fun startStoreInstall(app: CatalogApp, status: (String) -> Unit)
  fun daemonInstall(apk: File, packageName: String): Boolean
  fun dialogInstall(apk: File, packageName: String): HeadlessInstallOutcome
  fun download(app: CatalogApp, dest: File)
  fun downloadFile(packageName: String): File
  /** Start an update check; [done] gets latest versionCode by package. Returns a cancel. */
  fun startUpdateCheck(apps: List<CatalogApp>, done: (Map<String, Long>) -> Unit): () -> Unit
}

/** File IO for the `/fs` routes. */
internal interface FleetFiles {
  fun isPrivate(file: File): Boolean
  fun list(path: String): JSONObject
  fun isReadableFile(file: File): Boolean
  fun read(file: File): FleetHttpServer.Response
  /** Free bytes on the volume that will hold [file]. */
  fun usableSpaceFor(file: File): Long
  fun write(file: File, body: (OutputStream) -> Long): Long
}

/** The real filesystem. Pure java.io (no Context). */
internal object JvmFleetFiles : FleetFiles {
  override fun isPrivate(file: File) = FleetSecretPaths.isPrivate(file)
  override fun list(path: String) = FleetFs.list(path)
  override fun isReadableFile(file: File) = file.isFile && file.canRead()
  override fun read(file: File) = FleetFileRead.response(file)
  override fun write(file: File, body: (OutputStream) -> Long) = FleetUploadWriter.write(file, body)

  /** The nearest existing ancestor's volume. */
  override fun usableSpaceFor(file: File): Long {
    var dir: File? = file.absoluteFile.parentFile
    while (dir != null && !dir.exists()) dir = dir.parentFile
    return dir?.usableSpace ?: 0L
  }
}

/** The digital clock's look, as stored. */
internal data class FleetClockLook(val style: String, val font: String, val color: String, val glow: String, val face: String)

/** Operator actions and test hooks behind `POST /action`. */
internal interface FleetActions {
  fun reaffirm()
  fun identify()
  fun settingsSync(op: String)
  fun settingsSyncStatus(): JSONObject
  fun settingsSyncValues(): JSONObject
  /** Adopt household Portal [from]'s Home Assistant connection (blocking; never exposes the token). */
  fun haAdopt(from: String, replace: Boolean): HaAdoption.Outcome
  /** The household Portals connected to Home Assistant: `[{id, name}]` (blocking; no secrets). */
  fun haPeers(): org.json.JSONArray
  fun openControlCenter(screen: String): Boolean
  fun remoteEnabled(): Boolean
  fun mintTestSession(householdGrant: Boolean): String
  fun newTestPin(): String
  fun revokeSession(id: String): Boolean
  fun ageTestSessions(ms: Long): Int
  fun revokeTestSessions(): Int
  fun ccDebug(dryRun: Boolean?, testPlan: String?, testAlert: String?, haUnreachable: Boolean?, fake: CcFakeState?): String
  /** `cc_debug` `showTest` ([CcOverlayTest.KINDS]): a silent held overlay, for the unbind/update checks. Dry run only. */
  fun ccShowTest(kind: String): String = "unsupported"
  fun testSourceAvailable(): Boolean
  /** [pin] holds one screensaver mode at a fixed interval for the same span (performance A/B). */
  fun enableTestSource(minutes: Int, night: Boolean, pin: ScreensaverTestSource.Pin? = null): Boolean
  fun disableTestSource(): Boolean
  /** Retire the frame on screen and, when [enabled], open a fresh one on the new source. */
  fun reopenFrame(enabled: Boolean)
  fun testSourceActive(): Boolean
  fun testSourceNight(): Boolean
  fun testSourceUntil(): Long
  /** The active pin as "mode@<n>s", or null. */
  fun testSourcePin(): String?
  fun clockLook(): FleetClockLook
  fun isClockFace(id: String): Boolean
  fun setClockStyle(style: String)
  fun setClockFont(font: String)
  fun setClockColor(color: String)
  fun setClockGlow(glow: String)
  fun setClockFace(face: String)
  fun openClockPreview()
  fun whosThereDebug(body: JSONObject): JSONObject
  fun isSettingsPage(page: String): Boolean
  /** Open one of [FleetActionApi.SCREENS], optionally on a Settings [page]. */
  fun openScreen(screen: String, page: String?): Boolean
  fun orientation(request: String?): JSONObject
}

/** Frame-performance capture and the gated home diagnostics. */
internal interface FleetPerf {
  fun snapshot(): JSONObject
  fun stateSnapshot(): JSONObject
  fun readinessSnapshot(): JSONObject
  /** Null when the filmstrip is busy; may throw [FramePerformanceCommandPending]. */
  fun start(command: FramePerformanceRequest): Boolean?
  /** May throw [FramePerformanceCommandPending]. */
  fun stop(): Boolean
  fun homeDiagnosticsEnabled(): Boolean
  fun recentApps(): JSONObject
  fun webAppStartup(): JSONObject
  fun toolsDiscovery(): JSONObject
  fun remoteInput(): JSONObject
  fun filmstripRender(): FleetHttpServer.Response
}

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject
import com.immortal.launcher.settings.SettingsRegistry

internal object RemoteScenes {
  fun validate(scenes: JSONArray): RemoteCompanionValidation = RemoteScenePolicy.validate(scenes) { id, values ->
    SettingsRegistry.domain(id)?.let { RemoteSnapshotPolicy.validateValues(it, values) } == true
  }

  fun start(c: Context, scene: JSONObject, scheduled: Boolean = false): JSONObject? {
    val copy = JSONObject(scene.toString())
    val ops = RemoteCompanionOperations.get(c)
    val target = JSONObject().put("id", copy.getString("id"))
    val operation = ops.start("scene", target) {
      val results = JSONArray()
      val steps = copy.getJSONArray("steps")
      // Revalidate at execution time as stored sources/apps can have changed since saving.
      if (!validate(JSONArray().put(copy)).valid) return@start JSONArray().put(RemoteCompanionOperations.result(false, "invalid_scene"))
      for (i in 0 until steps.length()) {
        val step = steps.getJSONObject(i)
        val result = runCatching {
          when (step.getString("type")) {
            "wait" -> { Thread.sleep(step.getLong("ms")); RemoteCompanionOperations.result(true, "waited") }
            "settings" -> synchronized(RemoteCompanionStore.mutationLock) {
              val domain = SettingsRegistry.domain(step.getString("domain"))
              val values = step.getJSONObject("values")
              if (domain == null || !RemoteSnapshotPolicy.validateValues(domain, values)) RemoteCompanionOperations.result(false, "invalid_settings")
              else {
                RemoteSnapshotPolicy.applyVerified(c, domain, values).put("result", "settings")
              }
            }
            "source" -> source(c, step.getJSONObject("values"))
            "launch" -> ops.action(JSONObject().put("action", "launch").put("packageName", step.getString("packageName")))
            else -> ops.action(step)
          }
        }.getOrElse { RemoteCompanionOperations.result(false, "step_failed") }
        results.put(result.put("step", i).put("type", step.getString("type")))
        if (!result.getBoolean("ok")) break
      }
      results
    }
    if (operation == null && scheduled) ops.history.recordFailure("scene", target, "busy", "scheduled")
    return operation
  }

  private fun source(c: Context, step: JSONObject): JSONObject = synchronized(RemoteCompanionStore.mutationLock) {
    val s = ScreensaverConfig.load(c)
    val body = JSONObject()
    when (step.optString("source").ifBlank { "folder" }) {
      "default" -> body.put("source", "default")
      "local", "folder" -> {
        val folder = step.getString("folderPath")
        if (!RemoteCompanionPhotos.allowedFolder(folder) || !File(folder).isDirectory) return@synchronized RemoteCompanionOperations.result(false, "folder_unavailable")
        body.put("folderPath", folder)
      }
      "immich" -> { if (s.immichUrl.isNullOrBlank() || s.immichKey.isNullOrBlank()) return@synchronized RemoteCompanionOperations.result(false, "source_not_configured"); body.put("immichUrl", s.immichUrl).put("immichKey", s.immichKey) }
      "smb" -> { if (s.smbHost.isNullOrBlank() || s.smbShare.isNullOrBlank()) return@synchronized RemoteCompanionOperations.result(false, "source_not_configured"); body.put("smbHost", s.smbHost).put("smbShare", s.smbShare).put("smbPath", s.smbPath ?: "").put("smbUser", s.smbUser ?: "").put("smbPass", s.smbPass ?: "") }
      "dav" -> { if (s.davUrl.isNullOrBlank()) return@synchronized RemoteCompanionOperations.result(false, "source_not_configured"); body.put("davUrl", s.davUrl).put("davUser", s.davUser ?: "").put("davPass", s.davPass ?: "") }
      "web" -> { if (s.webUrl.isNullOrBlank()) return@synchronized RemoteCompanionOperations.result(false, "source_not_configured"); body.put("webUrl", s.webUrl) }
      "album" -> { if (s.albumUrl.isNullOrBlank()) return@synchronized RemoteCompanionOperations.result(false, "source_not_configured"); body.put("albumUrl", s.albumUrl) }
    }
    val applied = FleetScreensaver.apply(c, body)
    SettingsGuard.afterScreensaverApply(c, applied)
    RemoteCompanionOperations.result(applied.isNotEmpty(), "source").put("source", step.optString("source").ifBlank { "folder" })
  }

  private fun pending(c: Context, id: String): PendingIntent = PendingIntent.getBroadcast(c, 0,
      Intent(c, RemoteSceneReceiver::class.java).setAction("com.immortal.launcher.REMOTE_SCENE").setData(android.net.Uri.parse("immortal-scene://$id")).putExtra("id", id),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

  fun replace(c: Context, scenes: JSONArray) = synchronized(RemoteCompanionStore.mutationLock) {
    val alarm = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val old = RemoteCompanionStore.scenes(c)
    for (i in 0 until old.length()) alarm.cancel(pending(c, old.getJSONObject(i).getString("id")))
    RemoteCompanionStore.update(c, "scenes", scenes)
    rearm(c)
  }
  fun rearm(c: Context) {
    val scenes = RemoteCompanionStore.scenes(c)
    if (!validate(scenes).valid) return
    val alarm = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    for (i in 0 until scenes.length()) {
      val scene = scenes.getJSONObject(i)
      val schedule = scene.optJSONObject("schedule") ?: continue
      if (schedule.optBoolean("enabled")) alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTime(schedule), pending(c, scene.getString("id")))
    }
  }
  internal fun nextTime(schedule: JSONObject, now: Long = System.currentTimeMillis()): Long {
    val days = schedule.getJSONArray("days")
    for (offset in 0..7) {
      val candidate = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DATE, offset)
        set(Calendar.HOUR_OF_DAY, schedule.getInt("hour")); set(Calendar.MINUTE, schedule.getInt("minute")); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
      }
      val iso = (candidate.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
      if (candidate.timeInMillis > now && (0 until days.length()).any { days.getInt(it) == iso }) return candidate.timeInMillis
    }
    error("invalid_schedule")
  }
}

/** Explicit alarms plus system reboot/time-change rearming; no exported scene action. */
class RemoteSceneReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
      runCatching { RemoteScenes.rearm(context) }; return
    }
    if (intent.action != "com.immortal.launcher.REMOTE_SCENE") return
    val id = intent.getStringExtra("id") ?: return
    val scenes = RemoteCompanionStore.scenes(context)
    val scene = (0 until scenes.length()).mapNotNull { scenes.optJSONObject(it) }.firstOrNull { it.optString("id") == id } ?: return
    if (!scene.optJSONObject("schedule")?.optBoolean("enabled").let { it == true }) return
    runCatching { RemoteScenes.rearm(context); RemoteScenes.start(context, scene, scheduled = true) }
  }
}

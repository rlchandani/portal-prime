/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import com.immortal.launcher.settings.SettingsRegistry
import java.io.File
import org.json.JSONObject

/** Real controller/loader with generated files and UUID-isolated preferences only. */
class NightPhotoPreviewActivity : Activity() {
  lateinit var frame: PhotoFrameController
    private set
  private var folder: File? = null
  private val preferences = linkedSetOf<String>()

  override fun onCreate(state: Bundle?) {
    super.onCreate(state)
    val token = intent.getStringExtra("owned_probe")
    if (token == null || !token.matches(Regex("night-photo-owned-[a-f0-9-]+")) ||
        token == cancelledProbe) { finish(); return }
    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    val files = File(cacheDir, token)
    check(!files.exists() && files.mkdir())
    folder = files
    val ownFiles = File(files, "files").apply { check(mkdir()) }
    val ownCache = File(files, "cache").apply { check(mkdir()) }
    val isolated = object : ContextWrapper(this) {
      override fun getFilesDir(): File = ownFiles
      override fun getCacheDir(): File = ownCache
      override fun getExternalFilesDir(type: String?): File = ownFiles
      override fun getExternalCacheDir(): File = ownCache
      override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val ownedName = "$token-$name"
        preferences.add(ownedName)
        return super.getSharedPreferences(ownedName, mode)
      }
    }
    for (index in 0..2) {
      val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
      try {
        bitmap.eraseColor(listOf(Color.RED, Color.GREEN, Color.BLUE)[index])
        File(files, "$index.png").outputStream().use {
          check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
      } finally { bitmap.recycle() }
    }
    val patch = JSONObject().put("mode", "smartFrame").put("intervalSec", 5)
        .put("shuffle", false).put("includeVideo", false).put("photo_motion", false)
        .put("keep_people_in_frame", false).put("cacheEnabled", true)
        .put("overnightEnabled", false).put("darkRoomClock", false).put("idleSleepMin", 0)
        .put("sleepTimerEnabled", false).put("welcomeEnabled", false).put("ambientDashboard", false)
        .put("gestureWave", false).put("soundscape", "off").put("showNowPlaying", false)
        .put("antiBurnIn", false)
    check(SettingsRegistry.domain("screensaver")!!.applyValues(isolated, patch) ==
        patch.keys().asSequence().toSet())
    SettingsRegistry.domain("calendar")!!.applyValues(isolated, JSONObject().put("widgetOn", false))
    ScreensaverConfig.setFolder(isolated, files.path)
    frame = PhotoFrameController(isolated, showWelcome = false, interactivePreview = true)
    frame.faceOverride = Face.fromJson("preview", JSONObject("""{"clock":{"mode":"none"},
      "weather":{"enabled":false},"nowPlaying":{"enabled":false},"battery":{"enabled":false},
      "caption":{"enabled":false}}"""))
    setContentView(frame.view)
    frame.start()
  }

  override fun onStart() {
    super.onStart()
    if (::frame.isInitialized) frame.setHostActive(true)
  }

  override fun onStop() {
    if (::frame.isInitialized) frame.setHostActive(false)
    super.onStop()
  }

  override fun onDestroy() {
    if (::frame.isInitialized) frame.stop()
    // Only the freshly-created, UUID-owned tree can be removed by this fixture.
    folder?.deleteRecursively()
    preferences.forEach(::deleteSharedPreferences)
    super.onDestroy()
  }

  companion object {
    private var cancelledProbe: String? = null
    @JvmStatic fun cancelOwnedProbe(token: String) { cancelledProbe = token }
  }
}

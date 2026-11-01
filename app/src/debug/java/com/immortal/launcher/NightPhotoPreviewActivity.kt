/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Activity
import android.content.ComponentCallbacks
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
import java.security.MessageDigest
import org.json.JSONObject

/** Real controller/loader with generated files and UUID-isolated preferences only. */
class NightPhotoPreviewActivity : Activity() {
  lateinit var frame: PhotoFrameController
    private set
  private var folder: File? = null
  private val preferences = linkedSetOf<String>()
  private var warmFaces: SlideshowFaceStore? = null
  private var startWhenWarm: Runnable? = null

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
      override fun getApplicationContext(): Context = this
      // Storage stays isolated, but framework callbacks belong to the real Application.
      // Context's default registration follows applicationContext and would recurse here.
      override fun registerComponentCallbacks(callback: ComponentCallbacks) =
          this@NightPhotoPreviewActivity.applicationContext.registerComponentCallbacks(callback)
      override fun unregisterComponentCallbacks(callback: ComponentCallbacks) =
          this@NightPhotoPreviewActivity.applicationContext.unregisterComponentCallbacks(callback)
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
    val motion = intent.getBooleanExtra("photo_motion", false)
    val mode = intent.getStringExtra("slideshow_mode") ?: "smartFrame"
    check(mode in setOf("classic", "smartFrame", "kenBurns", "diptych", "framed", "galleryWall",
        "push", "memories", "panorama", "polaroid", "mosaic", "cinema", "heroSidebar"))
    val native = mode == "smartFrame"
    val generatedKeys = mutableListOf<String>()
    val faceCache = SlideshowFaceCache(File(ownFiles, "slideshow_faces/metadata.bin"))
    val now = System.currentTimeMillis()
    for (index in 0 until if (native) 3 else 16) {
      val size = if (native) 160 to 90 else
        listOf(1024 to 576, 600 to 960, 720 to 720, 1920 to 600)[index % 4]
      val bitmap = Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888)
      try {
        bitmap.eraseColor(listOf(Color.RED, Color.GREEN, Color.BLUE)[index % 3])
        File(files, "$index.png").outputStream().use {
          check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        if (motion && native) {
          val digest = MessageDigest.getInstance("SHA-256")
              .digest(File(files, "$index.png").readBytes())
              .joinToString("") { "%02x".format(it.toInt() and 255) }
          val key = "encoded:$digest"
          generatedKeys.add(key)
          faceCache.record(SlideshowFaceMetadata.Entry(
              SlideshowFaceMetadata.key(key), SlideshowFaceMetadata.Status.FACES, now,
              160, 90, listOf(SlideshowFaceMetadata.Bounds(.4f, .4f, .6f, .6f))), now)
        }
      } finally { bitmap.recycle() }
    }
    if (motion && native) {
      faceCache.persist(now)
      warmFaces = SlideshowFaceStore(isolated)
    }
    val patch = JSONObject().put("mode", mode).put("intervalSec", 5)
        .put("shuffle", false).put("includeVideo", false)
        .put("photo_motion", motion)
        .put("keep_people_in_frame", motion && native).put("cacheEnabled", true)
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
    if (!motion || !native) frame.start() else {
      val warmDeadline = android.os.SystemClock.uptimeMillis() + 5000
      startWhenWarm = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        if (generatedKeys.all { warmFaces?.regionsFor(it)?.isNotEmpty() == true }) frame.start()
        else if (android.os.SystemClock.uptimeMillis() < warmDeadline)
          frame.view.postDelayed(startWhenWarm!!, 25)
        else finish()
      }
      frame.view.post(startWhenWarm!!)
    }
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
    if (::frame.isInitialized) startWhenWarm?.let(frame.view::removeCallbacks)
    if (::frame.isInitialized) frame.stop()
    warmFaces?.close()
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

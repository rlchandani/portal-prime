/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Instrumentation
import android.app.UiAutomation
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.Window
import android.view.WindowManager
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/** Actual attached Dream window; no settings writes, screenshots, media or sensor stimulus. */
internal object NightBrightnessDeliveryDeviceChecks {
  fun run(instrumentation: Instrumentation, automation: UiAutomation): JSONObject {
    val assertions = JSONArray()
    val measurements = JSONObject()
    fun check(name: String, value: Boolean) {
      assertions.put(JSONObject().put("name", name).put("pass", value))
    }
    var window: Window? = null
    var originalCallback: Window.Callback? = null
    var originalBrightness = -1f
    var originalOwner: NightWindowBrightness? = null
    var fixture: NightWindowBrightness? = null
    var frame: PhotoFrameController? = null
    var originalService: PhotoDreamService? = null
    var originalNight = false
    val events = AtomicInteger()
    val started = SystemClock.elapsedRealtime()
    try {
      val service = main {
        val ref = ImmortalDreamNavigation::class.java.getDeclaredField("active")
            .apply { isAccessible = true }.get(ImmortalDreamNavigation) as WeakReference<*>
        ref.get() as? PhotoDreamService
      }
      check("OwnedDreamPresent", service != null)
      require(service != null)
      originalService = service
      main {
        window = service.window
        check("DreamWindowAttached", window?.decorView?.isAttachedToWindow == true)
        require(window != null && window!!.decorView.isAttachedToWindow)
        frame = service.javaClass.getDeclaredField("frame").apply { isAccessible = true }
            .get(service) as PhotoFrameController
        originalNight = frame!!.isNightClockShowing
        originalOwner = service.javaClass.getDeclaredField("nightBrightness").apply { isAccessible = true }
            .get(service) as NightWindowBrightness
        originalOwner!!.setActive(false)
        originalBrightness = window!!.attributes.screenBrightness
        originalCallback = window!!.callback
        require(originalCallback != null)
        window!!.callback = object : Window.Callback by originalCallback!! {
          override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams) {
            events.incrementAndGet()
            originalCallback!!.onWindowAttributesChanged(attrs)
          }
        }
        // Restrict this fixture to the same comfortable low range already used by the clock.
        window!!.attributes = window!!.attributes.apply { screenBrightness = .03f }
        fixture = NightWindowBrightness(service, window!!).also { it.setActive(true) }
      }
      check("ActualApi29", android.os.Build.VERSION.SDK_INT == 29)
      for ((label, night, target) in listOf(
          Triple("down", true, .01f), Triple("up", false, .03f))) {
        events.set(0)
        val began = SystemClock.elapsedRealtime()
        main { fixture!!.setNight(night, 1) }
        Thread.sleep(3_250)
        val level = main { window!!.attributes.screenBrightness }
        val count = events.get()
        measurements.put("${label}WindowUpdates", count)
        measurements.put("${label}ElapsedMs", SystemClock.elapsedRealtime() - began)
        check("${label}ExactEndpoint", level == target)
        check("${label}RedundantWindowUpdatesSuppressed", count in 1..10)
        check("${label}HardwareEndpoint", displayBrightness(automation) == (target * 255).toInt())
      }
      main {
        fixture!!.close()
        window!!.attributes = window!!.attributes.apply {
          screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        fixture = NightWindowBrightness(service, window!!).also { it.setActive(true) }
      }
      events.set(0)
      main { fixture!!.setNight(true, 1) }
      Thread.sleep(3_250)
      check("DefaultOwnerNightEndpoint", main { window!!.attributes.screenBrightness } == .01f)
      val system = Settings.System.getInt(service.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
      measurements.put("systemSelectedBrightness", system)
      require(system in 1..51) // This test never raises the clock to a bright daytime level.
      events.set(0)
      main { fixture!!.setNight(false, 1) }
      Thread.sleep(3_250)
      check("DefaultPolicyRestored", main { window!!.attributes.screenBrightness } == -1f)
      check("DefaultHardwareEndpoint", displayBrightness(automation) == system)
      measurements.put("defaultReturnWindowUpdates", events.get())
      check("DefaultReturnRedundantWindowUpdatesSuppressed", events.get() in 1..55)
    } catch (error: Throwable) {
      measurements.put("failureClass", error.javaClass.simpleName)
      check("ProbeCompleted", false)
    } finally {
      try {
        main {
          fixture?.close()
          window?.let { current ->
            current.attributes = current.attributes.apply { screenBrightness = originalBrightness }
            originalCallback?.let { current.callback = it }
          }
          originalOwner?.setActive(true)
          check("WindowOverrideRestored", window == null || window!!.attributes.screenBrightness == originalBrightness)
          check("OriginalCallbackRestored", window == null || window!!.callback === originalCallback)
          val activeRef = ImmortalDreamNavigation::class.java.getDeclaredField("active")
              .apply { isAccessible = true }.get(ImmortalDreamNavigation) as WeakReference<*>
          check("SameDreamRetained", originalService != null && activeRef.get() === originalService)
          check("SameControllerRetained", originalService != null && frame != null &&
              originalService!!.javaClass.getDeclaredField("frame").apply { isAccessible = true }
                  .get(originalService) === frame)
          check("NightModeUnchanged", frame == null || frame!!.isNightClockShowing == originalNight)
        }
      } catch (_: Throwable) { check("CleanupCompleted", false) }
    }
    measurements.put("probeDurationMs", SystemClock.elapsedRealtime() - started)
    return JSONObject().put("pass", (0 until assertions.length()).all { assertions.getJSONObject(it).getBoolean("pass") })
        .put("assertions", assertions).put("measurements", measurements)
        .put("globalSettingsWritten", false).put("opticalComfortTested", false)
        .put("screenshotsOrMediaExported", false)
  }

  private fun displayBrightness(automation: UiAutomation): Int {
    val fd = automation.executeShellCommand("dumpsys display")
    val text = ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().toString(Charsets.UTF_8) }
    return Regex("(?m)^\\s*mScreenBrightness=(\\d+)\\s*$").find(text)?.groupValues?.get(1)?.toInt() ?: -1
  }

  private fun <T> main(block: () -> T): T {
    val done = CountDownLatch(1)
    val state = AtomicInteger()
    var result: Result<T>? = null
    val handler = Handler(Looper.getMainLooper())
    val task = Runnable {
      if (state.compareAndSet(0, 1)) result = runCatching(block)
      done.countDown()
    }
    handler.post(task)
    if (!done.await(5, TimeUnit.SECONDS)) {
      if (state.compareAndSet(0, 2)) handler.removeCallbacks(task)
      error("Bounded Main rendezvous expired")
    }
    return result!!.getOrThrow()
  }
}

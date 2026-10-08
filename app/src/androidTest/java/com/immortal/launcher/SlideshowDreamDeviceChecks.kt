/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.service.dreams.DreamService
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Invoke ONLY after the soak, inside SlideshowDeviceChecks' configured synthetic-source scope,
 * with its preview closed. Main's post-soak dreamOnly=true command can call:
 *
 * SlideshowDreamDeviceChecks.run(instrumentation, automation, ::ownedKey)
 *
 * The caller owns source/settings/presence snapshots and their restoration. This helper changes
 * no preferences, secure settings, source, runner or production code. Before calling, configure
 * screensaver_enabled=1 and both screensaver_components/screensaver_default_component to
 * com.immortal.launcher.debug/com.immortal.launcher.PhotoDreamService, with faces/dismiss disabled.
 * It restores its callback tripwires and transient DreamPolicy.userExitAt marker after the
 * stopped broadcast drains. If that cannot be confirmed before the deadline, it reports failure
 * and leaves a recent suppression marker instead of risking a cleanup redream. Reports contain
 * aggregate booleans, elapsed milliseconds and exception class names; no shell output or photo keys.
 *
 * The Portal shell lacks WRITE_DREAM_STATE, so do not call the DreamManager start/awaken binder.
 * Its installed SystemUI Somnambulator was queried read-only: enabled=true, exported=true.
 * Android 10 AOSP confirms it calls Sandman.startDreamByUserRequest from its privileged process:
 * https://android.googlesource.com/platform/frameworks/base/+/android10-release/packages/SystemUI/src/com/android/systemui/Somnambulator.java
 * Start with am start -n com.android.systemui/.Somnambulator and validate the requested component.
 * Do not wait for an Activity draw: this launcher immediately finishes to start the Dream.
 * This requests a normal OS Dream (not testDream), including attach/start/stop/detach and the
 * dreaming broadcasts. It does not test unattended idle/presence initiation or forced redream.
 * Finally finish a known same-process service, or inject WAKEUP/BACK; neither needs that permission.
 * https://android.googlesource.com/platform/frameworks/base/+/android10-release/core/java/android/app/UiAutomationConnection.java
 * executeShellCommand uses Runtime.exec(String), not a shell: only direct executable/argument
 * strings are passed. Attached-window DOWN/UP dispatch verifies the app's tap callback and OS stop.
 * Portal-injected touchscreen input wakes the Dream before reaching the app; the fallback checks
 * that OS wake separately. Neither path establishes physical-panel touch routing.
 *
 * Same-process service reflection is optional; main can inject a known live service reference.
 * Without it, OS state + an active native slideshow accessibility surface + injected input/stop
 * provide lifecycle evidence, but cannot prove synthetic photo ownership or absence of control
 * callback dispatch. dreamLifecycleCompleted reports that narrower check; dreamFullyVerified
 * additionally requires service inspection, ownership, callback tripwires and detach cleanup.
 *
 * All waits/shell reads are bounded: seven seconds for the probe, three reserved for finally's
 * unconditional awaken and verification. Activity start has a four-second process timeout and
 * 4.5-second pipe wait, capped by the probe deadline; other shell children time out in one second.
 * Neither this helper nor its discovery methods should be invoked during an existing soak.
 */
internal object SlideshowDreamDeviceChecks {
  private const val PACKAGE = "com.immortal.launcher.debug"
  private const val SERVICE = "com.immortal.launcher.PhotoDreamService"
  private const val LIMIT_MS = 10_000L
  private const val CLEANUP_MS = 3_000L
  private const val CALL_MS = 700L
  private const val INPUT_WAIT_MS = 2_500L
  private const val ACTIVITY_START_WAIT_MS = 4_500L
  private const val POLL_MS = 80L
  // Hidden in the public SDK; WindowManager.LayoutParams.TYPE_DREAM in Android 10 AOSP.
  private const val DREAM_WINDOW_TYPE = 2023
  private val component = ComponentName(PACKAGE, SERVICE)
  private val somnambulator = ComponentName("com.android.systemui", "com.android.systemui.Somnambulator")

  fun run(
      instrumentation: Instrumentation,
      automation: UiAutomation,
      ownsPhoto: (String) -> Boolean,
      launcher: ComponentName = somnambulator,
      serviceReference: (() -> PhotoDreamService?)? = null,
  ): Bundle = Probe(instrumentation, automation, ownsPhoto, launcher, serviceReference).run()

  private class CheckFailure : RuntimeException()
  private class DeadlineFailure : RuntimeException()
  private class ShellReplyFailure : RuntimeException()
  private class DreamDumpFormatFailure : RuntimeException()

  private data class OsState(val ours: Boolean, val stopped: Boolean, val managerStopped: Boolean)
  private data class Inspection(
      val service: PhotoDreamService,
      val frame: PhotoFrameController,
      val stage: SlideshowStage,
      val controls: SlideshowViewerControls,
  )

  private class Probe(
      private val instrumentation: Instrumentation,
      private val automation: UiAutomation,
      private val ownsPhoto: (String) -> Boolean,
      private val launcher: ComponentName,
      private val serviceReference: (() -> PhotoDreamService?)?,
  ) {
    private val result = Bundle().apply {
      putBoolean("dreamServiceReferenceAvailable", false)
      putBoolean("dreamActivityStartResponseReceived", false)
      putBoolean("dreamNativePhotoOwnershipVerified", false)
      putBoolean("dreamNoDeliberateControlsDispatched", false)
      putBoolean("dreamServiceDetachedAndStageCleared", false)
    }
    private val began = SystemClock.elapsedRealtime()
    private val deadline = began + LIMIT_MS - CLEANUP_MS
    private val handler = Handler(Looper.getMainLooper())
    private val workers = Executors.newCachedThreadPool { task ->
      Thread(task, "slideshow-dream-shell").apply { isDaemon = true }
    }
    private val callbacks = ArrayList<Pair<Field, Any>>()
    private val controlDispatched = AtomicBoolean()
    private val startedBroadcast = AtomicBoolean()
    private val stoppedBroadcast = AtomicBoolean()
    private val stoppedBroadcastDrained = AtomicBoolean()
    private var inspection: Inspection? = null
    private var lifecyclePassed = false
    private var cleanupPassed = false
    private var eligible = false
    private var receiverRegistered = false
    private var activityAccepted = false
    private var holdExitMarker = false
    private val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
          Intent.ACTION_DREAMING_STARTED -> startedBroadcast.set(true)
          Intent.ACTION_DREAMING_STOPPED -> if (startedBroadcast.get()) {
            stoppedBroadcast.set(true)
            // This low-priority receiver follows ImmortalApp's normal-priority receiver.
            // Keep suppression until queued main-thread broadcast work has also returned.
            handler.post { stoppedBroadcastDrained.set(true) }
          }
        }
      }
    }

    fun run(): Bundle {
      val exitBefore = DreamPolicy.userExitAt
      try {
        demand("dreamAndroid10DebugTarget",
            Build.VERSION.SDK_INT == 29 && instrumentation.targetContext.packageName == PACKAGE &&
                Looper.myLooper() != Looper.getMainLooper())
        preflight()
        demand("dreamInitiallyStopped", osState(deadline).stopped)
        main(deadline) {
          instrumentation.targetContext.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_DREAMING_STARTED)
            addAction(Intent.ACTION_DREAMING_STOPPED)
            priority = -1000
          })
          receiverRegistered = true
        }
        eligible = true
        holdExitMarker = true
        refreshExitMarker()
        val activityStartBegan = SystemClock.elapsedRealtime()
        try {
          val reply = shell("am start -n ${launcher.flattenToShortString()}", deadline,
              waitMs = ACTIVITY_START_WAIT_MS, processTimeoutSeconds = 4)
          result.putBoolean("dreamActivityStartResponseReceived", true)
          activityStartReply(reply)
        } finally {
          result.putLong("dreamActivityStartWaitMs", SystemClock.elapsedRealtime() - activityStartBegan)
        }
        activityAccepted = true
        demand("dreamStartActivityAccepted", true)
        poll(deadline) { osState(deadline).ours && startedBroadcast.get() }
        demand("dreamOsComponentActive", true)
        demand("dreamNormalOsDreamVerified", true)
        demand("dreamStartedBroadcastObserved", true)
        result.putLong("dreamStartToActiveMs", elapsed())

        // A denied hidden API or a service in another process is an explicit coverage boundary.
        try {
          main(deadline) {
            val service = serviceReference?.invoke() ?: liveService()
            result.putBoolean("dreamServiceReferenceAvailable", service != null)
            if (service != null) {
              // Establish reflection availability before choosing the inspection path.
              field(service, "mStarted", DreamService::class.java).getBoolean(service)
              inspection = Inspection(
                  service,
                  field(service, "frame").get(service) as PhotoFrameController,
                  readFrameStage(service),
                  readFrameControls(service))
            }
          }
        } catch (error: Throwable) {
          result.putString("dreamServiceInspectionErrorType", errorType(error))
          result.putBoolean("dreamServiceReferenceAvailable", false)
        }

        var tapBounds: Rect? = null
        val observed = inspection
        if (observed != null) {
          poll(deadline) { main(deadline) { ownedNativePhoto(observed) } }
          demand("dreamNativePhotoOwnershipVerified", true)
          main(deadline) {
            demand("dreamServiceLifecycleStarted",
                field(observed.service, "mStarted", DreamService::class.java)
                    .getBoolean(observed.service))
            demand("dreamInteractiveService", observed.service.isInteractive)
            demand("dreamDeliberateModeDisabled", !observed.frame.interactivePreview)
            demand("dreamControlsHiddenBeforeTap", observed.controls.visibility == View.GONE)
            armControlTripwires(observed.controls)
            tapBounds = Rect().also { demand("dreamNativeSurfaceVisible",
                observed.stage.getGlobalVisibleRect(it) && it.width() > 0 && it.height() > 0) }
          }
        } else {
          poll(deadline) { tapBounds = accessibilityStage(); tapBounds != null }
          demand("dreamNativeSurfaceVisible", true)
          demand("dreamControlsHiddenBeforeTap", !accessibilityControls())
        }
        result.putLong("dreamStartToPhotoMs", elapsed())
        // Re-check OS ownership immediately before injecting through Android's input system.
        demand("dreamOsActiveAtTap", osState(deadline).ours)
        val markerBeforeTap = DreamPolicy.userExitAt
        val tapWallTime = System.currentTimeMillis()
        val tapBegan = SystemClock.elapsedRealtime()
        val bounds = checkNotNull(tapBounds)
        holdExitMarker = false // Let the real service's tap set its own exit marker.
        result.putString("dreamTapMethod", if (observed != null) "attachedDreamWindow" else "osInput")
        demand("dreamTapDelivered", if (observed != null) dispatchWindowTap(observed, bounds)
            else injectTap(bounds))
        var tapExitMarkerObserved = false
        poll(deadline) {
          val stopped = osState(deadline).stopped
          val marker = DreamPolicy.userExitAt
          if (!tapExitMarkerObserved && marker != markerBeforeTap &&
              marker >= tapWallTime && marker <= System.currentTimeMillis()) {
            tapExitMarkerObserved = true
            holdExitMarker = true // Preserve this evidence, then keep suppression fresh.
          }
          if (stopped) holdExitMarker = true
          stopped
        }
        demand("dreamStoppedByTapBeforeCleanup", true)
        result.putLong("dreamTapToStoppedMs", SystemClock.elapsedRealtime() - tapBegan)
        if (observed != null) {
          poll(deadline) { main(deadline) { detached(observed) } }
          main(deadline) {
            demand("dreamUserExitMarkerObserved", tapExitMarkerObserved)
            demand("dreamControlsHiddenAfterTap", observed.controls.visibility == View.GONE)
            demand("dreamNoDeliberateControlsDispatched", !controlDispatched.get())
            demand("dreamServiceDetachedAndStageCleared", true)
          }
        } else {
          demand("dreamControlsHiddenAfterTap", !accessibilityControls())
        }
        poll(deadline) { stoppedBroadcast.get() && stoppedBroadcastDrained.get() }
        main(deadline) { demand("dreamStoppedBroadcastDrained", true) }
        lifecyclePassed = true
      } catch (error: Throwable) {
        result.putString("dreamErrorType", errorType(error))
      } finally {
        // Interrupting a test must not skip waking its Dream. Preserve the flag for the caller.
        val interrupted = Thread.interrupted()
        val cleanupDeadline = began + LIMIT_MS
        try {
          if (eligible) {
            holdExitMarker = true
            refreshExitMarker()
            result.putBoolean("dreamAwakenAttempted", true)
            awaken(cleanupDeadline)
            restoreCallbacks(cleanupDeadline)
            poll(cleanupDeadline) {
              osState(cleanupDeadline).stopped &&
                  (inspection?.let { main(cleanupDeadline) { detached(it) } } ?: true)
            }
            demand("dreamFinallyAwake", true)
            if (activityAccepted || startedBroadcast.get()) {
              poll(cleanupDeadline) { stoppedBroadcast.get() && stoppedBroadcastDrained.get() }
              main(cleanupDeadline) { demand("dreamStoppedBroadcastDrained", true) }
            }
            cleanupPassed = true
          }
        } catch (error: Throwable) {
          result.putString("dreamCleanupErrorType", errorType(error))
        } finally {
          try {
            restoreCallbacks(cleanupDeadline)
            result.putBoolean("dreamCallbackTripwiresRestored", callbacks.isEmpty())
          } catch (error: Throwable) {
            cleanupPassed = false
            result.putString("dreamCallbackRestoreErrorType", errorType(error))
          }
          try {
            if (receiverRegistered) main(cleanupDeadline) {
              instrumentation.targetContext.unregisterReceiver(receiver)
              receiverRegistered = false
            }
            result.putBoolean("dreamBroadcastReceiverRemoved", !receiverRegistered)
          } catch (error: Throwable) {
            cleanupPassed = false
            result.putString("dreamReceiverCleanupErrorType", errorType(error))
          }
          val restoreMarker = !eligible || cleanupPassed &&
              (!activityAccepted && !startedBroadcast.get() || stoppedBroadcastDrained.get())
          if (restoreMarker) DreamPolicy.userExitAt = exitBefore else refreshExitMarker()
          result.putBoolean("dreamExitMarkerRestored", restoreMarker)
          workers.shutdownNow()
          if (interrupted) Thread.currentThread().interrupt()
        }
      }
      result.putBoolean("dreamLifecycleCompleted", lifecyclePassed && cleanupPassed)
      result.putBoolean("dreamFullyVerified", lifecyclePassed && cleanupPassed &&
          result.getBoolean("dreamNativePhotoOwnershipVerified") &&
          result.getBoolean("dreamNoDeliberateControlsDispatched") &&
          result.getBoolean("dreamServiceDetachedAndStageCleared"))
      result.putLong("dreamElapsedMs", elapsed())
      return result
    }

    private fun preflight() = remote(deadline) {
      val context = instrumentation.targetContext
      val info = context.packageManager.getActivityInfo(launcher, 0)
      demand("dreamSystemUiLauncherAvailable",
          info.enabled && info.applicationInfo.enabled && info.exported && info.permission == null)
      val resolver = context.contentResolver
      demand("dreamSecureEnabled", Settings.Secure.getString(resolver, "screensaver_enabled") == "1")
      val selected = Settings.Secure.getString(resolver, "screensaver_components")
          ?.split(',')?.map { ComponentName.unflattenFromString(it) }
      demand("dreamSecureComponentsConfigured", selected == listOf(component))
      demand("dreamSecureDefaultConfigured", ComponentName.unflattenFromString(
          Settings.Secure.getString(resolver, "screensaver_default_component").orEmpty()) == component)
    }

    private fun awaken(until: Long) {
      var finishedService = false
      try {
        val service = main(until) { inspection?.service ?: serviceReference?.invoke() ?: liveService() }
        if (service != null) {
          main(until) { service.finish() }
          finishedService = true
          result.putBoolean("dreamCleanupUsedServiceFinish", true)
        }
      } catch (error: Throwable) {
        result.putString("dreamCleanupServiceFinishErrorType", errorType(error))
      }
      // A cleared manager token with a still-detaching controller only needs the stop poll.
      if (finishedService && osState(until).managerStopped) return
      demand("dreamCleanupWakeupKeyAccepted", injectKey(KeyEvent.KEYCODE_WAKEUP, until))
      result.putBoolean("dreamCleanupUsedWakeupKey", true)
      if (!osState(until).managerStopped) {
        demand("dreamCleanupBackKeyAccepted", injectKey(KeyEvent.KEYCODE_BACK, until))
        result.putBoolean("dreamCleanupUsedBackKey", true)
      }
    }

    private fun injectKey(code: Int, until: Long): Boolean = remote(until) {
      remaining(until)
      val downTime = SystemClock.uptimeMillis()
      var downAccepted = false
      var upAccepted = false
      fun inject(action: Int, flags: Int = 0): Boolean = automation.injectInputEvent(
          KeyEvent(downTime, SystemClock.uptimeMillis(), action, code, 0, 0,
              KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_KEYBOARD), false)
      try {
        downAccepted = inject(KeyEvent.ACTION_DOWN)
        if (!downAccepted) return@remote false
        remaining(until)
        upAccepted = inject(KeyEvent.ACTION_UP)
        upAccepted
      } finally {
        if (downAccepted && !upAccepted) inject(KeyEvent.ACTION_UP, KeyEvent.FLAG_CANCELED)
      }
    }

    private fun refreshExitMarker() {
      DreamPolicy.userExitAt = System.currentTimeMillis()
    }

    private fun liveService(): PhotoDreamService? {
      val type = Class.forName("android.app.ActivityThread")
      val thread = type.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }
          .invoke(null) ?: return null
      val services = field(thread, "mServices").get(thread) as Map<*, *>
      return services.values.filterIsInstance<PhotoDreamService>().singleOrNull()
    }

    private fun readFrameStage(service: PhotoDreamService): SlideshowStage {
      val frame = field(service, "frame").get(service) as PhotoFrameController
      return field(frame, "nativeStage").get(frame) as SlideshowStage
    }

    private fun readFrameControls(service: PhotoDreamService): SlideshowViewerControls {
      val frame = field(service, "frame").get(service) as PhotoFrameController
      return field(frame, "viewerControls").get(frame) as SlideshowViewerControls
    }

    @Suppress("UNCHECKED_CAST")
    private fun ownedNativePhoto(observed: Inspection): Boolean {
      val stage = observed.stage
      val shown = field(stage, "shown").get(stage) ?: return false
      val photos = field(shown, "photos").get(shown) as List<SlideshowPhoto>
      val window = observed.service.window ?: return false
      if (!stage.isShown || !stage.isAttachedToWindow ||
          !window.decorView.isAttachedToWindow ||
          window.attributes.type != DREAM_WINDOW_TYPE ||
          field(observed.frame, "nativePhotoMode").getBoolean(observed.frame).not() ||
          field(stage, "budget").get(stage) !== field(observed.frame, "pixelBudget").get(observed.frame))
        return false
      val images = ArrayList<ImageView>()
      fun visit(view: View, depth: Int) {
        if (depth > 12 || images.size > 32) throw CheckFailure()
        if (view is ImageView) images += view
        if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it), depth + 1) }
      }
      visit(stage, 0)
      return photos.size in 1..2 && photos.size == stage.photoCount && photos.all { photo ->
        ownsPhoto(photo.key) && ownsPhoto(photo.sourceKey) && !photo.bitmap.isRecycled &&
            images.any { it.isShown && (it.drawable as? BitmapDrawable)?.bitmap === photo.bitmap }
      }
    }

    private fun detached(observed: Inspection): Boolean =
        !field(observed.service, "mStarted", DreamService::class.java).getBoolean(observed.service) &&
            !observed.stage.isAttachedToWindow && observed.stage.photoCount == 0 &&
            observed.stage.currentPhoto == null && field(observed.frame, "stopped").getBoolean(observed.frame)

    private fun armControlTripwires(controls: SlideshowViewerControls) {
      fun replace(name: String, callback: Any) {
        val target = field(controls, name)
        callbacks += target to checkNotNull(target.get(controls))
        target.set(controls, callback)
      }
      // Preserve frame.onExit: the real service must mark a user exit and finish itself.
      // Unexpected toolbar actions fail the probe and cannot save synthetic/household metadata.
      val action: () -> Unit = { controlDispatched.set(true) }
      listOf("onPrevious", "onNext", "onExit", "onEditingStart").forEach { replace(it, action) }
      val pause: (Boolean) -> Unit = { controlDispatched.set(true) }
      val save: (String, SlideshowAdjustment?, (Boolean) -> Unit) -> Unit =
          { _, _, completed -> controlDispatched.set(true); completed(false) }
      replace("onPause", pause)
      replace("onSave", save)
    }

    private fun restoreCallbacks(until: Long) {
      if (callbacks.isEmpty()) return
      main(until) {
        val controls = checkNotNull(inspection).controls
        callbacks.asReversed().forEach { (field, original) -> field.set(controls, original) }
        callbacks.clear()
      }
    }

    private fun accessibilityStage(): Rect? = accessibility { node ->
      val nativeClass = node.className?.toString() in setOf(
          "android.widget.FrameLayout", SlideshowStage::class.java.name)
      nativeClass && node.contentDescription?.toString() in setOf("Photo slideshow", "Portrait pair")
    }

    private fun accessibilityControls(): Boolean = accessibility { node ->
      node.text?.toString() in setOf("Previous", "Next", "Pause", "Resume", "Adjust", "Done",
          "Cancel", "Save position") || node.contentDescription?.toString() == "Photo zoom"
    } != null

    private fun accessibility(matches: (AccessibilityNodeInfo) -> Boolean): Rect? =
        remote(deadline) { accessibilitySnapshot(matches) }

    private fun accessibilitySnapshot(matches: (AccessibilityNodeInfo) -> Boolean): Rect? {
      remaining(deadline)
      val root = automation.rootInActiveWindow ?: return null
      var visited = 0
      fun visit(node: AccessibilityNodeInfo, depth: Int): Rect? {
        remaining(deadline)
        if (++visited > 500 || depth > 24) throw CheckFailure()
        if (node.packageName?.toString() == PACKAGE && node.isVisibleToUser && matches(node)) {
          val bounds = Rect()
          node.getBoundsInScreen(bounds)
          if (bounds.width() > 0 && bounds.height() > 0) return bounds
        }
        repeat(node.childCount) { index ->
          val child = node.getChild(index) ?: return@repeat
          try { visit(child, depth + 1)?.let { return it } } finally { child.recycle() }
        }
        return null
      }
      return try {
        if (root.packageName?.toString() == PACKAGE) visit(root, 0) else null
      } finally { root.recycle() }
    }

    private fun osState(until: Long): OsState {
      val values = shell("dumpsys -t 1 dreams", until).lineSequence().map { it.trim() }
          .filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
      if (!values.containsKey("mCurrentDreamName") || !values.containsKey("mCurrentDreamToken"))
        throw DreamDumpFormatFailure()
      fun parsed(name: String): ComponentName? = values[name]?.let {
        ComponentName.unflattenFromString(it.removePrefix("ComponentInfo{").removeSuffix("}"))
      }
      val token = values["mCurrentDreamToken"]
      val ours = parsed("mCurrentDreamName") == component && token != null && token != "null" &&
          values["mCurrentDreamIsWaking"] == "false" && parsed("mName") == component &&
          values["mCurrentDreamIsTest"] == "false" && values["mSentStartBroadcast"] == "true" &&
          values["mBound"] == "true" && values["mService"]?.let { it != "null" } == true
      val managerStopped = values["mCurrentDreamName"] == "null" && token == "null"
      return OsState(ours, managerStopped && values["mName"] == null, managerStopped)
    }

    private fun activityStartReply(reply: String) {
      val lines = reply.lineSequence().map { it.trim() }.toList()
      val values = lines.filter { ':' in it }
          .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
      val requested = Regex("""\bcmp=([^\s}]+)""").find(values["Starting"].orEmpty())
          ?.groupValues?.get(1)?.let(ComponentName::unflattenFromString)
      // The normal start response is followed by independent OS ownership, broadcast, service,
      // window and owned-pixel checks. Waiting for this immediately finishing launcher to draw
      // hangs on the Portal, even though its Dream has already started.
      if (requested != launcher || lines.any {
          it.startsWith("Error:") || it.startsWith("Exception") ||
              it.contains("Permission Denial") || it.contains("SecurityException")
        })
        throw ShellReplyFailure()
    }

    private fun injectTap(bounds: Rect): Boolean = remote(deadline, INPUT_WAIT_MS) {
      remaining(deadline)
      val downTime = SystemClock.uptimeMillis()
      var downAccepted = false
      var upAccepted = false
      fun inject(action: Int): Boolean {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
            bounds.exactCenterX(), bounds.exactCenterY(), 0).apply {
          source = InputDevice.SOURCE_TOUCHSCREEN
        }
        // Events are owned/recycled by this worker, even if its caller times out.
        try {
          // Async acceptance keeps this RPC short; the OS stop poll verifies actual handling.
          return automation.injectInputEvent(event, false)
        } finally { event.recycle() }
      }
      try {
        downAccepted = inject(MotionEvent.ACTION_DOWN)
        if (!downAccepted) return@remote false
        SystemClock.sleep(minOf(40L, remaining(deadline)))
        remaining(deadline) // A canceled worker must not send a late UP/click.
        upAccepted = inject(MotionEvent.ACTION_UP)
        upAccepted
      } finally {
        // Release a rejected/interrupted touch stream without deliberately clicking anything.
        if (downAccepted && !upAccepted) inject(MotionEvent.ACTION_CANCEL)
      }
    }

    private fun dispatchWindowTap(observed: Inspection, bounds: Rect): Boolean {
      val downTime = SystemClock.uptimeMillis()
      fun send(action: Int): Boolean = main(deadline, INPUT_WAIT_MS) {
        val decor = checkNotNull(observed.service.window).decorView
        val origin = IntArray(2)
        decor.getLocationOnScreen(origin)
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
            bounds.exactCenterX() - origin[0], bounds.exactCenterY() - origin[1], 0)
            .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { decor.dispatchTouchEvent(event) } finally { event.recycle() }
      }
      val downAccepted = send(MotionEvent.ACTION_DOWN)
      if (!downAccepted) return false
      SystemClock.sleep(40)
      return try { send(MotionEvent.ACTION_UP) }
      catch (error: Throwable) {
        runCatching { send(MotionEvent.ACTION_CANCEL) }
        throw error
      }
    }

    private fun shell(
        command: String, until: Long, waitMs: Long = CALL_MS, processTimeoutSeconds: Int = 1,
    ): String {
      remaining(until)
      val pipe = AtomicReference<ParcelFileDescriptor?>()
      val abandoned = AtomicBoolean()
      val task = workers.submit<String> {
        // Android 10 tokenizes this through Runtime.exec(String), without shell expansion.
        // Commands contain only executable/arguments; no quoting, redirects or exit markers.
        val descriptor = automation.executeShellCommand("timeout $processTimeoutSeconds $command")
        pipe.set(descriptor)
        try {
          if (abandoned.get()) throw DeadlineFailure()
          ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val bytes = ByteArrayOutputStream()
            val chunk = ByteArray(2048)
            while (true) {
              val count = input.read(chunk)
              if (count < 0) break
              if (bytes.size() + count > 32 * 1024) throw ShellReplyFailure()
              bytes.write(chunk, 0, count)
            }
            bytes.toString("UTF-8").trimEnd()
          }
        } finally { pipe.set(null); runCatching { descriptor.close() } }
      }
      try { return task.get(minOf(waitMs, remaining(until)), TimeUnit.MILLISECONDS) }
      catch (error: ExecutionException) { throw error.cause ?: error }
      catch (_: TimeoutException) { throw DeadlineFailure() }
      finally {
        abandoned.set(true)
        runCatching { pipe.getAndSet(null)?.close() }
        task.cancel(true)
      }
    }

    private fun <T> main(until: Long, waitMs: Long = CALL_MS, action: () -> T): T {
      val task = FutureTask(action)
      remaining(until)
      handler.post(task)
      try { return task.get(minOf(waitMs, remaining(until)), TimeUnit.MILLISECONDS) }
      catch (error: ExecutionException) { throw error.cause ?: error }
      catch (_: TimeoutException) { throw DeadlineFailure() }
      finally { handler.removeCallbacks(task); task.cancel(false) }
    }

    private fun <T> remote(until: Long, waitMs: Long = CALL_MS, action: () -> T): T {
      remaining(until)
      val task = workers.submit<T> { action() }
      try { return task.get(minOf(waitMs, remaining(until)), TimeUnit.MILLISECONDS) }
      catch (error: ExecutionException) { throw error.cause ?: error }
      catch (_: TimeoutException) { throw DeadlineFailure() }
      finally { task.cancel(true) }
    }

    private fun poll(until: Long, ready: () -> Boolean) {
      while (true) {
        remaining(until)
        if (holdExitMarker) refreshExitMarker()
        if (ready()) return
        SystemClock.sleep(minOf(POLL_MS, remaining(until)))
      }
    }

    private fun remaining(until: Long): Long {
      if (Thread.currentThread().isInterrupted) throw InterruptedException()
      return (until - SystemClock.elapsedRealtime()).takeIf { it > 0 } ?: throw DeadlineFailure()
    }

    private fun demand(label: String, passed: Boolean) {
      result.putBoolean(label, passed)
      if (!passed) throw CheckFailure()
    }

    private fun field(owner: Any, name: String, type: Class<*> = owner.javaClass): Field =
        type.getDeclaredField(name).apply { isAccessible = true }

    private fun errorType(error: Throwable): String {
      val cause = if (error is InvocationTargetException) error.targetException else error
      if (cause is InterruptedException) Thread.currentThread().interrupt()
      return cause.javaClass.simpleName
    }

    private fun elapsed(): Long = SystemClock.elapsedRealtime() - began
  }
}

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.OrientationEventListener
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * Covers the rotation the platform ignores. On the Portal Mini the window manager follows the
 * left turn (ROTATION_90) but its sensor judge never proposes the right turn (ROTATION_270), so
 * the UI stays portrait, sideways. While one of our activities is in front on a compact display
 * (short side below [LayoutClass.COMPACT_SHORT_SIDE_DP]) with auto-rotate on, this listens to the
 * raw device angle ([RotationFollowPolicy]: ±30° windows, ±60° hysteresis, 600 ms hold).
 *
 * It does not hard-code a model: when a pose has settled and the display still hasn't reached
 * the rotation the pose needs ([RotationPlatformProbe.ignored]), that pose is recorded as ignored
 * by this platform (persisted) and from then on the follower requests it itself
 * (`setRequestedOrientation(REVERSE_LANDSCAPE)`). Every other pose hands back the activity's own
 * declared orientation, so the platform's native rotation keeps its speed. The Portal+ (sw1280)
 * never enables the sensor at all.
 */
internal object RotationFollower {
  private const val TAG = "RotationFollower"
  private const val PREFS = "hearthpane_rotation"
  private const val KEY_IGNORED = "platform_ignored_poses"
  /** An activity switch pauses one and resumes the next within this; only then stop listening. */
  private const val IDLE_STOP_MS = 800L

  private val handler = Handler(Looper.getMainLooper())
  private val policy = RotationFollowPolicy()
  private lateinit var app: Application
  private var listener: OrientationEventListener? = null
  private var resumed: WeakReference<Activity>? = null
  private val live = mutableListOf<WeakReference<Activity>>()
  private val declared = HashMap<String, Int>()
  /** What the follower last requested per activity (null = never overridden: its declared value). */
  private val requested = java.util.WeakHashMap<Activity, Int>()
  private val gate = RotationListenGate()
  private var ignored: Set<RotationPose> = emptySet()
  private var settledAt = 0L
  /** Operator override from the Fleet `orientation` action (null = follow the device). */
  private var forced: Int? = null

  private val tick = Runnable { onDecision(policy.onTick(SystemClock.uptimeMillis())) }
  private val stop = Runnable { if (gate.onIdleStop()) stopListening() }
  private val probe = Runnable { probePlatform() }

  fun install(application: Application) {
    app = application
    ignored = loadIgnored(application)
    // Auto-rotate switched in Settings takes effect at once, not at the next activity resume.
    application.contentResolver.registerContentObserver(
        Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false,
        object : android.database.ContentObserver(handler) {
          override fun onChange(selfChange: Boolean) {
            val front = resumed?.get() ?: return
            if (gate.onSettingChanged(eligible(front))) startListening() else stopListening()
            apply(front)
          }
        })
    application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
      override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        live.removeAll { it.get() == null }
        live += WeakReference(activity)
        // Open in the current pose: no portrait frame before the follower catches up.
        apply(activity)
      }
      override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
        handler.removeCallbacks(stop)
        if (gate.onResumed(eligible(activity))) startListening() else stopListening()
        apply(activity)
      }
      override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
        gate.onPaused()
        handler.removeCallbacks(stop)
        handler.postDelayed(stop, IDLE_STOP_MS)
      }
      override fun onActivityDestroyed(activity: Activity) {
        live.removeAll { it.get() == null || it.get() === activity }
      }
      override fun onActivityStarted(activity: Activity) = Unit
      override fun onActivityStopped(activity: Activity) = Unit
      override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    })
  }

  /** Compact display, auto-rotate on, and an activity whose declared orientation may be followed. */
  private fun eligible(activity: Activity): Boolean {
    if (activity.resources.configuration.smallestScreenWidthDp >= LayoutClass.COMPACT_SHORT_SIDE_DP) return false
    val autoRotate = runCatching {
      Settings.System.getInt(activity.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
    }.getOrDefault(false)
    return (autoRotate || forced != null) && RotationPlatformProbe.follows(declaredOf(activity))
  }

  private fun declaredOf(activity: Activity): Int = declared.getOrPut(activity.javaClass.name) {
    runCatching { activity.packageManager.getActivityInfo(activity.componentName, 0).screenOrientation }
        .getOrDefault(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
  }

  private fun startListening() {
    if (listener != null) return
    val l = object : OrientationEventListener(app, SensorManager.SENSOR_DELAY_NORMAL) {
      override fun onOrientationChanged(orientation: Int) {
        onDecision(policy.onAngle(orientation, SystemClock.uptimeMillis()))
      }
    }
    if (!l.canDetectOrientation()) return
    l.enable()
    listener = l
  }

  private fun stopListening() {
    listener?.disable()
    listener = null
    handler.removeCallbacks(tick)
    handler.removeCallbacks(probe)
    // Forget the pose and hand every activity we overrode back its own orientation.
    policy.reset()
    if (forced == null) live.mapNotNull { it.get() }.forEach(::restoreDeclared)
  }

  private fun onDecision(decision: RotationFollowPolicy.Decision) {
    when (decision) {
      RotationFollowPolicy.Decision.None -> Unit
      is RotationFollowPolicy.Decision.Wait -> {
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, (decision.untilMs - SystemClock.uptimeMillis()).coerceAtLeast(0))
      }
      is RotationFollowPolicy.Decision.Settled -> {
        handler.removeCallbacks(tick)
        settledAt = SystemClock.uptimeMillis()
        resumed?.get()?.let(::apply)
        handler.removeCallbacks(probe)
        if (decision.pose !in ignored) handler.postDelayed(probe, RotationPlatformProbe.GRACE_MS)
      }
    }
  }

  /** Did the platform leave the settled pose unrotated? Then it ignores that pose: take it over. */
  private fun probePlatform() {
    val activity = resumed?.get() ?: return
    val pose = policy.settled ?: return
    if (pose in ignored || forced != null) return
    // Only judge the platform while the activity is on its own declared, pose-allowing orientation.
    val own = declaredOf(activity)
    if (!RotationPlatformProbe.allows(own, pose) || (requested[activity] ?: own) != own) return
    @Suppress("DEPRECATION")
    val rotation = activity.windowManager.defaultDisplay.rotation
    if (!RotationPlatformProbe.ignored(pose, rotation, SystemClock.uptimeMillis() - settledAt)) return
    ignored = ignored + pose
    saveIgnored(activity, ignored)
    Log.i(TAG, "platform ignores $pose (display rotation $rotation); following it in-app")
    apply(activity)
  }

  private fun apply(activity: Activity) {
    val want = forced ?: run {
      if (activity.resources.configuration.smallestScreenWidthDp >= LayoutClass.COMPACT_SHORT_SIDE_DP) return
      val pose = policy.settled ?: return
      RotationPlatformProbe.requestFor(pose, ignored, declaredOf(activity)) ?: return
    }
    request(activity, want)
  }

  /** One place sets the orientation and remembers it (no binder read-back of requestedOrientation). */
  private fun request(activity: Activity, want: Int) {
    if ((requested[activity] ?: declaredOf(activity)) == want) return
    runCatching { activity.requestedOrientation = want }
        .onSuccess { requested[activity] = want }
        .onFailure { Log.w(TAG, "setRequestedOrientation failed (${it.javaClass.simpleName})") }
  }

  private fun restoreDeclared(activity: Activity) {
    if (requested[activity] != null) request(activity, declaredOf(activity))
  }

  /**
   * Fleet `orientation` action (operator test hook): `request` = portrait | landscape |
   * reverse_landscape forces every activity of ours; `auto` returns to following the device;
   * `forget` also clears the learned ignored poses. Returns the follower's state.
   */
  fun operator(context: Context, request: String?): JSONObject {
    handler.post {
      when (request) {
        "portrait" -> force(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        "landscape" -> force(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        "reverse_landscape" -> force(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)
        "auto" -> release()
        "forget" -> {
          ignored = emptySet()
          saveIgnored(context, ignored)
          release()
        }
      }
    }
    // The handler applies the request next; report the state it leads to.
    val nextForced: Any = when (request) {
      "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
      "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
      "reverse_landscape" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
      "auto", "forget" -> JSONObject.NULL
      else -> forced ?: JSONObject.NULL
    }
    return JSONObject().put("request", request ?: "").put("forced", nextForced)
        .put("settled", policy.settled?.name ?: JSONObject.NULL)
        .put("listening", listener != null)
        .put("ignoredPoses", JSONArray(ignored.map { it.name }))
  }

  private fun force(orientation: Int) {
    forced = orientation
    live.mapNotNull { it.get() }.forEach(::apply)
  }

  private fun release() {
    forced = null
    live.mapNotNull { it.get() }.forEach { a ->
      val back = policy.settled?.let { RotationPlatformProbe.requestFor(it, ignored, declaredOf(a)) } ?: declaredOf(a)
      request(a, back)
    }
    resumed?.get()?.let { if (gate.onResumed(eligible(it))) startListening() }
  }

  private fun loadIgnored(context: Context): Set<RotationPose> =
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_IGNORED, emptySet()).orEmpty()
          .mapNotNull { name -> RotationPose.entries.firstOrNull { it.name == name } }.toSet()

  private fun saveIgnored(context: Context, poses: Set<RotationPose>) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putStringSet(KEY_IGNORED, poses.map { it.name }.toSet()).apply()
  }
}

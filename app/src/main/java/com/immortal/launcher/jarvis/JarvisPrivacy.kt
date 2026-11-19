/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import android.app.Activity
import android.app.Application
import android.os.Bundle
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * What Jarvis must know before it may take its one screenshot for "what's on my screen?" in another app: whether any
 * Hearthpane screen is up (Home and the photo frame never send pixels, D7; every Hearthpane screen answers with
 * `screen_context` instead), the dream, a Hearthpane call, camera 0, and the doorbell's live camera card. Jarvis asks
 * through [HearthpaneToolProvider]'s `privacy` method right before it captures; it is not a model tool. Facts only;
 * Jarvis decides. Read on a binder thread, so every input is a volatile or atomic read.
 */
internal object JarvisPrivacy {
  const val METHOD = "privacy"

  /** One snapshot. [screen] is the registered Hearthpane screen in front, if any. */
  data class Facts(
      val screen: String?,
      /** Any Hearthpane Activity is started (visible, possibly paused behind Jarvis's card). */
      val launcherInFront: Boolean,
      val dreaming: Boolean,
      val callActive: Boolean,
      val cameraInUse: Boolean,
      /** The doorbell card shows a live camera feed over whatever is in front. */
      val cameraFeedShowing: Boolean,
  )

  private val started = AtomicInteger()

  /** Counts Hearthpane's started Activities; registered once for the process ([install]). */
  private val counter = object : Application.ActivityLifecycleCallbacks {
    override fun onActivityStarted(activity: Activity) { started.incrementAndGet() }
    override fun onActivityStopped(activity: Activity) { started.updateAndGet { (it - 1).coerceAtLeast(0) } }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
  }

  fun install(application: Application) = application.registerActivityLifecycleCallbacks(counter)

  /** Whether a Hearthpane Activity is started right now. */
  fun launcherStarted(): Boolean = started.get() > 0

  /** The facts as the provider returns them; snake_case keys mirror Jarvis's `HearthpanePrivacy` reader. */
  fun json(facts: Facts): JSONObject = JSONObject()
      .put("screen", facts.screen ?: JSONObject.NULL)
      .put("launcher_in_front", facts.launcherInFront)
      .put("dreaming", facts.dreaming)
      .put("call_active", facts.callActive)
      .put("camera_in_use", facts.cameraInUse)
      .put("camera_feed_showing", facts.cameraFeedShowing)
}

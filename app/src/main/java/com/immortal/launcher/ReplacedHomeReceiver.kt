/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * MY_PACKAGE_REPLACED: the new process brings Home back over Meta's launcher ([ReplacedHomeRestore]).
 * Delivered after Android unfreezes the package, to the new process (it may already be running
 * for a rebound listener or accessibility service).
 */
class ReplacedHomeReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (!ReplacedHomeRestore.handles(intent.action)) return
    val pending = goAsync()
    val app = context.applicationContext ?: context
    try {
      ReplacedHomeRestore(AndroidHost(app)).run { pending.finish() }
    } catch (e: RuntimeException) {
      Log.w(TAG, "package replaced: Home restore failed", e)
      pending.finish()
    }
  }

  private class AndroidHost(private val context: Context) : ReplacedHomeHost {
    private val main = Handler(Looper.getMainLooper())

    override fun isDefaultHome(): Boolean = runCatching {
      context.packageManager.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
          ?.activityInfo?.packageName == context.packageName
    }.onFailure { Log.w(TAG, "package replaced: couldn't resolve HOME", it) }.getOrDefault(false)

    override fun inCall(): Boolean = RoutineScheduler.busy(context)

    override fun ownActivityResumed(): Boolean = OwnActivityResumes.any()

    // The same HOME intent the boot path uses from its receiver; the package pin keeps it ours.
    override fun startHome(): Boolean = runCatching {
      context.startActivity(homeIntent().setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Log.w(TAG, "package replaced: Home start refused", it) }.isSuccess

    override fun schedule(delayMs: Long, block: () -> Unit) {
      main.postDelayed(block, delayMs)
    }

    override fun log(message: String) {
      Log.i(TAG, message)
    }

    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
  }

  private companion object {
    const val TAG = "HearthpaneReplacedHome"
  }
}

/** Counts this process's resumed activities, for [ReplacedHomeReceiver]. Main thread callbacks. */
internal object OwnActivityResumes : Application.ActivityLifecycleCallbacks {
  private val resumed = AtomicInteger()

  fun install(application: Application) = application.registerActivityLifecycleCallbacks(this)

  fun any(): Boolean = resumed.get() > 0

  override fun onActivityResumed(activity: Activity) {
    resumed.incrementAndGet()
  }

  override fun onActivityPaused(activity: Activity) {
    resumed.updateAndGet { (it - 1).coerceAtLeast(0) }
  }

  override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
  override fun onActivityStarted(activity: Activity) = Unit
  override fun onActivityStopped(activity: Activity) = Unit
  override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
  override fun onActivityDestroyed(activity: Activity) = Unit
}

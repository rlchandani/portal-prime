/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Launchable-app data for the phone remote's app grid — the headless, JSON/PNG
 * counterpart to the Compose [AppSwitcherActivity] drawer. Same curation rules
 * ([Curation], minus Immortal itself) so the two surfaces agree on what's an "app".
 */
object RemoteApps {

  private val iconCache = RemoteAppIconCache<Context>(subscribe = subscribe@ { context, invalidate ->
    val app = context.applicationContext ?: return@subscribe false
    val filter = IntentFilter().apply {
      addAction(Intent.ACTION_PACKAGE_ADDED)
      addAction(Intent.ACTION_PACKAGE_REMOVED)
      addAction(Intent.ACTION_PACKAGE_CHANGED)
      addAction(Intent.ACTION_PACKAGE_REPLACED)
      addDataScheme("package")
    }
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) { invalidate() }
    }
    ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    true
  })

  /** All launchable apps as `[{label, packageName}]`, alphabetised and curated. */
  fun listJson(context: Context): JSONArray {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val arr = JSONArray()
    pm.queryIntentActivities(intent, 0)
        .asSequence()
        .map { it.activityInfo.packageName }
        .filter { it != context.packageName && !Curation.isHidden(it, "") }
        .distinct()
        .mapNotNull { pkg ->
          runCatching {
                val ai = pm.getApplicationInfo(pkg, 0)
                val raw = pm.getApplicationLabel(ai).toString()
                if (raw in Curation.hiddenLabels) null
                else pkg to Curation.displayLabel(pkg, raw)
              }
              .getOrNull()
        }
        .sortedBy { it.second.lowercase(Locale.getDefault()) }
        .forEach { (pkg, label) ->
          arr.put(JSONObject().put("packageName", pkg).put("label", label))
        }
    return arr
  }

  /** A package's launcher icon as PNG bytes (square [sizePx]), or null if unavailable. */
  fun iconPng(context: Context, pkg: String, sizePx: Int = 144): ByteArray? =
      runCatching {
            fun render(): ByteArray {
              val drawable = context.packageManager.getApplicationIcon(pkg)
              val bmp: Bitmap = drawable.toBitmap(sizePx, sizePx)
              return ByteArrayOutputStream().use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                it.toByteArray()
              }
            }
            val key = runCatching { RemoteAppIconKey(context.packageName, pkg, sizePx,
                context.resources.configuration.toString()) }.getOrNull()
            if (key == null) render() else iconCache.png(context, key,
                canRetain = { context.resources.configuration.toString() == key.resources },
                loader = ::render)
          }
          .getOrNull()

  /** Launch a package by its leanback/standard launcher intent. Returns false if it can't. */
  fun launch(context: Context, pkg: String): Boolean {
    val intent =
        context.packageManager.getLaunchIntentForPackage(pkg)
            ?: context.packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: return false
    return runCatching {
      if (!dismissDreamOnMain()) false
      else {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
      }
    }.getOrDefault(false)
  }

  private fun dismissDreamOnMain(): Boolean {
    val dismiss = {
      // The selected app owns this navigation. Finish only Immortal's owned frame surfaces and
      // record the deliberate exit before its recovery callback can run.
      ImmortalDreamNavigation.dismissForNavigation()
      true
    }
    if (Looper.myLooper() == Looper.getMainLooper()) return dismiss()

    // HTTP and scene workers are off-main; frame lifecycle calls need main.
    // Launch afterwards on the caller, so timed-out work cannot open an app later.
    val main = Handler(Looper.getMainLooper())
    val task = FutureTask(dismiss)
    if (!main.post(task)) return false
    return try {
      task.get(2, TimeUnit.SECONDS)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      false
    } catch (_: Exception) {
      false
    } finally {
      // Discard a queued dismissal if the main thread did not process it in time.
      task.cancel(false)
      main.removeCallbacks(task)
    }
  }
}

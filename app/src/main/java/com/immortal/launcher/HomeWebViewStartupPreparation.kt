/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.MessageQueue
import android.view.View
import android.view.ViewTreeObserver
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Default-off experiment. Snapshot reads do not create a coordinator, executor or WebView. */
internal object WebViewStartupPreparation {
  @Volatile private var processCoordinator: WebViewStartupCoordinator? = null

  /** Main-only lifecycle entry. Retain application Context, never the Home Activity. */
  fun scheduleForHome(context: Context, decorView: View): () -> Unit {
    if (!BuildConfig.PORTAL_WEBVIEW_BACKGROUND_STARTUP) return {}
    val appContext = context.applicationContext
    val coordinator = processCoordinator ?: WebViewStartupCoordinator(
        enabled = true,
        newExecutor = {
          // Core size zero means an idle worker expires even if a provider never calls back.
          ThreadPoolExecutor(0, 1, 15L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>()) {
            task -> Thread(task, "immortal-webview-startup").apply { isDaemon = true }
          }
        },
        startUp = { executor, runUiThreadTasks, complete ->
          check(!runUiThreadTasks)
          WebViewCompat.startUpWebView(appContext, backgroundOnlyWebViewConfig(executor),
              object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
                override fun onResult(result: WebViewStartUpResult) { complete(null) }
                override fun onError(error: WebViewStartupException) { complete(error) }
              })
        }).also { processCoordinator = it }
    return coordinator.request(
        schedule = { action, rejected ->
          HomeDrawIdleOpportunity(decorView, action, rejected).register()
        },
        // onResume's existing SettingsGuard has already loaded these prefs. This reads only
        // the cheap immutable snapshot at admission; native modes never touch AndroidX WebKit.
        isRelevant = { webViewStartupRelevant(ScreensaverConfig.load(appContext)) })
  }

  fun snapshot(): WebViewStartupState = processCoordinator?.snapshot() ?: WebViewStartupState()
}

/** Configuration construction is provider-free; the UI portion is always explicitly disabled. */
internal fun backgroundOnlyWebViewConfig(executor: Executor): WebViewStartUpConfig =
    WebViewStartUpConfig.Builder(executor).setShouldRunUiThreadStartUpTasks(false).build()

/**
 * One draw, one settling timer, one idle opportunity. All registration/removal runs on Main.
 * onDraw posts removal because ViewTreeObserver forbids removing a draw listener during draw.
 * The coordinator's token also rejects callbacks already queued when Home leaves.
 */
private class HomeDrawIdleOpportunity(
    private val view: View,
    private val action: () -> Unit,
    private val rejected: () -> Unit,
) {
  private val handler = Handler(Looper.getMainLooper())
  private var drawObserver: ViewTreeObserver? = null
  private var idleQueue: MessageQueue? = null
  private var drawn = false
  private var cancelled = false
  private val idleHandler = MessageQueue.IdleHandler {
    if (!cancelled) {
      cancel()
      action()
    }
    false
  }
  private val settle = Runnable {
    guardScheduling {
      if (!cancelled) {
        idleQueue = Looper.myQueue()
        idleQueue?.addIdleHandler(idleHandler)
      }
    }
  }
  private val afterDraw = Runnable {
    guardScheduling {
      removeDrawListener()
      if (!cancelled && !handler.postDelayed(settle, 1_000L)) fail()
    }
  }
  private val drawListener = ViewTreeObserver.OnDrawListener {
    guardScheduling {
      if (!cancelled && !drawn) {
        drawn = true
        if (!handler.post(afterDraw)) fail()
      }
    }
  }

  fun register(): () -> Unit {
    try {
      val observer = view.viewTreeObserver
      check(observer.isAlive)
      drawObserver = observer
      observer.addOnDrawListener(drawListener)
    } catch (error: Exception) {
      cancel()
      throw error
    } catch (error: LinkageError) {
      cancel()
      throw error
    }
    return ::cancel
  }

  private fun removeDrawListener() {
    val registered = drawObserver ?: return
    drawObserver = null
    val observer = if (registered.isAlive) registered else view.viewTreeObserver
    if (observer.isAlive) observer.removeOnDrawListener(drawListener)
  }

  private fun cancel() {
    if (cancelled) return
    cancelled = true
    ignoreCleanupFailure { handler.removeCallbacks(afterDraw) }
    ignoreCleanupFailure { handler.removeCallbacks(settle) }
    ignoreCleanupFailure { idleQueue?.removeIdleHandler(idleHandler) }
    idleQueue = null
    ignoreCleanupFailure { removeDrawListener() }
  }

  private fun fail() {
    cancel()
    rejected()
  }

  private fun guardScheduling(action: () -> Unit) {
    try { action() }
    catch (_: Exception) { fail() }
    catch (_: LinkageError) { fail() }
  }

  private fun ignoreCleanupFailure(action: () -> Unit) {
    try { action() }
    catch (_: Exception) { /* The coordinator rejects the cancelled token independently. */ }
    catch (_: LinkageError) { /* A removed Home cannot admit provider work. */ }
  }
}

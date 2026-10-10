/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService

/** Content-free process state; completed means the background-only request succeeded. */
internal data class WebViewStartupState(
    val pending: Boolean = false,
    val attempted: Boolean = false,
    val completed: Boolean = false,
    val failed: Boolean = false,
)

/**
 * One process-owned background preparation. Home request/cancel and the deferred scheduler
 * run on Main; completion and snapshot reads may run on any thread. The scheduler must defer
 * admission until Home has drawn and settled. No Android Views or provider APIs are owned here.
 */
internal class WebViewStartupCoordinator(
    private val enabled: Boolean,
    private val newExecutor: () -> ExecutorService,
    private val startUp: (Executor, Boolean, (Throwable?) -> Unit) -> Unit,
) {
  private class Registration(var cancel: (() -> Unit)? = null)
  private val lock = Any()
  private var state = WebViewStartupState()
  private var terminal = false
  private var pending: Registration? = null
  private var executor: ExecutorService? = null

  fun request(
      schedule: (() -> Unit, () -> Unit) -> (() -> Unit)?,
      isRelevant: () -> Boolean,
  ): () -> Unit {
    val registration = Registration()
    val oldCancellation = synchronized(lock) {
      if (!enabled || terminal) return {}
      val old = takeCancellation(pending)
      pending = registration
      state = state.copy(pending = true)
      old
    }
    cancelSafely(oldCancellation)
    val cancellation = try {
      schedule({ admit(registration, isRelevant) }, { failRegistration(registration) })
    } catch (_: Exception) {
      failRegistration(registration)
      return {}
    } catch (_: LinkageError) {
      failRegistration(registration)
      return {}
    }
    if (cancellation == null) {
      failRegistration(registration)
      return {}
    }
    val retained = synchronized(lock) {
      if (pending === registration && !terminal) {
        registration.cancel = cancellation
        true
      } else false
    }
    if (!retained) cancelSafely(cancellation)
    return { cancel(registration) }
  }

  private fun admit(registration: Registration, isRelevant: () -> Boolean) {
    if (synchronized(lock) { pending !== registration || terminal }) return
    val relevant = try {
      isRelevant()
    } catch (_: Exception) {
      failRegistration(registration)
      return
    } catch (_: LinkageError) {
      failRegistration(registration)
      return
    }
    val cancellation = synchronized(lock) {
      if (pending !== registration || terminal) return
      pending = null
      state = state.copy(pending = false, attempted = relevant)
      // Cancellation or native-mode ineligibility leaves the budget for a later Home entry.
      if (relevant) terminal = true
      takeCancellation(registration)
    }
    cancelSafely(cancellation)
    if (relevant) start()
  }

  private fun start() {
    val owned = try {
      newExecutor()
    } catch (error: Exception) {
      complete(error)
      return
    } catch (error: LinkageError) {
      complete(error)
      return
    }
    synchronized(lock) { executor = owned }
    // Legacy-provider work can throw before WebKit delivers its outcome callback.
    // Guard the actual submitted tasks as well as the synchronous API call.
    val guarded = Executor { command ->
      try {
        owned.execute {
          try { command.run() }
          catch (error: Exception) { complete(error) }
          catch (error: LinkageError) { complete(error) }
        }
      } catch (error: Exception) {
        complete(error)
      } catch (error: LinkageError) {
        complete(error)
      }
    }
    try { startUp(guarded, false, ::complete) }
    catch (error: Exception) { complete(error) }
    catch (error: LinkageError) { complete(error) }
  }

  private fun complete(error: Throwable?) {
    // Fatal VM errors and unrelated Error types must retain normal process-failure semantics.
    if (error != null && error !is Exception && error !is LinkageError) throw error
    val owned = synchronized(lock) {
      if (!state.attempted || state.completed || state.failed) return
      state = state.copy(completed = error == null, failed = error != null)
      executor.also { executor = null }
    }
    // Atomically transfer ownership once; never wait for tasks or interrupt provider work.
    try { owned?.shutdown() }
    catch (_: Exception) { /* The owned pool also expires idle workers. */ }
    catch (_: LinkageError) { /* Preparation remains terminal. */ }
  }

  private fun failRegistration(registration: Registration) {
    val cancellation = synchronized(lock) {
      if (pending !== registration || terminal) return
      pending = null
      terminal = true
      state = state.copy(pending = false, failed = true)
      takeCancellation(registration)
    }
    cancelSafely(cancellation)
  }

  private fun cancel(registration: Registration) {
    val cancellation = synchronized(lock) {
      if (pending !== registration) return
      pending = null
      state = state.copy(pending = false)
      takeCancellation(registration)
    }
    cancelSafely(cancellation)
  }

  /** Called under lock; old Home tokens cannot retain or remove a newer registration. */
  private fun takeCancellation(registration: Registration?): (() -> Unit)? =
      registration?.cancel.also { registration?.cancel = null }

  private fun cancelSafely(cancellation: (() -> Unit)?) {
    try { cancellation?.invoke() }
    catch (_: Exception) { /* The identity check still rejects stale admission. */ }
    catch (_: LinkageError) { /* No provider work has started. */ }
  }

  fun snapshot(): WebViewStartupState = synchronized(lock) { state }
}

internal fun webViewStartupRelevant(settings: ScreensaverConfig.Settings): Boolean =
    settings.enabled && (settings.usesWebUrl || ScreensaverConfig.usesEngine(settings.mode))

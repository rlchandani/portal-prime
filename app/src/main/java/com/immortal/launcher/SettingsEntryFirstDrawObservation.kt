/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.view.View
import android.view.ViewTreeObserver
import java.lang.ref.WeakReference

/** Main-owned, once-only observer. Capture expiry and Activity destruction both dispose it. */
internal class SettingsEntryFirstDrawObservation(
    view: View,
    report: () -> Unit,
) : ViewTreeObserver.OnPreDrawListener {
  private val view = WeakReference(view)
  private val originalObserver = WeakReference(view.viewTreeObserver)
  private var report: (() -> Unit)? = report
  private var disposed = false

  fun dispose() {
    if (disposed) return
    disposed = true
    // Decor attachment can merge listeners into a new observer and kill the original.
    val current = runCatching { view.get()?.viewTreeObserver }.getOrNull()
    val original = originalObserver.get()
    runCatching { current?.takeIf { it.isAlive }?.removeOnPreDrawListener(this) }
    if (original !== current)
      runCatching { original?.takeIf { it.isAlive }?.removeOnPreDrawListener(this) }
    report = null
    view.clear()
    originalObserver.clear()
  }

  override fun onPreDraw(): Boolean {
    if (!disposed) {
      val firstDraw = report
      dispose()
      // An optional diagnostic failure cannot cancel drawing.
      runCatching { firstDraw?.invoke() }
    }
    return true
  }
}

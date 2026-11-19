/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/** Interactive pages stay awake while visible; Android ignores this flag on hidden windows. */
@Composable
internal fun KeepInteractiveScreenAwake() {
    val view = LocalView.current
    DisposableEffect(view) {
        // A window that already keeps the screen on (Settings sets it before its first frame) needs
        // nothing: setting the view flag after the first frame forces a second window relayout.
        val flags = (view.context as? android.app.Activity)?.window?.attributes?.flags ?: 0
        if (flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0) return@DisposableEffect onDispose { }
        val before = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }
}

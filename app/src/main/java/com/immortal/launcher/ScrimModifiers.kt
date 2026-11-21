/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

/**
 * Tap-outside-to-close for a scrim that hosts its sheet as a child. A `clickable` here would merge
 * the whole sheet's text into one "Close" node; this exposes a single labelled Close action and
 * leaves the sheet's own nodes separate. Taps the sheet consumed (see [blockScrimTaps]) don't
 * reach it.
 */
@Composable
internal fun Modifier.dismissScrim(label: String = "Close", onDismiss: () -> Unit): Modifier {
  val latest by rememberUpdatedState(onDismiss)
  return pointerInput(Unit) { detectTapGestures { latest() } }
      .semantics {
        contentDescription = label
        role = Role.Button
        onClick(label) { latest(); true }
      }
}

/**
 * Keeps taps on a sheet or card from falling through to the dismissing scrim behind it, without
 * making the card a clickable (which would announce it as a button and merge its text).
 */
internal fun Modifier.blockScrimTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }

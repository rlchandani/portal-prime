/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.staticCompositionLocalOf

/** The shared note (Share to Portal) to draw in a free page-1 slot, or null when it isn't placed there. */
internal val LocalHomeNote = staticCompositionLocalOf<ShareHomeState?> { null }

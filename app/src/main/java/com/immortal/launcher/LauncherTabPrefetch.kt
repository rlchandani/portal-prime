/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Hidden launcher tabs composed ahead of their first visit, one per main-looper idle slot after
 * Home has settled, so opening Apps or Tools the first time only activates an existing page.
 */
internal object LauncherTabPrefetch {
  /** Leave Home's first frames and start-up loads alone for this long. */
  const val FIRST_DELAY_MS = 1_500L
  /** Spacing between two prefetched tabs, so each composition gets its own idle frame. */
  const val GAP_MS = 500L
  /** Home prefetches both secondary tabs; Music's WebView warm-up follows later (3 s after draw). */
  val HOME_TABS: List<ApprovedLauncherTab> = listOf(ApprovedLauncherTab.APPS, ApprovedLauncherTab.TOOLS)

  /** The next tab to compose ahead, in [prefetch] order, or null once all were visited. */
  fun next(prefetch: List<ApprovedLauncherTab>, visited: Set<ApprovedLauncherTab>): ApprovedLauncherTab? =
      prefetch.firstOrNull { it !in visited }
}

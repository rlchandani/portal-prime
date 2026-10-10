/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Keep notification Binder retrieval off the caller; discard a cancelled blocking read. */
internal suspend fun loadApprovedAppBadges(): Map<String, Int> =
    withContext(Dispatchers.IO) {
      currentCoroutineContext().ensureActive()
      val badges = MediaNotificationListenerService.activeCountsByPackage()
      currentCoroutineContext().ensureActive()
      badges
    }

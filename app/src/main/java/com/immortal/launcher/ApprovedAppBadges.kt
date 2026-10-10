/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Suspend boundary for Apps badges; baseline retrieval still runs synchronously on the caller. */
internal suspend fun loadApprovedAppBadges(): Map<String, Int> =
    MediaNotificationListenerService.activeCountsByPackage()

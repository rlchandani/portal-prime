/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** A snooze is an already accepted occurrence, independent of a consumed one-shot config. */
internal object AlarmDeliveryPolicy {
    fun shouldRing(enabled: Boolean, snooze: Boolean): Boolean = enabled || snooze
    fun shouldAdvanceSchedule(snooze: Boolean): Boolean = !snooze
}

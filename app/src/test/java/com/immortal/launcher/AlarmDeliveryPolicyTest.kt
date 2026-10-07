/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class AlarmDeliveryPolicyTest {
    @Test fun consumedOneShotStillRingsItsAcceptedSnooze() {
        assertTrue(AlarmDeliveryPolicy.shouldRing(enabled = false, snooze = true))
        assertFalse(AlarmDeliveryPolicy.shouldAdvanceSchedule(snooze = true))
    }

    @Test fun disabledRegularAlarmDoesNotRing() {
        assertFalse(AlarmDeliveryPolicy.shouldRing(enabled = false, snooze = false))
        assertTrue(AlarmDeliveryPolicy.shouldRing(enabled = true, snooze = false))
        assertTrue(AlarmDeliveryPolicy.shouldAdvanceSchedule(snooze = false))
    }
}

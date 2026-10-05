/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovedSourcesOperationCancellationTest {
    @Test fun cancellationRejectsAReplyThatWasAlreadyQueued() {
        val operation = ApprovedSourcesOperation()
        val request = operation.begin()
        operation.cancel()
        assertFalse(operation.accepts(request))
    }

    @Test fun retryRejectsTheEarlierReply() {
        val operation = ApprovedSourcesOperation()
        val earlier = operation.begin()
        val retry = operation.begin()
        assertFalse(operation.accepts(earlier))
        assertTrue(operation.accepts(retry))
    }

    @Test fun leavingCannotBeReopenedByARetry() {
        val operation = ApprovedSourcesOperation()
        val request = operation.begin()
        operation.close()
        assertFalse(operation.accepts(request))
        assertFalse(operation.accepts(operation.begin()))
    }

    @Test fun successfulConfirmationConsumesTheRequest() {
        val operation = ApprovedSourcesOperation()
        val request = operation.begin()
        assertTrue(operation.accepts(request))
        operation.cancel()
        assertFalse(operation.accepts(request))
    }
}

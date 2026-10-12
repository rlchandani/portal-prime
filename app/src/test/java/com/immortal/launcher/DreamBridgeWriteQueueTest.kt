/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class DreamBridgeWriteQueueTest {
    @Test fun submittingDoesNotRunDiskWorkOnTheCaller() {
        val workers = mutableListOf<() -> Unit>(); var writes = 0
        val queue = DreamBridgeWriteQueue({ workers.add(it) }, { it() })
        queue.submit({ writes++; true })
        assertEquals(0, writes); assertEquals(1, workers.size)
        workers.single()(); assertEquals(1, writes)
    }
    @Test fun completionWaitsForDurableWorkAndItsMainDispatcher() {
        val workers = mutableListOf<() -> Unit>(); val main = mutableListOf<() -> Unit>()
        val events = mutableListOf<String>()
        val queue = DreamBridgeWriteQueue({ workers.add(it) }, { main.add(it) })
        queue.submit({ events.add("committed"); true }) { events.add("completed:$it") }
        assertTrue(events.isEmpty()); workers.single()()
        assertEquals(listOf("committed"), events); main.single()()
        assertEquals(listOf("committed", "completed:true"), events)
    }
    @Test fun unsuccessfulCommitCannotBeReportedAsDurable() {
        var result: Boolean? = null
        DreamBridgeWriteQueue({ it() }, { it() }).submit({ false }) { result = it }
        assertEquals(false, result)
    }
    @Test fun diskExceptionIsReportedAsFailure() {
        var result: Boolean? = null
        DreamBridgeWriteQueue({ it() }, { it() }).submit({ error("fixture") }) { result = it }
        assertEquals(false, result)
    }
    @Test fun queuedMarksAndClearsKeepTheirSubmissionOrder() {
        val workers = mutableListOf<() -> Unit>(); val events = mutableListOf<String>()
        val queue = DreamBridgeWriteQueue({ workers.add(it) }, { it() })
        queue.submit({ events.add("mark"); true }); queue.submit({ events.add("clear"); true })
        assertTrue(events.isEmpty()); workers.forEach { it() }
        assertEquals(listOf("mark", "clear"), events)
    }
    @Test fun rejectedSubmissionDoesNotExecuteDiskWork() {
        var writes = 0
        val queue = DreamBridgeWriteQueue({ throw IllegalStateException("fixture") }, { it() })
        assertTrue(runCatching { queue.submit({ writes++; true }) }.isFailure)
        assertEquals(0, writes)
    }
}

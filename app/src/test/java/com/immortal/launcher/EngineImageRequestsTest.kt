/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class EngineImageRequestsTest {
  private class Fixture {
    val jobs = mutableListOf<(() -> Boolean) -> Unit>()
    var accepting = true
    var current = true
    val requests = EngineImageRequests { _, job ->
      if (accepting) jobs.add(job)
      accepting
    }
    fun run() = jobs.removeAt(0).invoke { current }
  }

  @Test fun simultaneousReadersShareOnePreparationAndItsResult() {
    val f = Fixture()
    val expected = File("synthetic-ready.jpg")
    var prepared = 0
    val first = f.requests.request("revision") { prepared++; expected }
    val second = f.requests.request("revision") { error("duplicate decoder") }
    assertSame(first, second)
    assertFalse(first.isDone)
    assertEquals(1, f.jobs.size)
    f.run()
    assertEquals(expected, first.get())
    assertEquals(expected, second.get())
    assertEquals(1, prepared)
  }

  @Test fun rejectedQueueCompletesWithoutFallingBackToAnUncheckedOriginal() {
    val f = Fixture()
    f.accepting = false
    assertNull(f.requests.request("revision") { error("rejected work ran") }.get())
    assertTrue(f.jobs.isEmpty())
  }

  @Test fun sharedReadersCompleteWithoutWaitingForAnUnrelatedQueuedPhoto() {
    val f = Fixture()
    val expected = File("visible-ready.jpg")
    val bridge = f.requests.request("visible") { expected }
    val unrelated = f.requests.request("another") { File("another-ready.jpg") }
    val intercepted = f.requests.request("visible") { error("duplicate visible preparation") }
    assertEquals(2, f.jobs.size)
    f.run()
    assertEquals(expected, bridge.get())
    assertEquals(expected, intercepted.get())
    assertFalse(unrelated.isDone)
    assertEquals(1, f.jobs.size)
  }

  @Test fun closingReleasesReadersAndRejectsLatePublication() {
    val f = Fixture()
    var prepared = false
    val result = f.requests.request("revision") { prepared = true; File("late.jpg") }
    f.requests.close()
    assertNull(result.get())
    f.run()
    assertFalse(prepared)
    assertNull(result.get())
    assertNull(f.requests.request("another") { error("closed decoder") }.get())
  }

  @Test fun anAlreadyRunningDecoderKeepsItsOwnershipButCannotPublishAfterClose() {
    val f = Fixture()
    val result = f.requests.request("revision") { f.requests.close(); File("late.jpg") }
    f.run()
    assertNull(result.get())
  }

  @Test fun cancelledOwnerNeverStartsPixelPreparation() {
    val f = Fixture()
    val result = f.requests.request("revision") { error("cancelled work ran") }
    f.current = false
    f.run()
    assertNull(result.get())
  }

  @Test fun failedPreparationReleasesTheSlotForAFutureRetry() {
    val f = Fixture()
    val first = f.requests.request("revision") { throw IllegalStateException("synthetic failure") }
    f.run()
    assertNull(first.get())
    val second = f.requests.request("revision") { File("retry.jpg") }
    assertNotSame(first, second)
    f.run()
    assertEquals(File("retry.jpg"), second.get())
  }
}

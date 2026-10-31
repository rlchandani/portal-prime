/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class EnginePhotoTransportsTest {
  @Test fun retirementInvalidatesLeaseBeforeDeferredDisconnectRuns() {
    val jobs = ArrayList<() -> Unit>()
    val owner = EnginePhotoTransports { jobs.add(it) }
    val lease = owner.register {}!!
    assertTrue(lease.isCurrent())
    owner.cancelCurrent()
    assertFalse(lease.isCurrent())
    assertEquals(1, jobs.size)
  }

  @Test fun cancelledHttpCallCannotConnectWhenExecutedLater() {
    java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
      server.soTimeout = 100
      val call = okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder()
          .url("http://127.0.0.1:${server.localPort}/synthetic-photo").build())
      val owner = EnginePhotoTransports { it() }
      owner.register { call.cancel() }
      owner.close()
      assertTrue(call.isCanceled())
      try {
        call.execute().close()
        fail("Cancelled call must not execute")
      } catch (_: java.io.IOException) { }
      try {
        server.accept().close()
        fail("Cancelled call connected")
      } catch (_: java.net.SocketTimeoutException) { }
    }
  }

  @Test fun closingSchedulesDisconnectWithoutDoingNetworkWorkOnTheCallingThread() {
    val jobs = ArrayList<() -> Unit>()
    val owner = EnginePhotoTransports { jobs.add(it) }
    var disconnected = 0
    owner.register { disconnected++ }
    owner.close()
    assertEquals(0, disconnected); assertEquals(1, jobs.size)
    jobs.removeAt(0).invoke()
    assertEquals(1, disconnected)
  }

  @Test fun finishedRequestsUnregisterAndAreNotDisconnectedAgain() {
    val jobs = ArrayList<() -> Unit>()
    val owner = EnginePhotoTransports { jobs.add(it) }
    owner.register { error("finished connection") }!!.close()
    owner.close()
    assertTrue(jobs.isEmpty())
  }

  @Test fun lateRegistrationCannotStartIoAndItsResourceIsClosedOffThread() {
    val jobs = ArrayList<() -> Unit>()
    val owner = EnginePhotoTransports { jobs.add(it) }
    owner.close()
    var closed = false
    assertNull(owner.register { closed = true })
    assertFalse(closed)
    jobs.removeAt(0).invoke()
    assertTrue(closed)
  }

  @Test fun hidingCancelsCurrentIoButAllowsNewRequestsAfterResume() {
    val jobs = ArrayList<() -> Unit>()
    val owner = EnginePhotoTransports { jobs.add(it) }
    var count = 0
    owner.register { count++ }
    owner.cancelCurrent()
    jobs.removeAt(0).invoke()
    assertEquals(1, count)
    assertNotNull(owner.register { count++ })
    owner.close()
    jobs.removeAt(0).invoke()
    assertEquals(2, count)
  }
}

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class HomeConnectivityTest {
  private val context = mock(Context::class.java)

  @Test fun probeRunsOnTheIoDispatcherNotTheCaller() {
    val pool = Executors.newSingleThreadExecutor { Thread(it, "connectivity-io") }
    try {
      val caller = Thread.currentThread().name
      var probedOn = ""
      val connected = runBlocking {
        HomeConnectivity.isConnected(context, pool.asCoroutineDispatcher()) { probedOn = Thread.currentThread().name; true }
      }
      assertTrue(connected)
      // Coroutine debug mode may suffix " @coroutine#N" to the thread name.
      assertTrue(probedOn, probedOn.startsWith("connectivity-io"))
      assertFalse(caller.startsWith("connectivity-io"))
    } finally { pool.shutdownNow() }
  }

  @Test fun noNetworkAndAFailingProbeBothReadAsDisconnected() {
    val pool = Executors.newSingleThreadExecutor()
    try {
      val io = pool.asCoroutineDispatcher()
      assertFalse(runBlocking { HomeConnectivity.isConnected(context, io) { false } })
      assertFalse(runBlocking { HomeConnectivity.isConnected(context, io) { throw SecurityException("no permission") } })
    } finally { pool.shutdownNow() }
  }
}

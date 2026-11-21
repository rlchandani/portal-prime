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

/** The per-call unstable-client wrapper, without Android: a fake client stands in for ContentProviderClient. */
class UnstableProviderTest {
  private class FakeClient { var closed = 0 }
  private class Died : RuntimeException("dead object")

  private val failures = mutableListOf<UnstableProvider.Failure>()

  private fun <T> run(acquire: () -> FakeClient?, block: (FakeClient) -> T?, closeFails: Boolean = false): T? =
      UnstableProvider.withClient("fixture.authority", acquire,
          close = { it.closed++; if (closeFails) throw IllegalStateException("close") }, block = block,
          isDeath = { it is Died }, onFailure = { _, failure, _ -> failures += failure })

  @Test fun successReturnsTheResultAndClosesTheClient() {
    val client = FakeClient()
    assertEquals("ok", run({ client }, { "ok" }))
    assertEquals(1, client.closed)
    assertTrue(failures.isEmpty())
  }

  @Test fun missingProviderIsUnavailableNotACrash() {
    assertNull(run<String>({ null }, { fail("block must not run without a client"); null }))
    assertEquals(listOf(UnstableProvider.Failure.UNAVAILABLE), failures)
  }

  @Test fun acquireFailureIsSwallowedAsFailed() {
    assertNull(run<String>({ throw SecurityException("not exported") }, { "never" }))
    assertEquals(listOf(UnstableProvider.Failure.FAILED), failures)
  }

  @Test fun providerDeathMidCallIsReportedAndTheClientStillCloses() {
    val client = FakeClient()
    assertNull(run<String>({ client }, { throw Died() }))
    assertEquals(1, client.closed)
    assertEquals(listOf(UnstableProvider.Failure.DIED), failures)
  }

  @Test fun otherProviderErrorsAreFailedAndClosed() {
    val client = FakeClient()
    assertNull(run<String>({ client }, { throw IllegalArgumentException("unknown method") }))
    assertEquals(1, client.closed)
    assertEquals(listOf(UnstableProvider.Failure.FAILED), failures)
  }

  @Test fun aCloseFailureNeverMasksTheResult() {
    val client = FakeClient()
    assertEquals("ok", run({ client }, { "ok" }, closeFails = true))
    assertEquals(listOf(UnstableProvider.Failure.FAILED), failures)
  }

  @Test fun everyCallAcquiresAFreshClientSoARestartedProviderIsReached() {
    val clients = mutableListOf<FakeClient>()
    val acquire = { FakeClient().also { clients += it } }
    assertNull(run<String>(acquire, { throw Died() }))
    assertEquals("back", run(acquire, { "back" }))
    assertEquals(2, clients.size)
    assertTrue(clients.all { it.closed == 1 })
  }

  /** Tripwire: no source may reach another app's provider through a stable ContentResolver reference again. */
  @Test fun noStableCrossAppProviderCallsRemain() {
    val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
    val stable = Regex("""(contentResolver|resolver)\s*\.\s*(call|query|acquireContentProviderClient|acquireProvider)\s*\(""")
    val offenders = root.walkTopDown().filter { it.extension == "kt" || it.extension == "java" }
        .flatMap { f -> f.readLines().mapIndexedNotNull { i, line -> if (stable.containsMatchIn(line)) "${f.name}:${i + 1}" else null } }
        .toList()
    assertTrue("Use UnstableProvider for cross-app providers: $offenders", offenders.isEmpty())
  }
}

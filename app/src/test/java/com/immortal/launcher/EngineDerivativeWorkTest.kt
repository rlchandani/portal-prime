/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class EngineDerivativeWorkTest {
  @Test fun `one running job and bounded pending queue across cache owners`() {
    val work=EngineDerivativeWork(2)
    val old=work.owner();val next=work.owner()
    val entered=CountDownLatch(1);val release=CountDownLatch(1);val done=CountDownLatch(1)
    try {
      assertTrue(old.submit("running") { entered.countDown();release.await(3,TimeUnit.SECONDS) })
      assertTrue(entered.await(3,TimeUnit.SECONDS))
      assertTrue(next.submit("one") { })
      assertTrue(next.submit("two") { done.countDown() })
      assertFalse(next.submit("overflow") { fail("Queue exceeded its bound") })
      assertEquals(1L,done.count)
      release.countDown();assertTrue(done.await(3,TimeUnit.SECONDS))
    } finally { release.countDown();old.close();next.close();work.close() }
  }
  @Test fun `close invalidates running result and drops queued jobs without waiting`() {
    val work=EngineDerivativeWork(4);val owner=work.owner()
    val entered=CountDownLatch(1);val release=CountDownLatch(1);val ended=CountDownLatch(1)
    var accepted=true
    val queuedRan=AtomicBoolean(false)
    val drained=CountDownLatch(1);val next=work.owner()
    try {
      owner.submit("running") { current ->
        entered.countDown();release.await(3,TimeUnit.SECONDS);accepted=current();ended.countDown()
      }
      assertTrue(entered.await(3,TimeUnit.SECONDS))
      owner.submit("queued") { queuedRan.set(true) }
      owner.close()
      assertEquals(1L,ended.count);assertFalse(owner.submit("after-close") { })
      assertTrue(next.submit("drained") { drained.countDown() })
      release.countDown();assertTrue(ended.await(3,TimeUnit.SECONDS));assertTrue(drained.await(3,TimeUnit.SECONDS))
      assertFalse(accepted);assertFalse(queuedRan.get())
    } finally { release.countDown();owner.close();next.close();work.close() }
  }
  @Test fun `duplicate requests do not consume another queue slot`() {
    val work=EngineDerivativeWork(1);val owner=work.owner()
    val entered=CountDownLatch(1);val release=CountDownLatch(1);val done=CountDownLatch(1)
    try {
      owner.submit("same") { entered.countDown();release.await(3,TimeUnit.SECONDS) }
      assertTrue(entered.await(3,TimeUnit.SECONDS))
      assertFalse(owner.submit("same") { fail("Duplicate ran") })
      assertTrue(owner.submit("different") { done.countDown() })
      release.countDown();assertTrue(done.await(3,TimeUnit.SECONDS))
    } finally { release.countDown();owner.close();work.close() }
  }
  @Test fun `tail-enqueued warming yields to already queued visible work`() {
    val work=EngineDerivativeWork(2);val owner=work.owner()
    val entered=CountDownLatch(1);val release=CountDownLatch(1);val done=CountDownLatch(1)
    val order=Collections.synchronizedList(ArrayList<String>())
    try {
      owner.submit("warm-zero") {
        entered.countDown();release.await(3,TimeUnit.SECONDS);order.add("warm-zero")
        owner.submit("warm-one") { order.add("warm-one");done.countDown() }
      }
      assertTrue(entered.await(3,TimeUnit.SECONDS))
      assertTrue(owner.submit("visible") { order.add("visible") })
      release.countDown();assertTrue(done.await(3,TimeUnit.SECONDS))
      assertEquals(listOf("warm-zero","visible","warm-one"),order)
    } finally { release.countDown();owner.close();work.close() }
  }

}

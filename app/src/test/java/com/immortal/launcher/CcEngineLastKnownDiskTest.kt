/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The disk cache ([CcLastKnownStore]/[CcLastKnownJson]) that extends the in-memory last-known cache
 * across process death, so even a cold open (the first after a reboot or a low-memory kill) draws at
 * once. Home Assistant still confirms or drops every restored entity, and the safety gate is unchanged.
 */
class CcEngineLastKnownDiskTest {
  private fun entity(id: String, state: String) =
      CcEntity(id, state, attrs = mapOf("friendly_name" to id.substringAfter('.')))

  @Test fun aColdOpenSeedsTheCachedPlanAndLastKnownStatesBeforeHomeAssistantAnswers() {
    val r = CcEngineRig {
      plan = CcEngineRig.PLAN
      persistedLastKnown = mapOf(
          "lock.front" to entity("lock.front", "locked"),
          "light.kitchen" to entity("light.kitchen", "on"))
    }
    // A fresh process: nothing is live yet and no socket has answered.
    r.engine.setVisible(true); r.sched.runDue()

    assertEquals("locked", r.state.entity("lock.front")?.state)
    assertEquals("on", r.state.entity("light.kitchen")?.state)
    assertEquals(setOf("lock.front", "light.kitchen"), r.state.unconfirmed)
    assertEquals("the cached plan is shown so the floor draws at once", CcEngineRig.PLAN, r.state.plan)
    assertTrue("the plan models are built before the subscription answers", r.state.planModels.isNotEmpty())
    assertEquals("a safety-critical restored entity shows as in flight", setOf("lock.front"), r.state.pending)
    assertNotNull("and actions on it are refused until HA confirms",
        CcLastKnown.refusal(r.state.entity("lock.front"), r.state.unconfirmed))
    assertTrue("the cache is consumed into live state", r.engine.lastKnown.isEmpty())
  }

  @Test fun aColdOpenWithNothingCachedDrawsEmptyAndNeverThrows() {
    val r = CcEngineRig()
    r.engine.setVisible(true); r.sched.runDue()
    assertTrue(r.state.entities.isEmpty())
    assertTrue(r.state.unconfirmed.isEmpty())
    assertNull(r.state.plan)
  }

  @Test fun theDiskCacheIsReadOnlyOncePerProcess() {
    val r = CcEngineRig { persistedLastKnown = mapOf("light.kitchen" to entity("light.kitchen", "on")) }
    r.engine.setVisible(true); r.sched.runDue()
    assertEquals("on", r.state.entity("light.kitchen")?.state)

    // A later disk change (e.g. another writer) must not be re-read mid-process.
    r.host.persistedLastKnown = mapOf("light.den" to entity("light.den", "off"))
    r.engine.setVisible(false); r.sched.runDue()
    r.engine.setVisible(true); r.sched.runDue()
    assertNull("the second open does not reload the cache", r.state.entity("light.den"))
  }

  @Test fun goingToTheBackgroundPersistsTheRetainedSetToDisk() {
    val r = CcEngineRig().apply { connectLive() }
    r.engine.setVisible(false); r.sched.runDue()
    val saved = r.host.savedLastKnown.last()
    assertEquals(setOf("light.kitchen", "lock.front", "camera.porch", "valve.main"), saved.keys)
    assertEquals("locked", saved.getValue("lock.front").state)
    assertEquals("the persisted copy mirrors the in-memory cache", r.engine.lastKnown, saved)
  }

  @Test fun theFullAnswerConfirmsADiskRestoredLockInPlace() {
    val r = CcEngineRig {
      plan = CcEngineRig.PLAN
      persistedLastKnown = mapOf("lock.front" to entity("lock.front", "locked"))
    }
    r.engine.setVisible(true); r.sched.runDue()
    assertTrue("lock.front" in r.state.unconfirmed)
    // HA connects and answers in full: the restored lock is confirmed and no longer in flight.
    r.handshake()
    r.reply("subscribe_entities")
    r.event("subscribe_entities", CcEngineRig.ENTITIES)
    r.advance(CcEngine.FLUSH_MS)
    assertTrue(r.state.unconfirmed.isEmpty())
    assertEquals("locked", r.state.entity("lock.front")?.state)
    assertTrue(r.state.pending.isEmpty())
  }
}

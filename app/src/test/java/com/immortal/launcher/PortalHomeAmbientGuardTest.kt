/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class PortalHomeAmbientGuardTest {
  private class Backend(var value: String? = "1") : PortalAmbientBackend {
    var token: SavedPortalAmbient? = null
    var saveOk = true
    var writeOk = true
    var clearOk = true
    val actions = mutableListOf<String>()
    override fun saved() = token
    override fun current() = value
    override fun save(original: String?): Boolean {
      actions += "save:$original"
      if (saveOk) token = SavedPortalAmbient(original)
      return saveOk
    }
    override fun write(value: String?): Boolean {
      actions += "write:$value"
      if (writeOk) this.value = value
      return writeOk
    }
    override fun clear(): Boolean {
      actions += "clear"
      if (clearOk) token = null
      return clearOk
    }
  }

  @Test fun callsDisablesAmbientOnlyAfterSavingTheOriginalValue() {
    val b = Backend(); assertTrue(PortalAmbientHandoff(b).begin())
    assertEquals(listOf("save:1", "write:0"), b.actions)
    assertEquals("0", b.value); assertEquals(SavedPortalAmbient("1"), b.token)
  }
  @Test fun deliberateReturnRestoresOriginalAndClearsToken() {
    val b = Backend(); val h = PortalAmbientHandoff(b)
    h.begin(); assertTrue(h.restore())
    assertEquals("1", b.value); assertNull(b.token)
    assertEquals(listOf("save:1", "write:0", "write:1", "clear"), b.actions)
  }
  @Test fun originallyAbsentSettingIsDeletedAgain() {
    val b = Backend(null); val h = PortalAmbientHandoff(b)
    h.begin(); assertEquals(SavedPortalAmbient(null), b.token)
    assertTrue(h.restore()); assertNull(b.value); assertNull(b.token)
  }
  @Test fun alreadyDisabledAmbientNeedsNoTokenOrWrite() {
    val b = Backend("0"); val h = PortalAmbientHandoff(b)
    assertTrue(h.begin()); assertTrue(h.restore()); assertTrue(b.actions.isEmpty())
  }
  @Test fun repeatedLaunchAndNewProcessKeepTheFirstRestorationToken() {
    val b = Backend(); PortalAmbientHandoff(b).begin()
    PortalAmbientHandoff(b).begin(); assertEquals(SavedPortalAmbient("1"), b.token)
    assertEquals(listOf("save:1", "write:0", "save:1"), b.actions)
    assertTrue(PortalAmbientHandoff(b).restore()); assertEquals("1", b.value)
  }
  @Test fun failedDurableCaptureCannotDisableAmbient() {
    val b = Backend(); b.saveOk = false
    assertFalse(PortalAmbientHandoff(b).begin()); assertEquals("1", b.value)
    assertNull(b.token); assertEquals(listOf("save:1"), b.actions)
  }
  @Test fun failedOverrideDropsTokenWhenTheOriginalIsUnchanged() {
    val b = Backend(); b.writeOk = false
    assertFalse(PortalAmbientHandoff(b).begin()); assertEquals("1", b.value)
    assertNull(b.token)
  }
  @Test fun failedRestorationKeepsTokenForNextHomeReturn() {
    val b = Backend(); val h = PortalAmbientHandoff(b); h.begin(); b.writeOk = false
    assertFalse(h.restore()); assertEquals("0", b.value)
    assertEquals(SavedPortalAmbient("1"), b.token)
    b.writeOk = true; assertTrue(PortalAmbientHandoff(b).restore()); assertEquals("1", b.value)
  }
  @Test fun unchangedOriginalAndFailedTokenClearAreRetriedWithoutRewriting() {
    val b = Backend(); val h = PortalAmbientHandoff(b); h.begin(); b.clearOk = false
    assertFalse(h.restore()); assertEquals("1", b.value)
    b.clearOk = true; assertTrue(h.restore())
    assertEquals(1, b.actions.count { it == "write:1" }); assertNull(b.token)
  }
  @Test fun ordinaryReturnsWithoutCallsDoNotChangeTheSystem() {
    val b = Backend(); assertTrue(PortalAmbientHandoff(b).restore())
    assertEquals("1", b.value); assertTrue(b.actions.isEmpty())
  }
  @Test fun aLaterExternalChangeIsPreservedInsteadOfOverwritten() {
    val b = Backend(null); val h = PortalAmbientHandoff(b); h.begin(); b.value = "1"
    assertTrue(h.restore()); assertEquals("1", b.value); assertNull(b.token)
    assertFalse(b.actions.contains("write:null"))
  }

  @Test fun repeatedFailedAtomicWritesNeverOverrideOrLoseTheOriginal() {
    val b = Backend(); b.saveOk = false
    assertFalse(PortalAmbientHandoff(b).begin()); assertNull(b.token)
    assertFalse(PortalAmbientHandoff(b).begin()); assertEquals("1", b.value)
    assertEquals(listOf("save:1", "save:1"), b.actions)
    b.saveOk = true; assertTrue(PortalAmbientHandoff(b).begin())
    assertEquals("0", b.value); assertTrue(PortalAmbientHandoff(b).restore()); assertEquals("1", b.value)
  }
  @Test fun reentryRebasesAnExternalChangeBeforeTheNewOverride() {
    val b = Backend(null); PortalAmbientHandoff(b).begin(); b.value = "1"
    assertTrue(PortalAmbientHandoff(b).begin()); assertEquals(SavedPortalAmbient("1"), b.token)
    assertTrue(PortalAmbientHandoff(b).restore()); assertEquals("1", b.value)
  }

  @Test fun tokenRoundTripPreservesAbsentAndExactOriginalValues() {
    for (original in listOf(null, "1", "0", "unusual\nvalue"))
      assertEquals(SavedPortalAmbient(original), decodePortalAmbient(encodePortalAmbient(original)))
  }
  @Test fun committedClearTombstoneCannotReappearAsAnActiveToken() {
    assertNull(decodePortalAmbient("-"))
  }
  @Test(expected = IllegalStateException::class) fun corruptTokenFailsClosed() {
    decodePortalAmbient("broken")
  }

}

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The on-disk form of Control Center's last-known states ([CcLastKnownJson]), pure. */
class CcLastKnownJsonTest {
  private val now = 2_000_000_000_000L

  private val sample = listOf(
      CcEntity("lock.front", "locked",
          attrs = mapOf("friendly_name" to "Front", "rgb_color" to listOf(255, 0, 0),
              "nested" to mapOf("k" to 1), "missing" to null),
          lastChanged = 1_999_999_000_000L, userId = "u1"),
      CcEntity("light.kitchen", "on", attrs = mapOf("friendly_name" to "Kitchen")))
      .associateBy { it.id }

  @Test fun aRoundTripRestoresEveryFieldIncludingNestedAttributes() {
    val text = CcLastKnownJson.encode(sample, savedAt = now).toString()
    assertEquals(sample, CcLastKnownJson.decode(text, now))
  }

  @Test fun restoredAttributesMatchTheLiveParsersNormalisation() {
    // A persisted entity must be indistinguishable from one the socket parser produced.
    val text = CcLastKnownJson.encode(sample, savedAt = now).toString()
    val lock = CcLastKnownJson.decode(text, now).getValue("lock.front")
    assertEquals(listOf(255, 0, 0), lock.attrs["rgb_color"])
    assertEquals(mapOf("k" to 1), lock.attrs["nested"])
    assertTrue("a null attribute is preserved", lock.attrs.containsKey("missing"))
    assertNull(lock.attrs["missing"])
    assertEquals("u1", lock.userId)
    assertEquals(1_999_999_000_000L, lock.lastChanged)
  }

  @Test fun aStaleCacheIsIgnoredSoDaysOldStatesAreNotPaintedAsCurrent() {
    val text = CcLastKnownJson.encode(sample, savedAt = now).toString()
    assertEquals("just inside the window still restores", sample,
        CcLastKnownJson.decode(text, now + CcLastKnownJson.MAX_AGE_MS))
    assertTrue("past the window restores nothing",
        CcLastKnownJson.decode(text, now + CcLastKnownJson.MAX_AGE_MS + 1).isEmpty())
  }

  @Test fun aPayloadFromAnotherVersionIsIgnored() {
    val text = CcLastKnownJson.encode(sample, savedAt = now)
        .put("v", CcLastKnownJson.VERSION + 1).toString()
    assertTrue(CcLastKnownJson.decode(text, now).isEmpty())
  }

  @Test fun malformedOrEmptyPayloadsNeverThrowAndRestoreNothing() {
    assertTrue(CcLastKnownJson.decode("not json at all", now).isEmpty())
    assertTrue(CcLastKnownJson.decode("", now).isEmpty())
    assertTrue(CcLastKnownJson.decode(JSONObject().toString(), now).isEmpty())
    assertTrue("no entities encode to an empty restore",
        CcLastKnownJson.decode(CcLastKnownJson.encode(emptyMap(), now).toString(), now).isEmpty())
  }

  @Test fun anEntryWithoutAUsableIdIsSkipped() {
    val text = CcLastKnownJson.encode(mapOf("light.kitchen" to CcEntity("light.kitchen", "on")), now)
        .also { it.getJSONArray("entities").getJSONObject(0).put("id", "no-dot") }
        .toString()
    assertTrue(CcLastKnownJson.decode(text, now).isEmpty())
  }
}

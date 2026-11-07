/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.RoutineFixtures.MON
import com.immortal.launcher.RoutineFixtures.doc
import com.immortal.launcher.RoutineFixtures.morning
import com.immortal.launcher.RoutineFixtures.routineJson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoutineMutationsTest {
  private val base = doc(morning())
  private fun apply(body: JSONObject) = RoutineMutations.apply(base, body, MON)

  @Test fun upsertAddsWithNewRevision() {
    val res = apply(JSONObject().put("action", "upsert").put("expectedRev", 3).put("routine", routineJson())) as RoutineMutations.Result.Ok
    assertEquals(4, res.doc.rev)
    assertEquals(listOf("r1", "r9"), res.doc.routines.map { it.id })
    assertEquals(3, base.rev) // input untouched
  }

  @Test fun upsertReplacesInPlace_andPrunesPastSkips() {
    val edit = RoutineCodec.routineJson(morning().copy(name = "Mornings", skips = listOf(MON - 1, MON + 2)))
    val res = apply(JSONObject().put("action", "upsert").put("expectedRev", 3).put("routine", edit)) as RoutineMutations.Result.Ok
    assertEquals("Mornings", res.doc.routines.single().name)
    assertEquals(listOf(MON + 2), res.doc.routines.single().skips)
  }

  @Test fun staleRevisionIsRejectedWithTheCurrentDoc() {
    val res = apply(JSONObject().put("action", "upsert").put("expectedRev", 2).put("routine", routineJson()))
    assertEquals(base, (res as RoutineMutations.Result.Stale).doc)
    assertEquals("missing_revision", (apply(JSONObject().put("action", "upsert").put("routine", routineJson())) as RoutineMutations.Result.Invalid).code)
  }

  @Test fun invalidAndOverlappingRoutinesAreRejected() {
    assertEquals("invalid_name", (apply(JSONObject().put("action", "upsert").put("expectedRev", 3).put("routine", routineJson(name = ""))) as RoutineMutations.Result.Invalid).code)
    val clash = routineJson(start = 440, end = 470, days = listOf(1, 0, 0, 0, 0, 0, 0))
    assertEquals("overlap", (apply(JSONObject().put("action", "upsert").put("expectedRev", 3).put("routine", clash)) as RoutineMutations.Result.Invalid).code)
    assertEquals("unknown_action", (apply(JSONObject().put("action", "nuke").put("expectedRev", 3)) as RoutineMutations.Result.Invalid).code)
  }

  @Test fun deleteRemoves_unknownIdIsNotFound() {
    val res = apply(JSONObject().put("action", "delete").put("expectedRev", 3).put("id", "r1")) as RoutineMutations.Result.Ok
    assertTrue(res.doc.routines.isEmpty())
    assertEquals("not_found", (apply(JSONObject().put("action", "delete").put("expectedRev", 3).put("id", "nope")) as RoutineMutations.Result.Invalid).code)
  }

  @Test fun skipTogglesADateInTheNextTwoWeeks() {
    val on = apply(JSONObject().put("action", "skip").put("expectedRev", 3).put("id", "r1").put("date", "2026-11-11").put("skip", true)) as RoutineMutations.Result.Ok
    assertEquals(listOf(MON + 2), on.doc.routines.single().skips)
    val off = RoutineMutations.apply(on.doc, JSONObject().put("action", "skip").put("expectedRev", 4).put("id", "r1").put("date", "2026-11-11").put("skip", false), MON) as RoutineMutations.Result.Ok
    assertTrue(off.doc.routines.single().skips.isEmpty())
    assertEquals("invalid_skip", (apply(JSONObject().put("action", "skip").put("expectedRev", 3).put("id", "r1").put("date", "2026-12-25").put("skip", true)) as RoutineMutations.Result.Invalid).code)
    assertEquals("invalid_skip", (apply(JSONObject().put("action", "skip").put("expectedRev", 3).put("id", "r1").put("date", "2026-11-08").put("skip", true)) as RoutineMutations.Result.Invalid).code)
  }

  @Test fun kidsReplaceTheListAndStripRemovedKidsFromRoutines() {
    val withKids = doc(morning(kids = listOf("maya", "leo")))
    val body = JSONObject().put("action", "kids").put("expectedRev", 3)
        .put("kids", JSONArray().put(JSONObject().put("id", "maya").put("name", "Maya").put("avatar", "fox")))
    val res = RoutineMutations.apply(withKids, body, MON) as RoutineMutations.Result.Ok
    assertEquals(listOf("maya"), res.doc.routines.single().kids)
    assertEquals(1, res.doc.kids.size)
  }

  @Test fun atMostTwelveRoutines() {
    var d = RoutineDoc(0, emptyList(), emptyList())
    repeat(12) { i ->
      d = (RoutineMutations.apply(d, JSONObject().put("action", "upsert").put("expectedRev", d.rev)
          .put("routine", routineJson(id = "r$i", start = i * 60, end = i * 60 + 30)), MON) as RoutineMutations.Result.Ok).doc
    }
    val res = RoutineMutations.apply(d, JSONObject().put("action", "upsert").put("expectedRev", d.rev)
        .put("routine", routineJson(id = "rx", start = 1300, end = 1330)), MON)
    assertEquals("too_many_routines", (res as RoutineMutations.Result.Invalid).code)
  }
}

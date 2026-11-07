/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.RoutineFixtures.MON
import com.immortal.launcher.RoutineFixtures.at
import com.immortal.launcher.RoutineFixtures.morning
import com.immortal.launcher.RoutineFixtures.routineJson
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoutinesApiTest {
  private class FakeStorage(var doc: String? = null, var runs: String? = null, var full: Boolean = false) : RoutineStorage {
    override fun readDoc() = doc
    override fun readRuns() = runs
    override fun writeDoc(json: String) = !full && true.also { doc = json }
    override fun writeRuns(json: String) = !full && true.also { runs = json }
  }
  private class FixedClock(var local: Long) : RoutineClock {
    override fun nowLocalMs() = local
    override fun toUtcMs(localMs: Long) = localMs
  }
  private class FakeActions : RoutineActions {
    var changed = 0; var closed: String? = null; var previewed: Routine? = null; var busy = false
    override fun changed() { changed++ }
    override fun closeFromPhone(rid: String) = (rid == "r1").also { if (it) closed = rid }
    override fun preview(routine: Routine) = !busy && true.also { previewed = routine }
  }

  private val storage = FakeStorage()
  private val clock = FixedClock(at(MON, 7, 0))
  private val repo = RoutineRepository(storage, clock)
  private val actions = FakeActions()
  private val api = RoutinesApi(repo, actions)

  private fun post(body: JSONObject) = api.handle("POST", body)
  private fun upsert(r: JSONObject, rev: Long) = post(JSONObject().put("action", "upsert").put("expectedRev", rev).put("routine", r))

  @Test fun getOnAFreshPortalIsEmptyRevisionZero() {
    val (status, body) = api.handle("GET", JSONObject())
    assertEquals(200, status)
    assertEquals(0L, body.getLong("rev"))
    assertEquals(0, body.getJSONArray("routines").length())
    assertEquals(MON, body.getInt("day"))
    assertEquals(420, body.getInt("minute"))
  }

  @Test fun upsertSavesBumpsRevisionAndReschedules() {
    val (status, body) = upsert(RoutineCodec.routineJson(morning()), 0)
    assertEquals(200, status)
    assertEquals(1L, body.getLong("rev"))
    assertEquals("r1", body.getJSONArray("routines").getJSONObject(0).getString("id"))
    assertEquals(1, actions.changed)
    assertEquals(1L, RoutineRepository(storage, clock).doc().rev) // persisted
  }

  @Test fun staleRevisionIs409WithTheCurrentDoc() {
    upsert(RoutineCodec.routineJson(morning()), 0)
    val (status, body) = upsert(routineJson(), 0)
    assertEquals(409, status)
    assertEquals("stale_revision", body.getString("error"))
    assertEquals(1L, body.getLong("rev"))
  }

  @Test fun invalidIs400_withAMessage() {
    upsert(RoutineCodec.routineJson(morning()), 0)
    val (status, body) = upsert(routineJson(start = 440, end = 470, days = listOf(1, 0, 0, 0, 0, 0, 0)), 1)
    assertEquals(400, status)
    assertEquals("overlap", body.getString("error"))
    assertEquals(0, actions.changed - 1)
  }

  @Test fun storageFullIs507_andNothingChanges() {
    storage.full = true
    val (status, body) = upsert(RoutineCodec.routineJson(morning()), 0)
    assertEquals(507, status)
    assertEquals("storage_full", body.getString("error"))
    assertEquals(0L, repo.doc().rev)
    assertEquals(0, actions.changed)
  }

  @Test fun unreadableStorageRefusesWrites() {
    storage.doc = "{not json"
    assertEquals(503, upsert(RoutineCodec.routineJson(morning()), 0).first)
  }

  @Test fun closeAndPreviewAck() {
    assertEquals(200, post(JSONObject().put("action", "close").put("id", "r1")).first)
    assertEquals("r1", actions.closed)
    assertEquals("not_running", post(JSONObject().put("action", "close").put("id", "r2")).second.getString("error"))
    assertEquals(200, post(JSONObject().put("action", "preview").put("routine", routineJson())).first)
    assertEquals("Swim", actions.previewed?.name)
    actions.busy = true
    val busy = post(JSONObject().put("action", "preview").put("routine", routineJson()))
    assertEquals(409, busy.first)
    assertEquals("busy", busy.second.getString("error"))
    assertEquals(400, post(JSONObject().put("action", "preview").put("routine", routineJson(name = ""))).first)
  }

  @Test fun getReportsTodaysRunsForThePhone() {
    upsert(RoutineCodec.routineJson(morning()), 0)
    clock.local = at(MON, 7, 20)
    val run = RoutineRunLogic.toggle(morning(), RoutineSchedule.startRun(morning(), at(MON, 7, 15), false), "c0", "all", clock.local).run
    assertTrue(repo.putRun(run))
    val today = api.handle("GET", JSONObject()).second.getJSONArray("today").getJSONObject(0)
    assertEquals("r1", today.getString("rid"))
    assertEquals("running", today.getString("status"))
    assertEquals(1, today.getInt("done"))
    assertEquals(5, today.getInt("total"))
    assertEquals(35, today.getInt("leftMin"))
  }

  @Test fun runsFromEarlierDaysAreDropped() {
    repo.putRun(RoutineSchedule.startRun(morning(), at(MON - 1, 7, 15), false))
    assertTrue(repo.runs().isEmpty())
  }

  @Test fun unknownMethodAndAction() {
    assertEquals(405, api.handle("PUT", JSONObject()).first)
    assertEquals(400, post(JSONObject().put("action", "explode")).first)
  }
}

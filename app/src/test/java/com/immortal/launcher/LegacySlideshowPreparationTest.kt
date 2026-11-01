/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import java.io.IOException
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.RejectedExecutionException
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/** Actual extracted preparation/admission behavior; queues control races without sleeps or Android UI. */
class LegacySlideshowPreparationTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test fun `JSON omits invalid regions while preserving point formatting`() {
    val invalid = listOf(FaceFocus.Bounds(Float.NaN, .2f, .7f, .9f),
        FaceFocus.Bounds(.7f, .2f, .1f, .9f), FaceFocus.Bounds(-.1f, .2f, .7f, .9f),
        FaceFocus.Bounds(.1f, .2f, .7f, 1.1f), FaceFocus.Bounds(.1f, .2f, .1f, .9f))
    val photos = JSONArray(LegacySlideshowPreparation.photosJson(
        invalid.indices.map { "fixture:$it" }, emptyMap(),
        { key -> FaceFocus.Focus(.1234567f, .8765432f, invalid[key.substringAfter(":").toInt()]) },
        { .7654f }))
    for (i in invalid.indices) {
      val photo = photos.getJSONObject(i)
      assertEquals(setOf("url", "fx", "fy", "pw"), photo.keys().asSequence().toSet())
      assertEquals(.123, photo.getDouble("fx"), 0.0)
      assertEquals(.877, photo.getDouble("fy"), 0.0)
      assertEquals(.77, photo.getDouble("pw"), 0.0)
    }
  }

  @Test fun `persisted face region crosses JSON bridge without inward rounding`() {
    temporary.root.resolve("face_focus.json").writeText(
        """{"fixture:region":"0.1234567,0.8765432,0.7654,0.1234567,0.2345678,0.8765432,0.9876543"}""")
    val context = Mockito.mock(Context::class.java)
    Mockito.`when`(context.filesDir).thenReturn(temporary.root)
    val store = FaceFocusStore(context)
    try {
      val json = LegacySlideshowPreparation.photosJson(
          listOf("fixture:region", "fixture:none", "fixture:region"), emptyMap(),
          store::focusFor, store::weightFor)
      val photos = JSONArray(json)
      assertEquals(listOf("fixture:region", "fixture:none", "fixture:region"),
          (0 until photos.length()).map { photos.getJSONObject(it).getString("url") })
      for (index in listOf(0, 2)) {
        val photo = photos.getJSONObject(index)
        val bounds = photo.getJSONArray("fb")
        assertEquals(4, bounds.length())
        assertEquals(.1234567f.toDouble(), bounds.getDouble(0), 0.0)
        assertEquals(.2345678f.toDouble(), bounds.getDouble(1), 0.0)
        assertEquals(.8765432f.toDouble(), bounds.getDouble(2), 0.0)
        assertEquals(.9876543f.toDouble(), bounds.getDouble(3), 0.0)
        assertEquals(.123, photo.getDouble("fx"), 0.0)
        assertEquals(.877, photo.getDouble("fy"), 0.0)
        assertEquals(.77, photo.getDouble("pw"), 0.0)
      }
      assertEquals(setOf("url"), photos.getJSONObject(1).keys().asSequence().toSet())
    } finally { store.close() }
  }

  @Test fun `cold persisted framing is read on the worker and admitted only on main`() {
    // Synthetic metadata only: no image files, image reads, transport or detector are involved.
    temporary.root.resolve("face_focus.json").writeText(
        """{"fixture:one":"0.1234567,0.8765432,0.7654"}""")
    val context = Mockito.mock(Context::class.java)
    Mockito.`when`(context.filesDir).thenReturn(temporary.root)
    val store = FaceFocusStore(context)
    val f = Fixture()
    val lookupRoles = ArrayList<String>()
    try {
      f.request { current ->
        LegacySlideshowPreparation.photosJson(listOf("fixture:one"), emptyMap(),
            { key -> lookupRoles.add(f.role); store.focusFor(key) },
            { key -> lookupRoles.add(f.role); store.weightFor(key) }, current)
      }
      assertEquals("Cache hydration ran before worker dispatch", emptyList<String>(), lookupRoles)
      assertTrue("A WebView admission ran on the requesting stack", f.admitted.isEmpty())
      f.runWorkers()
      assertEquals(listOf("worker", "worker"), lookupRoles)
      assertTrue("Worker preparation attached UI directly", f.admitted.isEmpty())
      f.runMain()
      assertEquals(listOf("main"), f.admissionRoles)
      val photo = JSONArray(f.admitted.single()).getJSONObject(0)
      assertEquals(.123, photo.getDouble("fx"), 0.0)
      assertEquals(.877, photo.getDouble("fy"), 0.0)
      assertEquals(.77, photo.getDouble("pw"), 0.0)
    } finally { f.preparation.close(); store.close() }
  }

  @Test fun `stop before a queued preparation avoids its metadata reads`() {
    val f = Fixture()
    var reads = 0
    f.request { reads++; "old" }
    f.preparation.close()
    f.runWorkers(); f.runMain()
    assertEquals("Stopped work still hydrated metadata", 0, reads)
    assertTrue(f.admitted.isEmpty())
    assertTrue(f.failures.isEmpty())
  }

  @Test fun `stop between preparation and admission cannot start cache-writing work`() {
    val f = Fixture()
    val file = temporary.root.resolve("owned-synthetic-metadata.json")
    val cache = FaceFocusCache(file)
    f.preparation.request(
        prepare = { "old" },
        onReady = { cache.record("fixture:old", "0.2,0.3,0.8"); cache.persist() },
        onFailure = { fail("Stop must not trigger source fallback") })
    f.runWorkers()
    f.preparation.close()
    f.runMain()
    assertFalse("A stale admission caused a real metadata write", file.exists())
  }

  @Test fun `a prepared old result cannot attach after a replacement request`() {
    val f = Fixture()
    f.request { "old" }
    f.runWorkers() // Old readiness is queued, but its main callback has not run.
    f.request { "new" }
    f.runWorkers(); f.runMain()
    assertEquals(listOf("new"), f.admitted)
    assertEquals(listOf("main"), f.admissionRoles)
  }

  @Test fun `cancel invalidates already prepared main delivery`() {
    val f = Fixture()
    f.request { "old" }
    f.runWorkers()
    f.preparation.cancel()
    f.runMain()
    assertTrue("Cancelled readiness reached UI admission", f.admitted.isEmpty())
    assertTrue(f.failures.isEmpty())
  }

  @Test fun `a running preparation that ignores cancellation still cannot attach`() {
    val f = Fixture()
    f.request {
      // Equivalent to an uninterruptible hydration returning after stop.
      f.onMain { f.preparation.close() }
      "late"
    }
    f.runWorkers(); f.runMain()
    assertTrue("Late hydration bypassed terminal ownership", f.admitted.isEmpty())
    assertTrue(f.failures.isEmpty())
  }

  @Test fun `replacement during old preparation admits only the replacement`() {
    val f = Fixture()
    f.request {
      f.onMain { f.request { "replacement" } }
      "superseded"
    }
    f.runWorkers(); f.runMain()
    assertEquals(listOf("replacement"), f.admitted)
  }

  @Test fun `rapid reloads prepare only the latest pending deck`() {
    val f = Fixture()
    val prepared = ArrayList<Int>()
    for (index in 1..50) f.request { prepared.add(index); "deck-$index" }
    f.runWorkers(); f.runMain()
    assertEquals(listOf(50), prepared)
    assertEquals(listOf("deck-50"), f.admitted)
  }

  @Test fun `cancellation during lookup prevents reading the rest of the deck`() {
    val f = Fixture()
    val focusReads = ArrayList<String>()
    val weightReads = ArrayList<String>()
    f.request { current ->
      LegacySlideshowPreparation.photosJson(listOf("fixture:a", "fixture:b", "fixture:c"),
          emptyMap(), { key ->
            focusReads.add(key)
            f.onMain { f.preparation.cancel() }
            FaceFocus.Focus(.2f, .3f)
          }, { key -> weightReads.add(key); .8f }, current)
    }
    f.runWorkers(); f.runMain()
    assertEquals(listOf("fixture:a"), focusReads)
    assertTrue("Cancelled work continued reading metadata", weightReads.isEmpty())
    assertTrue(f.admitted.isEmpty())
    assertTrue("Cancellation was treated as a source failure", f.failures.isEmpty())
  }

  @Test fun `current preparation failure reaches fallback once on main`() {
    val f = Fixture()
    val failure = IOException("synthetic read failure")
    val escaped = runCatching { f.request { throw failure } }.exceptionOrNull()
    assertNull("Worker failure escaped onto the requesting stack", escaped)
    f.runWorkers()
    assertTrue("Worker invoked UI fallback directly", f.failures.isEmpty())
    f.runMain()
    assertEquals(listOf(failure), f.failures)
    assertEquals(listOf("main"), f.failureRoles)
    assertTrue(f.admitted.isEmpty())
  }

  @Test fun `superseded preparation failure cannot trigger fallback`() {
    val f = Fixture()
    val escaped = runCatching {
      f.request {
        f.onMain { f.request { "replacement" } }
        throw IOException("obsolete synthetic failure")
      }
    }.exceptionOrNull()
    assertNull("Obsolete failure escaped its worker", escaped)
    f.runWorkers(); f.runMain()
    assertEquals(listOf("replacement"), f.admitted)
    assertTrue("An old failure replaced a newer deck with fallback", f.failures.isEmpty())
  }

  @Test fun `a rejected main dispatch cannot admit or start warming`() {
    val f = Fixture()
    f.acceptMain = false
    var warmStarts = 0
    f.preparation.request({ "ready" }, { warmStarts++ }, { fail("Closed main queue ran fallback") })
    f.runWorkers(); f.runMain()
    assertEquals("Undelivered metadata started cache warming", 0, warmStarts)
  }

  @Test fun `worker rejection does not hydrate metadata and reaches fallback on main`() {
    val f = Fixture()
    val rejection = RejectedExecutionException("synthetic executor rejection")
    f.workerFailure = rejection
    var reads = 0
    val escaped = runCatching { f.request { reads++; "never prepared" } }.exceptionOrNull()
    assertNull("Executor rejection escaped onto the requesting stack", escaped)
    assertEquals(0, reads)
    assertTrue(f.admitted.isEmpty())
    assertTrue("Submission invoked fallback before Main delivery", f.failures.isEmpty())
    f.runWorkers(); f.runMain()
    assertEquals(listOf(rejection), f.failures)
    assertEquals(listOf("main"), f.failureRoles)
  }

  @Test fun `current attachment failure invokes owned cleanup and fallback once on main`() {
    val f = Fixture()
    val failure = IOException("synthetic attachment failure")
    var owned = false
    var fallbacks = 0
    val cleanupRoles = ArrayList<String>()
    f.preparation.request(
        prepare = { "ready" },
        onReady = { owned = true; throw failure },
        onFailure = { error ->
          assertSame(failure, error)
          assertTrue("Failed admission lost its allocated resource", owned)
          cleanupRoles.add(f.role)
          owned = false
          fallbacks++
        })
    f.runWorkers()
    assertFalse("Worker allocated a UI resource", owned)
    f.runMain()
    assertFalse("Failed admission orphaned its allocation", owned)
    assertEquals(listOf("main"), cleanupRoles)
    assertEquals(1, fallbacks)
  }

  @Test fun `stop rejects an already queued preparation failure without fallback`() {
    val f = Fixture()
    f.request { throw IOException("synthetic queued failure") }
    f.runWorkers()
    f.preparation.close()
    f.runMain()
    assertTrue(f.admitted.isEmpty())
    assertTrue("Stopped failure restarted playback", f.failures.isEmpty())
  }

  @Test fun `covered main delivery retains only the latest prepared deck`() {
    val f = Fixture()
    for (index in 1..50) {
      f.request { "deck-$index" }
      f.runWorkers()
    }
    assertEquals("Ready results accumulated while Main was covered", 1, f.queuedMainCount)
    assertTrue(f.admitted.isEmpty())
    f.runMain()
    assertEquals(listOf("deck-50"), f.admitted)
  }

  @Test fun `terminal close rejects all future preparations`() {
    val f = Fixture()
    f.preparation.close()
    var reads = 0
    f.request { reads++; "closed" }
    f.runWorkers(); f.runMain()
    assertEquals(0, reads)
    assertTrue(f.admitted.isEmpty())
    assertTrue(f.failures.isEmpty())
  }

  @Test fun `serialization preserves duplicates optional geometry and legacy numeric precision`() {
    val previous = Locale.getDefault()
    Locale.setDefault(Locale.GERMANY)
    try {
      val json = LegacySlideshowPreparation.photosJson(
          listOf("fixture:b", "fixture:a", "fixture:b", "fixture:empty", "fixture:negative"),
          mapOf("fixture:b" to (1234 to 987)),
          { key -> if (key == "fixture:b") FaceFocus.Focus(.1234567f, .8765432f) else null },
          { key -> when (key) { "fixture:b" -> .7654f; "fixture:negative" -> -1f; else -> 0f } })
      val photos = JSONArray(json)
      assertEquals(5, photos.length())
      assertEquals(listOf("fixture:b", "fixture:a", "fixture:b", "fixture:empty", "fixture:negative"),
          (0 until photos.length()).map { photos.getJSONObject(it).getString("url") })
      for (index in listOf(0, 2)) {
        val photo = photos.getJSONObject(index)
        assertEquals(setOf("url", "w", "h", "fx", "fy", "pw"), photo.keys().asSequence().toSet())
        assertEquals(1234, photo.getInt("w")); assertEquals(987, photo.getInt("h"))
        assertEquals(.123, photo.getDouble("fx"), 0.0)
        assertEquals(.877, photo.getDouble("fy"), 0.0)
        assertEquals(.77, photo.getDouble("pw"), 0.0)
      }
      for (index in listOf(1, 3, 4))
        assertEquals(setOf("url"), photos.getJSONObject(index).keys().asSequence().toSet())
    } finally { Locale.setDefault(previous) }
  }

  @Test fun `missing or corrupt synthetic cache preserves the no-framing fallback without writing`() {
    val context = Mockito.mock(Context::class.java)
    for (directory in listOf(temporary.newFolder("missing"), temporary.newFolder("corrupt"))) {
      val file = directory.resolve("face_focus.json")
      if (directory.name == "corrupt") file.writeText("invalid synthetic metadata")
      val before = if (file.exists()) file.readText() else null
      Mockito.`when`(context.filesDir).thenReturn(directory)
      val store = FaceFocusStore(context)
      try {
        val json = LegacySlideshowPreparation.photosJson(listOf("fixture:one"),
            mapOf("fixture:one" to (640 to 480)), store::focusFor, store::weightFor)
        val photo = JSONArray(json).getJSONObject(0)
        assertEquals(setOf("url", "w", "h"), photo.keys().asSequence().toSet())
        assertEquals("fixture:one", photo.getString("url"))
        assertEquals(640, photo.getInt("w")); assertEquals(480, photo.getInt("h"))
        assertEquals(before, if (file.exists()) file.readText() else null)
      } finally { store.close() }
    }
  }

  @Test fun `empty deck neither hydrates cache nor invents a photo`() {
    val json = LegacySlideshowPreparation.photosJson(emptyList(), emptyMap(),
        { fail("Empty deck read focus metadata"); null },
        { fail("Empty deck read people metadata"); 0f })
    assertEquals(0, JSONArray(json).length())
  }

  @Test fun `already obsolete serialization does not touch the cache`() {
    var reads = 0
    val error = runCatching {
      LegacySlideshowPreparation.photosJson(listOf("fixture:old"), emptyMap(),
          { reads++; null }, { reads++; 0f }, { false })
    }.exceptionOrNull()
    assertTrue("Obsolete preparation did not cancel", error is CancellationException)
    assertEquals("Obsolete preparation hydrated metadata", 0, reads)
  }

  private class Fixture {
    private val workerTasks = ArrayDeque<() -> Unit>()
    private val mainTasks = ArrayDeque<() -> Unit>()
    var role = "main"
      private set
    var acceptMain = true
    var workerFailure: Exception? = null
    val queuedMainCount: Int get() = mainTasks.size
    val admitted = ArrayList<String>()
    val admissionRoles = ArrayList<String>()
    val failures = ArrayList<Exception>()
    val failureRoles = ArrayList<String>()
    val preparation = LegacySlideshowPreparation(
        submitWorker = { action ->
          workerFailure?.let { throw it }
          workerTasks.addLast(action)
        },
        postMain = { action ->
          if (acceptMain) { mainTasks.addLast(action); true } else false
        })

    fun request(prepare: (() -> Boolean) -> String) = preparation.request(prepare,
        { admitted.add(it); admissionRoles.add(role) },
        { failures.add(it); failureRoles.add(role) })

    fun runWorkers() = inRole("worker") {
      while (workerTasks.isNotEmpty()) workerTasks.removeFirst().invoke()
    }

    fun runMain() = onMain {
      while (mainTasks.isNotEmpty()) mainTasks.removeFirst().invoke()
    }

    fun onMain(action: () -> Unit) = inRole("main", action)

    private fun inRole(value: String, action: () -> Unit) {
      val old = role
      role = value
      try { action() } finally { role = old }
    }
  }
}

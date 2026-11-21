/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Log
import com.immortal.launcher.settings.SettingsDomain
import com.immortal.launcher.settings.SettingsSyncCatalog
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.MockedConstruction
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * One Portal's household settings sync with the Android edges replaced: in-memory preferences and
 * a scratch dir behind a mocked Context, the real household settings catalog, and this Portal's
 * device id. Everything it does (logs, published status, ledger, settings written, the delayed
 * main-thread work it queues, replies) is appended to [out], one line each.
 */
internal class SyncGoldenHarness(private val dir: File) : AutoCloseable {
  val out: MutableList<String> = Collections.synchronizedList(ArrayList())
  private val prefs = HashMap<String, ScreensaverTestPrefs>()
  /** Delayed main-thread work the sync queued and hasn't had cancelled, in order. */
  val posted = ArrayList<Pair<Runnable, Long>>()

  val context: Context = mock(Context::class.java).also { c ->
    `when`(c.applicationContext).thenReturn(c)
    `when`(c.getSharedPreferences(anyString(), anyInt())).thenAnswer { prefs(it.getArgument(0)) }
    `when`(c.filesDir).thenReturn(dir)
    `when`(c.noBackupFilesDir).thenReturn(dir)
    `when`(c.cacheDir).thenReturn(File(dir, ".cache"))
  }

  private val catalog get() = SettingsSyncCatalog.household
  private val wellFormed: (String, Any?) -> Any? = { k, v -> catalog.wellFormed(k, v) }
  private val testThread = ThreadMocks(out)
  private val workerMocks = ArrayList<Pair<ExecutorService, ThreadMocks>>()
  private val looper: MockedStatic<Looper> = Mockito.mockStatic(Looper::class.java)
  private val handlers: MockedConstruction<Handler> = Mockito.mockConstruction(Handler::class.java) { h, _ ->
    `when`(h.postDelayed(Mockito.any(), anyLong())).thenAnswer { SyncGoldenMain.post(it.getArgument(0), it.getArgument(1)); true }
    Mockito.doAnswer { SyncGoldenMain.remove(it.getArgument(0)); null }.`when`(h).removeCallbacks(Mockito.any())
  }
  private val appliedHook = SettingsDomain.valuesApplied
  private val realClock = SettingsSync.clock
  private val ticks = AtomicLong(CLOCK_START_MS)

  /**
   * The sync's clock: a fixed start that moves on 1 ms per read. The real clock let two peer
   * sightings share a millisecond, and peers are listed most recently seen first, so a tie flipped
   * their order at random. Every read here is strictly later than the one before.
   */
  fun now(): Long = ticks.incrementAndGet()

  val worker: ExecutorService = staticField("worker")
  val net: ExecutorService = staticField("net")

  init {
    SyncGoldenMain.harness = this
    SettingsSync.clock = this::now
    File(dir, "remote-companion.json").writeText(JSONObject().put("deviceId", SELF).toString())
    prefs("fleet_agent").edit().putString("name", "Family").putInt("port", PORT).apply()
    for (ex in listOf(worker, net)) workerMocks += ex to ex.submit<ThreadMocks> { ThreadMocks(out) }.get()
    resetSync()
  }

  fun prefs(name: String): ScreensaverTestPrefs = prefs.getOrPut(name) { ScreensaverTestPrefs() }

  override fun close() {
    drain()
    resetSync()
    SettingsDomain.valuesApplied = appliedHook
    SettingsSync.clock = realClock
    workerMocks.forEach { (ex, m) -> ex.submit { m.close() }.get() }
    SyncGoldenMain.harness = null
    handlers.close(); looper.close(); testThread.close()
  }

  /** The sync is a process-wide object: every scenario starts it from scratch. */
  private fun resetSync() {
    setStatic("installed", false)
    setStatic("core", null)
    staticField<java.util.concurrent.atomic.AtomicBoolean>("pausedFlag").set(false)
    staticField<kotlinx.coroutines.flow.MutableStateFlow<SettingsSyncStatus>>("_status").value = SettingsSyncStatus()
  }

  /** Waits until the worker and network threads have nothing left (each may queue on the other). */
  fun drain() {
    repeat(DRAIN_ROUNDS) { worker.submit {}.get(); net.submit {}.get() }
  }

  fun busHandler(type: String): HouseholdBus.Handler =
      staticField<Map<String, HouseholdBus.Handler>>("handlers", HouseholdBus::class.java)[type] ?: error("no $type handler")

  // --- what the sync shows ------------------------------------------------------------------

  fun status(): String = "status " + norm(FleetGoldenHarness.canonical(SettingsSync.statusJson()))

  /** Household values that differ from their defaults. */
  fun values(): String {
    val defaults = catalog.defaults()
    return "values " + catalog.read(context).toSortedMap().filter { (k, v) -> defaults[k] != v }.entries.joinToString(",") { "${it.key}=${it.value}" }
  }

  fun ledger(): String {
    val l = SettingsSyncStore.load(context, wellFormed) ?: return "ledger -"
    fun recs(m: Map<String, SyncRecord>) = m.values.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}@${it.version.counter}:${short(it.version.device)}" }
    val pending = l.pending?.let { "${short(it.fromId)}/${it.fromName}[${recs(it.records)}]" } ?: "-"
    val defaults = catalog.defaults()
    val known = l.lastKnown.toSortedMap().filter { (k, v) -> defaults[k] != v }.entries.joinToString(",") { "${it.key}=${it.value}" }
    val peers = l.peers.values.sortedBy { it.id }.joinToString(",") { "${short(it.id)}/${it.name}" + if (it.syncedAt > 0) "+synced" else "" }
    return "ledger ${l.phase} clock=${l.clock} records=[${recs(l.records)}] pending=$pending known=[$known] peers=[$peers] " +
        "adoptedFrom=${l.adoptedFrom ?: "-"} cardDismissed=${SettingsSyncStore.adoptCardDismissed(context)}"
  }

  fun short(id: String) = NAMES[id] ?: id

  fun norm(s: String): String = s.replace(NOW_MS, "<now>")

  private fun setStatic(name: String, value: Any?) {
    SettingsSync::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, value)
  }

  @Suppress("UNCHECKED_CAST")
  private fun <T> staticField(name: String, owner: Class<*> = SettingsSync::class.java): T =
      owner.getDeclaredField(name).apply { isAccessible = true }.get(null) as T

  companion object {
    const val SELF = "55555555-5555-4555-8555-555555555555"
    const val PEER_HI = "99999999-9999-4999-8999-999999999999"
    const val PEER_LO = "11111111-1111-4111-8111-111111111111"
    const val PORT = 8765
    private const val DRAIN_ROUNDS = 4
    /** 2027-01-15: inside [NOW_MS], so stamps still read as `<now>` in the golden. */
    private const val CLOCK_START_MS = 1_800_000_000_000L
    val NAMES = mapOf(SELF to "self", PEER_HI to "hi", PEER_LO to "lo")
    /** Epoch ms (2023 onwards): stamps the sync takes from its clock. */
    private val NOW_MS = Regex("\\b1[7-9]\\d{11}\\b")
  }
}

/** Where the mocked main-thread Handler (created once per process, lazily) sends its work. */
internal object SyncGoldenMain {
  @Volatile var harness: SyncGoldenHarness? = null

  fun post(r: Runnable, delay: Long) {
    val h = harness ?: return
    synchronized(h.posted) { h.posted += r to delay }
    h.out += "main.postDelayed ${delay}ms"
  }

  fun remove(r: Runnable) {
    val h = harness ?: return
    synchronized(h.posted) { h.posted.removeAll { it.first === r } }
  }
}

/**
 * Mockito's static and construction mocks only apply on the thread that opens them, so each sync
 * thread opens its own: Log calls are recorded and the companion store's AtomicFile reads a plain
 * file.
 */
internal class ThreadMocks(out: MutableList<String>) : AutoCloseable {
  private val log: MockedStatic<Log> = Mockito.mockStatic(Log::class.java) { inv ->
    if (inv.method.returnType == Int::class.javaPrimitiveType) {
      val tag = inv.arguments.getOrNull(0) as? String ?: ""
      if (tag == "HearthpaneSettingsSync" || tag == "HouseholdBus" || tag.startsWith("Hearthpane"))
        out += "log ${inv.method.name} $tag: ${inv.arguments.getOrNull(1)}" +
            ((inv.arguments.getOrNull(2) as? Throwable)?.let { " (${it.javaClass.simpleName})" } ?: "")
      0
    } else Mockito.RETURNS_DEFAULTS.answer(inv)
  }
  private val atomic: MockedConstruction<AtomicFile> = Mockito.mockConstruction(AtomicFile::class.java) { f, ctx ->
    val file = ctx.arguments()[0] as File
    `when`(f.openRead()).thenAnswer { if (file.exists()) FileInputStream(file) else throw FileNotFoundException(file.name) }
    `when`(f.baseFile).thenReturn(file)
  }

  override fun close() { atomic.close(); log.close() }
}

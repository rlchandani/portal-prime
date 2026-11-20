/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.animation.AnimatorSet
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewPropertyAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import org.mockito.MockedConstruction
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import sun.misc.Unsafe

/**
 * The real [PhotoFrameController] with every Android edge replaced, so its behaviour can be
 * recorded on the JVM: a mocked Context over in-memory preferences and scratch dirs, a virtual
 * main-thread clock ([SystemClock] and every [Handler] post), worker executors that run only when
 * a scenario drains them, and construction mocks for every view, sensor, player and loader it
 * builds. Views keep their set/get properties (visibility, alpha, ...), so the controller's own
 * checks read back what it wrote.
 *
 * Everything the controller does to those collaborators is appended to [out] in call order
 * (queries such as getters are left out), with main-thread posts, worker submissions and logs.
 */
internal class PhotoFrameGoldenRig(
    screensaver: Map<String, Any?>,
    private val testSource: TestSource? = null,
    val options: Options = Options(),
) : AutoCloseable {
  data class Options(
      val showWelcome: Boolean = false,
      val interactivePreview: Boolean = false,
      val photosOnly: Boolean = false,
      val nightClockOnly: Boolean = false,
      val overnightNightPhoto: Boolean = false,
      val width: Int = 1920,
      val height: Int = 1080,
      /** Someone in front of the frame (the engine's people-bias and the filmstrip read it). */
      val present: Boolean = false,
  )

  /** The operator test source: minutes from now it stays on, a forced night, and a mode pin. */
  data class TestSource(val night: Boolean = false, val pinMode: String? = null, val pinIntervalSec: Int = 0)

  val out = ArrayList<String>()
  /** Rig-side events (posts' owners, worker submissions, logs) on Mockito's call sequence, so they interleave exactly. */
  interface Events { fun note(line: String) }
  private val events: Events = Mockito.mock(Events::class.java)
  private fun event(line: String) = events.note(line)
  val dir: File = java.nio.file.Files.createTempDirectory("photo-frame-golden").toFile()
  private val prefs = HashMap<String, ScreensaverTestPrefs>()
  private val names = IdentityHashMap<Any, String>()
  private val counts = HashMap<String, Int>()
  private val recordedTo = IdentityHashMap<Any, Int>()
  private val mocks = ArrayList<Any>()
  private val closers = ArrayList<AutoCloseable>()
  val main = MainQueue()
  val workers = LinkedHashMap<String, Worker>()
  var receiver: BroadcastReceiver? = null
    private set
  /** The dark-room observers built so far, newest last, with the callback each was given. */
  val darkRooms = ArrayList<Pair<Any, (Boolean) -> Unit>>()
  /** Every [SlideshowLoader.load] call, oldest first. */
  val loads = ArrayList<Load>()

  data class Load(val asset: SlideshowAsset, val onReady: (List<SlideshowPhoto>, String?) -> Unit, val onFailure: () -> Unit)
  /** Source discoveries (Immich, WebDAV, shared album) waiting for their answer. */
  val discoveries = ArrayList<(Any?) -> Unit>()
  /** Engine preparations: the metadata step the WebView engine would run on its worker. */
  val preparations = ArrayList<(() -> Boolean) -> String>()
  private var viewerArgs: List<Any?> = emptyList()

  inner class MainQueue {
    var now = UPTIME0
    private var seq = 0L
    private val tasks = ArrayList<Triple<Long, Long, Runnable>>()

    fun post(r: Runnable, delay: Long): Boolean {
      tasks += Triple(now + delay.coerceAtLeast(0L), seq++, r)
      return true
    }

    fun remove(r: Runnable) { tasks.removeAll { it.third === r } }

    /** Delays from now of everything queued, soonest first. */
    fun pending(): List<Long> = tasks.sortedWith(compareBy({ it.first }, { it.second })).map { it.first - now }

    /** Runs what is due within [ms], in due order, moving the clock to each task's time. */
    fun advance(ms: Long) {
      val target = now + ms
      while (true) {
        val next = tasks.filter { it.first <= target }.minWithOrNull(compareBy({ it.first }, { it.second })) ?: break
        tasks.remove(next)
        if (next.first > now) now = next.first
        next.third.run()
      }
      now = target
    }
  }

  /** A worker thread that only runs when drained. */
  inner class Worker(val name: String) : AbstractExecutorService() {
    val queue = ArrayDeque<Runnable>()
    private var down = false
    override fun execute(command: Runnable) {
      if (down) throw java.util.concurrent.RejectedExecutionException(name)
      queue.addLast(command); event("exec $name")
    }
    fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    override fun shutdown() { down = true }
    override fun shutdownNow(): MutableList<Runnable> { down = true; return queue.toMutableList().also { queue.clear() } }
    override fun isShutdown() = down
    override fun isTerminated() = down && queue.isEmpty()
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
  }

  private val workerNames = listOf("io", "meta", "transcode", "cancel", "sound", "pool5", "pool6")

  private fun worker(): ExecutorService {
    val name = workerNames.getOrElse(workers.size) { "pool${workers.size}" }
    return Worker(name).also { workers[name] = it }
  }

  // --- the property-keeping default answer every constructed collaborator uses ----------------
  private val properties = IdentityHashMap<Any, HashMap<String, Any?>>()
  private val children = IdentityHashMap<Any, HashMap<String, Any>>()

  private val viewAnswer = Answer { inv: InvocationOnMock ->
    val m = inv.method
    val name = m.name
    val store = properties.getOrPut(inv.mock) { HashMap() }
    when {
      name.startsWith("set") && inv.arguments.size == 1 -> { store[name.removePrefix("set")] = inv.arguments[0]; null }
      (name.startsWith("get") || name.startsWith("is")) && inv.arguments.isEmpty() -> {
        val key = name.removePrefix("get").removePrefix("is")
        if (store.containsKey(key)) store[key] else child(inv, key)
      }
      else -> child(inv, name)
    }
  }

  /** A stable answer for a query: empty collections, defaults, or one named child mock per query. */
  private fun child(inv: InvocationOnMock, key: String): Any? {
    val type = inv.method.returnType
    if (type == Void.TYPE) return null
    val default = Mockito.RETURNS_DEFAULTS.answer(inv)
    if (default != null || type.isPrimitive || type == String::class.java || Modifier.isFinal(type.modifiers) ||
        Collection::class.java.isAssignableFrom(type) || Map::class.java.isAssignableFrom(type) ||
        type == Any::class.java || type.name.startsWith("kotlin.")) return default
    // Views animate through their animator; a chained animator must keep returning itself.
    if (type == ViewPropertyAnimator::class.java) return children.getOrPut(inv.mock) { HashMap() }.getOrPut(key) {
      Mockito.mock(ViewPropertyAnimator::class.java, Mockito.withSettings().defaultAnswer { a ->
        if (a.method.returnType == ViewPropertyAnimator::class.java) a.mock else viewAnswer.answer(a)
      }).also { register(it, "${names[inv.mock]}.animate") }
    }
    return children.getOrPut(inv.mock) { HashMap() }.getOrPut(key) {
      Mockito.mock(type, Mockito.withSettings().defaultAnswer(viewAnswer)).also { register(it, "${names[inv.mock]}.${key.replaceFirstChar(Char::lowercase)}") }
    }
  }

  private fun register(mock: Any, name: String) { names[mock] = name; mocks += mock }

  private val constructorArgs = IdentityHashMap<Any, List<Any?>>()

  /** Mocks every [type] built while the rig is open; [special] answers some calls, else [viewAnswer]. */
  private fun <T> construct(
      type: Class<T>, label: String = type.simpleName,
      special: (InvocationOnMock, List<Any?>) -> Any? = { _, _ -> UNHANDLED },
      init: (T, List<Any?>) -> Unit = { _, _ -> },
  ): MockedConstruction<T> =
      Mockito.mockConstruction(type, Mockito.withSettings().defaultAnswer { inv ->
        val r = special(inv, constructorArgs[inv.mock].orEmpty())
        if (r === UNHANDLED) viewAnswer.answer(inv) else r
      }) { mock, context ->
        val n = counts.merge(label, 1, Int::plus)!!
        register(mock as Any, "$label$n")
        constructorArgs[mock as Any] = context.arguments()
        init(mock, context.arguments())
      }.also { closers += it }

  // --- statics ------------------------------------------------------------------------------
  private val statics = ArrayList<MockedStatic<*>>()

  private val context: Context = Mockito.mock(Context::class.java)
  private val metrics: DisplayMetrics = Mockito.mock(DisplayMetrics::class.java)

  lateinit var controller: PhotoFrameController
    private set
  private val savedPresence = PresenceHub.current

  private val decoderSpy = spyObject(SlideshowDecoder::class.java) { inv ->
    val a = inv.arguments
    when (inv.method.name) {
      "file", "nativeFilmstripFile" -> { event("decode ${inv.method.name} ${render(a[0])} edge=${a.getOrNull(1)}"); decoded(a[0] as String) }
      "bytes", "nativeFilmstripBytes" -> { event("decode ${inv.method.name} ${(a[0] as ByteArray).size}B edge=${a.getOrNull(1)}"); decoded("${(a[0] as ByteArray).size}B") }
      "stream" -> { event("decode stream edge=${a.getOrNull(1)}"); decoded("stream") }
      else -> inv.callRealMethod()
    }
  }
  private val testSourceSpy = spyObject(ScreensaverTestSource::class.java) { inv ->
    if (inv.method.name == "photo") {
      event("testPhoto index=${inv.arguments[0]} edge=${inv.arguments[1]}")
      bitmap("test${inv.arguments[0]}")
    } else inv.callRealMethod()
  }

  private fun decoded(what: String): Bitmap = bitmap("decoded ${clean(what)}")

  private fun uri(text: String): android.net.Uri = Mockito.mock(android.net.Uri::class.java).also {
    Mockito.`when`(it.toString()).thenReturn(text)
    Mockito.`when`(it.path).thenReturn(text.removePrefix("file://"))
    register(it, "uri(${clean(text)})")
  }

  init {
    register(events, "events")
    register(context, "ctx")
    metrics.widthPixels = options.width
    metrics.heightPixels = options.height
    metrics.density = 1.5f
    val res = Mockito.mock(Resources::class.java)
    Mockito.`when`(res.displayMetrics).thenReturn(metrics)
    val assets = Mockito.mock(AssetManager::class.java)
    Mockito.`when`(assets.list(Mockito.anyString())).thenReturn(arrayOf("a.jpg", "b.jpg", "notes.txt"))
    Mockito.`when`(assets.open(Mockito.anyString())).thenAnswer { java.io.ByteArrayInputStream(ByteArray(64) { it.toByte() }) }
    Mockito.`when`(context.resources).thenReturn(res)
    Mockito.`when`(context.assets).thenReturn(assets)
    Mockito.`when`(context.applicationContext).thenReturn(context)
    Mockito.`when`(context.cacheDir).thenReturn(File(dir, "cache").apply { mkdirs() })
    Mockito.`when`(context.filesDir).thenReturn(File(dir, "files").apply { mkdirs() })
    Mockito.`when`(context.packageName).thenReturn("com.immortal.launcher.debug")
    Mockito.`when`(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenAnswer { prefs(it.getArgument(0)) }
    // Base household settings: media cache on (no housekeeping thread), no welcome overlay.
    prefs("immortal_screensaver").edit().putBoolean("cache_enabled", true).putBoolean("welcome_enabled", false).apply()
    screensaver.forEach { (k, v) -> putPref(prefs("immortal_screensaver"), k, v) }
    testSource?.let(::withTestSource)

    statics += Mockito.mockStatic(Log::class.java) { inv ->
      if (inv.method.returnType == Int::class.javaPrimitiveType) {
        val tag = inv.arguments.getOrNull(0) as? String ?: ""
        val msg = inv.arguments.getOrNull(1)?.toString().orEmpty()
        if (tag.startsWith("Immortal")) event("log ${inv.method.name} $tag: ${clean(msg)}")
        0
      } else Mockito.RETURNS_DEFAULTS.answer(inv)
    }
    statics += Mockito.mockStatic(SystemClock::class.java) { inv ->
      if (inv.method.returnType == Long::class.javaPrimitiveType) main.now else Mockito.RETURNS_DEFAULTS.answer(inv)
    }
    statics += Mockito.mockStatic(Looper::class.java)
    // Real channel packing, so colour constants in class initialisers (stage mattes) are right.
    statics += Mockito.mockStatic(android.graphics.Color::class.java) { inv ->
      val a = inv.arguments.map { (it as? Number)?.toInt() ?: 0 }
      when (inv.method.name) {
        "rgb" -> if (a.size == 3) (0xFF shl 24) or (a[0] shl 16) or (a[1] shl 8) or a[2] else 0
        "argb" -> if (a.size == 4) (a[0] shl 24) or (a[1] shl 16) or (a[2] shl 8) or a[3] else 0
        else -> Mockito.RETURNS_DEFAULTS.answer(inv)
      }
    }
    statics += Mockito.mockStatic(Typeface::class.java)
    statics += Mockito.mockStatic(android.text.format.DateFormat::class.java) { inv ->
      if (inv.method.name == "getBestDateTimePattern") inv.arguments[1] else Mockito.RETURNS_DEFAULTS.answer(inv)
    }
    statics += Mockito.mockStatic(android.animation.ObjectAnimator::class.java)
    statics += Mockito.mockStatic(BitmapFactory::class.java)
    statics += Mockito.mockStatic(android.net.Uri::class.java) { inv ->
      when (inv.method.name) {
        "parse" -> uri("${inv.arguments[0]}")
        "fromFile" -> uri("file://${(inv.arguments[0] as File).path}")
        else -> Mockito.RETURNS_DEFAULTS.answer(inv)
      }
    }
    statics += Mockito.mockStatic(Executors::class.java) { inv ->
      if (ExecutorService::class.java.isAssignableFrom(inv.method.returnType)) worker() else inv.callRealMethod()
    }
    statics += Mockito.mockStatic(ContextCompat::class.java) { inv ->
      if (inv.method.name == "registerReceiver") {
        receiver = inv.arguments[1] as BroadcastReceiver; event("registerReceiver"); null
      } else inv.callRealMethod()
    }
    construct(Handler::class.java, special = { inv, _ ->
      val a = inv.arguments
      when {
        inv.method.name == "postDelayed" && a.size == 2 -> main.post(a[0] as Runnable, a[1] as Long)
        inv.method.name == "post" && a.size == 1 -> main.post(a[0] as Runnable, 0L)
        inv.method.name == "removeCallbacks" && a.size == 1 -> { main.remove(a[0] as Runnable); null }
        else -> UNHANDLED
      }
    })
    listOf(FrameLayout::class.java, LinearLayout::class.java, ImageView::class.java, TextView::class.java,
        GradientDrawable::class.java, FrameLayout.LayoutParams::class.java, LinearLayout.LayoutParams::class.java,
        IntentFilter::class.java, Rect::class.java, AnimatorSet::class.java, AccelerateDecelerateInterpolator::class.java,
        LinearInterpolator::class.java, SlideshowVideoView::class.java, FaceRenderer::class.java,
        SoundscapePlayer::class.java, GalleryOverlayLayoutObserver::class.java, FaceFocusStore::class.java, EngineScaledCache::class.java,
        MediaCache::class.java, VideoTranscoder::class.java, android.graphics.Canvas::class.java,
        android.graphics.Paint::class.java).forEach { construct(it) }
    // Built with arguments that carry decisions (tone, lux, interval, urls): note them.
    listOf(QuietNightClockView::class.java, NightPhotoOverlay::class.java, SplitGlancePanel::class.java,
        FilmstripEngine::class.java, GestureCamera::class.java, TextToSpeech::class.java).forEach { type ->
      construct(type) { _, args -> event("new ${type.simpleName}(${args.joinToString(", ") { render(it) }})") }
    }
    construct(CalendarMinuteRefresh::class.java)
    construct(SlideshowSourceDiscovery::class.java, special = { inv, _ ->
      if (inv.method.name != "load") UNHANDLED else {
        @Suppress("UNCHECKED_CAST") discoveries += inv.arguments[1] as (Any?) -> Unit
        null
      }
    })
    construct(LegacySlideshowPreparation::class.java, special = { inv, _ ->
      if (inv.method.name != "request") UNHANDLED else {
        @Suppress("UNCHECKED_CAST") preparations += inv.arguments[0] as (() -> Boolean) -> String
        null
      }
    })
    construct(SlideshowViewerControls::class.java) { _, args -> viewerArgs = args }
    // The real stage reports a presented scene from show() itself and then holds it as current.
    construct(SlideshowStage::class.java, special = { inv, _ ->
      if (inv.method.name != "show") UNHANDLED else {
        @Suppress("UNCHECKED_CAST") val photos = inv.arguments[0] as List<SlideshowPhoto>
        val store = properties.getOrPut(inv.mock) { HashMap() }
        if (photos.isNotEmpty()) { store["CurrentPhoto"] = photos.first(); store["PhotoCount"] = photos.size }
        @Suppress("UNCHECKED_CAST") (store["OnPresented"] as? () -> Unit)?.invoke()
        null
      }
    })
    construct(BitmapFactory.Options::class.java)
    construct(ExifInterface::class.java, special = { inv, args ->
      if (inv.method.name != "getAttributeInt") UNHANDLED else {
        val path = args.firstOrNull() as? String ?: ""
        when {
          path.contains("rot90") -> ExifInterface.ORIENTATION_ROTATE_90
          path.contains("rot270") -> ExifInterface.ORIENTATION_ROTATE_270
          else -> ExifInterface.ORIENTATION_NORMAL
        }
      }
    })
    construct(DarkRoomObserver::class.java) { obs, args ->
      @Suppress("UNCHECKED_CAST")
      darkRooms += obs as Any to (args.first { it is Function1<*, *> } as (Boolean) -> Unit)
      event("darkRoom lux=${args[1]} initialNight=${args[2]}")
    }
    construct(SlideshowLoader::class.java, special = { inv, _ ->
      if (inv.method.name != "load" || inv.arguments.size < 7) UNHANDLED else {
        @Suppress("UNCHECKED_CAST")
        loads += Load(inv.arguments[0] as SlideshowAsset, inv.arguments[5] as (List<SlideshowPhoto>, String?) -> Unit,
            inv.arguments[6] as () -> Unit)
        null
      }
    })
    TimeZoneGuard.utc()
    presence(PresenceState(if (options.present) Presence.PRESENT else Presence.UNKNOWN, ScreenState.OFF, confident = false, sinceMs = 0L))
  }

  /** Process-wide presence, which other suites also set: pinned per scenario, restored on close. */
  private fun presence(state: PresenceState) {
    PresenceHub::class.java.getDeclaredField("current").apply { isAccessible = true }.set(null, state)
  }

  /** More household settings, before [create]. */
  fun reconfigure(values: Map<String, Any?>) { values.forEach { (k, v) -> putPref(prefs("immortal_screensaver"), k, v) } }

  /** Turns the operator test source on for the next hour, before [create]. */
  fun withTestSource(t: TestSource) {
    val until = System.currentTimeMillis() + 60 * 60_000L
    val p = prefs("screensaver_test_source").edit().putLong("until", until)
    if (t.night) p.putLong("night_until", until)
    if (t.pinMode != null) p.putString("pin_mode", t.pinMode).putInt("pin_interval_sec", t.pinIntervalSec)
    p.apply()
  }

  /** Header-only decodes report the size named in the file (e.g. `p_3000x2000.jpg`), or nothing. */
  private fun stubBounds() {
    @Suppress("UNCHECKED_CAST")
    val bf = statics.first { it.toString().contains("BitmapFactory") } as MockedStatic<BitmapFactory>
    bf.`when`<Bitmap?> { BitmapFactory.decodeFile(Mockito.anyString(), Mockito.any()) }.thenAnswer { inv ->
      val path = inv.getArgument<String>(0)
      val opts = inv.getArgument<BitmapFactory.Options>(1)
      Regex("""_(\d+)x(\d+)""").find(path)?.let { m ->
        opts.outWidth = m.groupValues[1].toInt(); opts.outHeight = m.groupValues[2].toInt()
      }
      null
    }
  }

  /** Builds the controller (its real constructor) with the [options] the host would pass. */
  fun create(): PhotoFrameController {
    stubBounds()
    controller = PhotoFrameController(context, showWelcome = options.showWelcome, interactivePreview = options.interactivePreview,
        photosOnly = options.photosOnly, nightClockOnly = options.nightClockOnly, overnightNightPhoto = options.overnightNightPhoto)
    controller.onNightClockChanged = { event("nightClockChanged $it") }
    controller.onExit = { event("exit") }
    flush("create")
    return controller
  }

  /** Builds the view tree now and forgets what that did, so a scenario records only its own effects. */
  fun buildQuietly() {
    controller.view
    mocks.forEach { recordedTo[it] = Mockito.mockingDetails(it).invocations.size }
  }

  fun start() { controller.start(); flush("start") }

  fun drain(worker: String) { workers[worker]?.drain(); flush("drain $worker") }

  fun advance(ms: Long) { main.advance(ms); flush("advance $ms") }

  fun hostActive(value: Boolean) { controller.setHostActive(value); flush("hostActive $value") }

  fun next() { controller.next(); flush("next") }

  fun prev() { controller.prev(); flush("prev") }

  fun touch(action: Int, x: Float, y: Float) {
    val ev = Mockito.mock(MotionEvent::class.java)
    Mockito.`when`(ev.actionMasked).thenReturn(action)
    Mockito.`when`(ev.x).thenReturn(x)
    Mockito.`when`(ev.y).thenReturn(y)
    controller.onTouch(ev)
    flush("touch $action $x,$y")
  }

  /** The light sensor reports a dark (true) or lit room to the newest observer. */
  fun room(dark: Boolean) { darkRooms.last().second(dark); flush("room dark=$dark") }

  /** Household night settings changed and were broadcast; the controller re-reads them. */
  fun nightSettings(values: Map<String, Any?>) {
    values.forEach { (k, v) -> putPref(prefs("immortal_screensaver"), k, v) }
    val intent = Mockito.mock(Intent::class.java)
    Mockito.`when`(intent.action).thenReturn(NightSettingsChanges.ACTION)
    receiver?.onReceive(context, intent)
    flush("nightSettings $values")
  }

  /** The newest native load decodes [count] photos (named after its asset key). */
  fun deliver(count: Int = 1, partner: String? = null) {
    val load = loads.last()
    val photos = (0 until count).map { SlideshowPhoto("${load.asset.key}#$it", bitmap("${load.asset.key}#$it"), sourceKey = load.asset.key) }
    load.onReady(photos, partner)
    flush("deliver ${clean(load.asset.key)} x$count")
  }

  fun failLoad() { loads.last().onFailure(); flush("fail ${clean(loads.last().asset.key)}") }

  fun bitmap(label: String): Bitmap = Mockito.mock(Bitmap::class.java, Mockito.withSettings().name(label).defaultAnswer(viewAnswer)).also {
    register(it, "bitmap($label)")
  }

  fun stop() { controller.stop(); flush("stop") }

  /** Adds `name: value` lines for the controller's playback state. */
  fun state(vararg lines: Pair<String, Any?>) { lines.forEach { (k, v) -> out += "state $k=${render(v)}" } }

  fun pending() { out += "pending ${main.pending()}" }

  fun note(text: String) { out += "-- $text" }

  /** Records, in call order, everything done to a collaborator since the last flush. */
  fun flush(step: String) {
    val fresh = ArrayList<org.mockito.invocation.Invocation>()
    for (m in mocks.toList()) {
      val all = Mockito.mockingDetails(m).invocations.toList()
      val from = recordedTo[m] ?: 0
      if (all.size > from) fresh += all.subList(from, all.size)
      recordedTo[m] = all.size
    }
    val lines = fresh.sortedBy { it.sequenceNumber }.filter(::interesting).map(::line)
    out += "> $step"
    out += lines
  }

  private fun interesting(inv: org.mockito.invocation.Invocation): Boolean {
    val m = inv.method
    val name = names[inv.mock] ?: return false
    if (name.startsWith("TextView") || name.startsWith("GradientDrawable") || name.startsWith("LayoutParams") ||
        name.startsWith("Rect") || name.startsWith("IntentFilter") || name.startsWith("Options") ||
        name.startsWith("ExifInterface") || name.startsWith("bitmap(") || name.startsWith("AccelerateDecelerate") ||
        name.startsWith("LinearInterpolator") || name.startsWith("Canvas") || name.startsWith("Paint")) return false
    if ((m.name.startsWith("get") || m.name.startsWith("is")) && inv.arguments.isEmpty()) return false
    if (m.name == "toString" || m.name == "hashCode" || m.name == "equals") return false
    if (name == "ctx" && m.name == "getSharedPreferences") return false
    if (name.startsWith("uri(")) return false
    // Constructed-but-calendar-row layouts: their content follows the wall clock, not the controller.
    if (name.startsWith("LinearLayout") && m.name != "setVisibility") return false
    return true
  }

  private fun line(inv: org.mockito.invocation.Invocation): String =
      if (inv.mock === events) inv.arguments[0] as String
      // The stage matte is a class constant; whichever suite loads the stage first fixes its value.
      else if (inv.method.name == "setBackgroundColor" && inv.arguments[0] == SlideshowStage.MATTE) "${names[inv.mock]}.setBackgroundColor(MATTE)"
      else "${names[inv.mock]}.${inv.method.name}(${inv.arguments.joinToString(", ") { render(it) }})"

  fun render(v: Any?): String = when {
    v == null -> "null"
    names.containsKey(v) -> names[v]!!
    v is String -> "\"${clean(v)}\""
    v is Number || v is Boolean || v is Char -> v.toString()
    v is Enum<*> -> v.name
    v is SlideshowAsset -> "asset(${clean(v.key)})"
    v is SlideshowPhoto -> "photo(${clean(v.key)})"
    v is MediaItem -> "media(${clean(v.path)},${v.isVideo})"
    v is ScreensaverConfig.Settings -> "settings(mode=${v.mode}, interval=${v.intervalSec}, source=${v.source})"
    v is Pair<*, *> -> "(${render(v.first)}, ${render(v.second)})"
    v is Collection<*> -> v.joinToString(", ", "[", "]") { render(it) }
    v is Array<*> -> v.joinToString(", ", "[", "]") { render(it) }
    v is Map<*, *> -> v.entries.joinToString(", ", "{", "}") { "${render(it.key)}=${render(it.value)}" }
    v is Runnable || v is Function<*> -> "fn"
    v.javaClass.isSynthetic || v.javaClass.isAnonymousClass || v.javaClass.name.contains("\$\$Lambda") -> "fn"
    else -> v.javaClass.simpleName.ifBlank { "obj" }
  }

  fun clean(s: String): String = s.replace(dir.path, "<dir>")
      .replace(Regex("[\\w.$/]*[$][$]Lambda[\\w/$.@]*"), "λ").replace(Regex("""@[0-9a-f]{5,}"""), "")

  fun folder(vararg files: String): String {
    val folder = File(dir, "photos").apply { mkdirs() }
    files.forEachIndexed { i, f ->
      File(folder, f).apply { writeBytes(ByteArray(10 + i)); setLastModified(FILE_TIME) }
    }
    return folder.path
  }

  private fun prefs(name: String): SharedPreferences = prefs.getOrPut(name) { ScreensaverTestPrefs() }

  private fun putPref(p: SharedPreferences, k: String, v: Any?) {
    val e = p.edit()
    when (v) {
      null -> e.remove(k)
      is Boolean -> e.putBoolean(k, v)
      is Int -> e.putInt(k, v)
      is Long -> e.putLong(k, v)
      is String -> e.putString(k, v)
      else -> error("unsupported pref $k")
    }
    e.apply()
  }

  /** The viewer's pause button (its constructor callback). */
  fun viewerPause(value: Boolean) {
    @Suppress("UNCHECKED_CAST") (viewerArgs[4] as (Boolean) -> Unit)(value)
    flush("viewerPause $value")
  }

  /** The newest source discovery answers with [result]. */
  fun discover(result: Any?) { discoveries.last()(result); flush("discover ${render(result)}") }

  /** Runs the newest engine preparation as its worker would and records the photo list it built. */
  fun prepareEngine() { val json = preparations.last()({ true }); event("engine photos ${clean(json)}"); flush("prepareEngine") }

  /** Decodes the newest load's asset at [edge], as the loader's worker would. */
  fun decodeAsset(edge: Int) {
    val asset = loads.last().asset
    val bmp = asset.decode(edge)
    event("decoded ${render(bmp)} portrait=${asset.portrait?.invoke()} identity=${bmp?.let { asset.identity?.invoke(it) }}")
    flush("decodeAsset ${clean(asset.key)} $edge")
  }

  override fun close() {
    presence(savedPresence)
    unspy(SlideshowDecoder::class.java, decoderSpy.second)
    unspy(ScreensaverTestSource::class.java, testSourceSpy.second)
    runCatching { if (this::controller.isInitialized) controller.stop() }
    closers.asReversed().forEach { runCatching { it.close() } }
    statics.asReversed().forEach { runCatching { it.close() } }
    TimeZoneGuard.restore()
    dir.deleteRecursively()
  }

  /** Swaps a Kotlin object's INSTANCE for a spy answering through [answer]; returns (spy, original). */
  private fun <T : Any> spyObject(type: Class<T>, answer: (InvocationOnMock) -> Any?): Pair<T, T> {
    val field = type.getDeclaredField("INSTANCE")
    @Suppress("UNCHECKED_CAST") val original = field.get(null) as T
    val spy = Mockito.mock(type, Mockito.withSettings().spiedInstance(original).defaultAnswer { answer(it) })
    unsafe.putObjectVolatile(unsafe.staticFieldBase(field), unsafe.staticFieldOffset(field), spy)
    return spy to original
  }

  private fun unspy(type: Class<*>, original: Any) {
    val field = type.getDeclaredField("INSTANCE")
    unsafe.putObjectVolatile(unsafe.staticFieldBase(field), unsafe.staticFieldOffset(field), original)
  }

  companion object {
    const val UPTIME0 = 5_000_000L
    private val UNHANDLED = Any()
    const val FILE_TIME = 1_600_000_000_000L
    val unsafe: Unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run { isAccessible = true; get(null) as Unsafe }
  }
}

/** Golden runs format dates and calendars in one zone, whatever the machine's. */
internal object TimeZoneGuard {
  private var saved: java.util.TimeZone? = null
  fun utc() { if (saved == null) saved = java.util.TimeZone.getDefault(); java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC")) }
  fun restore() { saved?.let { java.util.TimeZone.setDefault(it) }; saved = null }
}

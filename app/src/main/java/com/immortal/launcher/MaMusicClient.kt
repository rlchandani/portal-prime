/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Persistent authenticated MA 2.10.7/schema 65 client. Providers (including YouTube Music)
 * and their credentials remain on the server. No Android APIs, logs, or preference access.
 *
 * Callbacks run on one background worker; marshal to the UI/store as needed. Generation
 * accompanies every callback and command. A selection change cancels old requests without
 * reopening an authenticated socket. close() is detach: it cancels I/O and delivers no more
 * callbacks after returning. Callback bodies should be short and must not wait for the worker.
 * Socket deadlines close blocked I/O; JVM hostname resolution follows the platform resolver.
 *
 * Default transport is bare-host raw LAN ws at /ws. HTTPS/WSS URLs are INCOMPATIBLE,
 * never silently downgraded. Use only on a trusted private network; TLS would require a
 * separate Transport implementation and endpoint configuration, not a scheme-stripping hack.
 */
class MaMusicClient(
    private val onSelection: (Long, MaMusicProtocol.Selection) -> Unit = { _, _ -> },
    private val onConnection: (Long, MusicConnection) -> Unit = { _, _ -> },
    private val transportFactory: ((String, Int) -> Transport)? = null,
    private val limits: Limits = Limits(),
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val unixMs: () -> Long = { System.currentTimeMillis() },
) : AutoCloseable {
  data class Config(val host: String, val port: Int, val token: String, val selectedPlayer: String) {
    override fun toString() = "MaMusicClient.Config(port=$port, token=<redacted>)"
    val sourceId: String get() = when {
      selectedPlayer.isBlank() -> ""
      selectedPlayer.startsWith("ma:") -> selectedPlayer
      else -> "ma:$selectedPlayer"
    }
    internal fun sameServer(other: Config) = host == other.host && port == other.port && token == other.token
  }

  /** Inject a bounded transport whose close() unblocks connect/read/write from any thread. */
  interface Transport : AutoCloseable {
    fun connect(timeoutMs: Int): Boolean
    fun sendText(text: String)
    /** SocketTimeoutException means idle poll, null means EOF. Never return a partial frame. */
    fun readText(): String?
    override fun close()
  }

  data class Limits(
      val pollMs: Int = 250,
      val requestTimeoutMs: Int = 6000,
      val refreshCoalesceMs: Long = 750,
      val progressRefreshMs: Long = 5000,
      val selectionMaxAgeMs: Long = 10_000,
      val reconnectInitialMs: Long = 500,
      val reconnectMaxMs: Long = 15_000,
      val livenessIntervalMs: Long = 30_000,
  ) {
    init {
      require(pollMs in 1..1000 && requestTimeoutMs in 1..60_000)
      require(refreshCoalesceMs in 1..60_000 && progressRefreshMs in 1..60_000 &&
          selectionMaxAgeMs in 1..60_000 && reconnectInitialMs in 1..60_000 &&
          reconnectMaxMs in reconnectInitialMs..60_000 && livenessIntervalMs in 1..60_000)
    }
  }

  /** Playback identity captured by the rendered UI, or at admission for legacy callers. */
  data class PlaybackTarget(val controlPlayerId: String, val queueId: String?)

  sealed interface Action {
    val expectedTarget: PlaybackTarget? get() = null
    data class Transport(val command: MusicCommand, override val expectedTarget: PlaybackTarget? = null) : Action
    data class Seek(val positionMs: Long, override val expectedTarget: PlaybackTarget? = null) : Action
    data class QueueItem(
        val id: String, val expectedQueueId: String? = null,
        override val expectedTarget: PlaybackTarget? = null,
    ) : Action
    data class Volume(val roomId: String, val percent: Int) : Action
    /** Canonical ma:<leader-id>; null captures the currently published leader at admission. */
    data class Group(
        val add: Set<String> = emptySet(), val remove: Set<String> = emptySet(),
        val expectedLeaderId: String? = null,
    ) : Action
  }

  enum class Outcome { ACKNOWLEDGED, REFRESHED, REJECTED, FAILED }

  /** REFRESHED is an observation after ACK, not a promise the requested change took effect. */
  data class CommandResult(
      val outcome: Outcome,
      val generation: Long,
      val sourceId: String,
      val messageId: String? = null,
      val selection: MaMusicProtocol.Selection? = null,
      val connection: MusicConnection? = null,
      val problem: String? = null,
  )

  private data class Epoch(val generation: Long, val config: Config)
  private data class Queued(
      val epoch: Epoch, val action: Action, val expectedTrackId: String?,
      val target: PlaybackTarget,
      val expiresAt: Long, val callback: (CommandResult) -> Unit,
  )
  private enum class Kind { AUTH, PLAYERS, QUEUE, ITEMS, COMMAND, LIVENESS }
  private class Pending(
      val id: String, val epoch: Epoch, val kind: Kind, val deadline: Long,
      val command: Queued? = null,
  ) {
    var timer: ScheduledFuture<*>? = null
    var chunks: JSONArray? = null
    var bytes = 0
    var parts = 0
  }
  private class Refresh(val epoch: Epoch, val revision: Long, val progressOnly: Boolean = false) {
    var players: MaMusicProtocol.Reply<List<MaMusicProtocol.Player>>? = null
    var queue: MaMusicProtocol.Reply<MaMusicProtocol.Queue?>? = null
    var items: MaMusicProtocol.Reply<MaMusicProtocol.QueueItems>? = null
  }

  private val gate = java.lang.Object()
  private val commands = ArrayBlockingQueue<Queued>(16)
  private val requestedRefresh = AtomicBoolean(false)
  private val watchdog = ScheduledThreadPoolExecutor(1) { task ->
    Thread(task, "MaMusicDeadline").apply { isDaemon = true }
  }.apply { removeOnCancelPolicy = true }
  @Volatile private var closed = false
  @Volatile private var desired: Epoch? = null
  @Volatile private var transport: Transport? = null
  @Volatile private var readyGeneration = -1L
  private var publishedSelection: MaMusicProtocol.Selection? = null // guarded by gate
  private var generation = 0L
  private var ioSerial = 0L // gate protects watchdog cancellation against the next operation
  private var serial = 0L
  private var epoch: Epoch? = null // below: worker-owned state
  private var sessionConfig: Config? = null
  private var helloVerified = false
  private var authenticated = false
  private var blocked = false
  private var pending: Pending? = null
  private var refreshing: Refresh? = null
  private var selection: MaMusicProtocol.Selection? = null
  private var selectionAt = 0L
  private var cachedPlayers: MaMusicProtocol.Reply<List<MaMusicProtocol.Player>>? = null
  private var cachedItems: MaMusicProtocol.Reply<MaMusicProtocol.QueueItems>? = null
  private var afterAck: Pair<Queued, String>? = null
  private var fullNeeded = false
  private var dirty = false
  private var immediate = false
  private var progressNeeded = false
  private var revision = 0L
  private var fullNotBefore = 0L
  private var lastFullAt = Long.MIN_VALUE / 2
  private var lastProgressAt = Long.MIN_VALUE / 2
  private var lastValidatedAt = 0L
  private var nextReconnectAt = 0L
  private var reconnectDelay = limits.reconnectInitialMs
  private val worker = Thread({ runWorker() }, "MaMusicClient").apply { isDaemon = true; start() }

  /** Returns the generation to use for commands/store publication. No token is ever logged. */
  fun config(config: Config): Long = synchronized(gate) {
    check(!closed) { "Music client is closed" }
    val previous = desired
    if (previous?.config == config) return@synchronized previous.generation
    val next = Epoch(++generation, config)
    desired = next
    readyGeneration = -1
    publishedSelection = null
    commands.clear()
    requestedRefresh.set(false)
    if (previous == null || !previous.config.sameServer(config)) {
      // close immediately, including a connect or request currently blocked in I/O.
      transport?.close()
      transport = null
    }
    gate.notifyAll()
    next.generation
  }

  /** Accepts canonical ma:<id> or a bare MA player ID. Does not change server credentials. */
  fun select(selectedPlayer: String): Long = synchronized(gate) {
    val config = desired?.config ?: return@synchronized generation
    config(config.copy(selectedPlayer = selectedPlayer))
  }

  /** Coalesces repeated requests; returns false after detach. Also retries a blocked connection. */
  fun refresh(): Boolean = synchronized(gate) {
    if (closed || desired == null) return@synchronized false
    requestedRefresh.set(true)
    gate.notifyAll()
    true
  }

  /**
   * Returns false without a READY selection, for a detached/stale/full mailbox or oversized action. Accepted work gets a
   * REJECTED/FAILED callback, or ACKNOWLEDGED then REFRESHED/FAILED. Config changes and detach
   * suppress stale callbacks. Actions are built from the worker's fresh authoritative selection.
   */
  fun command(action: Action, generation: Long, callback: (CommandResult) -> Unit): Boolean =
      command(action, generation, expectedTrackId = null, callback = callback)

  /** Capture the UI's current track; room/group/volume commands do not bind to a track. */
  fun command(
      action: Action, generation: Long, expectedTrackId: String?, callback: (CommandResult) -> Unit,
  ): Boolean = synchronized(gate) {
    val e = desired ?: return@synchronized false
    if (closed || generation != e.generation || readyGeneration != generation || !bounded(action) ||
        (expectedTrackId != null && expectedTrackId.length !in 1..4096)) return@synchronized false
    val ownedAction = if (action is Action.Group) {
      // Bind legacy callers to the leader they observed at admission, under the same
      // lock used to publish READY state. Never retarget a queued action after regrouping.
      val leader = action.expectedLeaderId ?: publishedSelection?.snapshot?.controlPlayerId
          ?: return@synchronized false
      action.copy(add = action.add.toSet(), remove = action.remove.toSet(), expectedLeaderId = leader)
    } else if (action is Action.QueueItem) {
      val queueId = action.expectedQueueId ?: publishedSelection?.queueId
          ?: return@synchronized false
      action.copy(expectedQueueId = queueId)
    } else action
    if (!bounded(ownedAction)) return@synchronized false
    // A track ID can survive regrouping or queue transfer. Capture the admitted
    // playback target under the READY publication lock so queued work cannot retarget it.
    val observed = publishedSelection?.snapshot ?: return@synchronized false
    val target = ownedAction.expectedTarget ?: PlaybackTarget(
        observed.controlPlayerId ?: return@synchronized false, observed.queueId)
    val accepted = commands.offer(Queued(e, ownedAction, expectedTrackId,
        target, elapsedMs() + limits.requestTimeoutMs * 4L, callback))
    if (accepted) gate.notifyAll()
    accepted
  }

  override fun close() {
    val active = synchronized(gate) {
      if (closed) return
      closed = true
      desired = null // release credentials
      readyGeneration = -1
      publishedSelection = null
      commands.clear()
      requestedRefresh.set(false)
      ioSerial++
      transport.also { transport = null }
    }
    runCatching { active?.close() }
    watchdog.shutdownNow()
    worker.interrupt()
    synchronized(gate) { gate.notifyAll() }
  }

  private fun runWorker() {
    try {
      while (!closed) {
        val wanted = desired
        if (wanted == null) { waitForWork(limits.pollMs.toLong()); continue }
        if (epoch !== wanted) adopt(wanted)
        if (closed || desired !== wanted) continue
        if (requestedRefresh.getAndSet(false)) {
          fullNeeded = true; immediate = true; blocked = false; nextReconnectAt = 0
        }
        val now = elapsedMs()
        expireCommands(now)
        if (transport == null) {
          if (blocked || now < nextReconnectAt) { waitForWork(limits.pollMs.toLong()); continue }
          connect(wanted)
          continue
        }
        if (pending == null) {
          if (!authenticated) {
            send(Kind.AUTH, JSONObject().put("message_id", nextId()).put("command", "auth")
                .put("args", JSONObject().put("token", wanted.config.token).put("device_name", "Immortal")))
          } else if ((fullNeeded || dirty || (commands.isNotEmpty() && now - selectionAt >= limits.selectionMaxAgeMs)) &&
              (immediate || (now >= fullNotBefore && now - lastFullAt >= limits.refreshCoalesceMs))) {
            startFull(wanted)
          } else if (!dirty && !fullNeeded && commands.isNotEmpty()) {
            val command = commands.poll()
            if (command != null && command.epoch === wanted) startCommand(command)
          } else if (progressNeeded && now - lastProgressAt >= limits.progressRefreshMs) {
            startProgress(wanted)
          } else if (now - lastValidatedAt >= limits.livenessIntervalMs) {
            // Idle/paused queues may emit no events. Probe the verified read API without
            // fetching queue items or changing the source/track observation used by commands.
            send(Kind.LIVENESS, MaMusicProtocol.playersCommand(nextId()))
          }
        }
        if (pending?.let { now >= it.deadline } == true) {
          disconnect(MusicConnection.OFFLINE, "Music Assistant request timed out")
          continue
        }
        val socket = transport ?: continue
        try {
          val text = socket.readText()
          if (closed || desired !== wanted || transport !== socket) continue
          if (text == null) disconnect(MusicConnection.OFFLINE, "Music Assistant disconnected")
          else receive(text)
        } catch (_: SocketTimeoutException) {
          // Idle poll only. Partial-frame timeouts close MaWebSocket, then fail on the next read.
        } catch (_: Exception) {
          if (!closed && desired === wanted) disconnect(MusicConnection.OFFLINE, "Music Assistant connection failed")
        }
      }
    } catch (_: InterruptedException) {
      // close() interrupts both idle wait and an injected transport's blocking read.
    } finally {
      pending?.timer?.cancel(false)
      pending = null
      refreshing = null
      afterAck = null
      sessionConfig = null
      epoch = null
      selection = null
      cachedPlayers = null
      cachedItems = null
      commands.clear()
      runCatching { transport?.close() }
      transport = null
      watchdog.shutdownNow()
    }
  }

  private fun adopt(next: Epoch) {
    cancelPending()
    refreshing = null; afterAck = null; selection = null; cachedPlayers = null; cachedItems = null
    val sameServer = sessionConfig?.sameServer(next.config) == true && transport != null
    epoch = next
    if (!sameServer) { authenticated = false; helloVerified = false; sessionConfig = null }
    blocked = false; fullNeeded = true; immediate = true; dirty = false; progressNeeded = false
    selectionAt = 0; revision = 0; fullNotBefore = 0
    nextReconnectAt = 0; reconnectDelay = limits.reconnectInitialMs
    val failure = validate(next.config)
    if (failure != null) {
      disconnect(failure.first, failure.second, terminal = true)
    } else connection(next, MusicConnection.CONNECTING)
  }

  private fun validate(config: Config): Pair<MusicConnection, String>? = when {
    config.host.isBlank() || config.sourceId.isBlank() -> MusicConnection.NOT_CONFIGURED to "Choose a Music Assistant server and player"
    !MaWebSocket.validHost(config.host) || config.port !in 1..65535 ->
      MusicConnection.INCOMPATIBLE to "Music Assistant requires a bare LAN host; HTTPS/WSS is unsupported"
    config.token.isBlank() -> MusicConnection.AUTH_REQUIRED to "Music Assistant API token required"
    config.token.length > 8192 || config.token.any { it.code < 32 || it.code == 127 } ->
      MusicConnection.AUTH_REQUIRED to "Invalid Music Assistant API token"
    MaMusicProtocol.activeQueueCommand("validate", config.sourceId) == null ->
      MusicConnection.INCOMPATIBLE to "Invalid Music Assistant player"
    else -> null
  }

  private fun connect(e: Epoch) {
    // Explicit refresh cannot bypass endpoint/token validation.
    val invalid = validate(e.config)
    if (invalid != null) { disconnect(invalid.first, invalid.second, true); return }
    connection(e, MusicConnection.CONNECTING)
    var socket: Transport? = null
    try {
      socket = transportFactory?.invoke(e.config.host, e.config.port)
          ?: SocketTransport(e.config.host, e.config.port, limits.pollMs)
      synchronized(gate) {
        if (closed || desired !== e) { socket.close(); return }
        transport = socket
      }
      val timer = guard(socket, limits.requestTimeoutMs)
      val connected = try { socket.connect(limits.requestTimeoutMs) } finally { timer.cancel(false); invalidateGuard() }
      if (closed || desired !== e || transport !== socket) { socket.close(); return }
      if (!connected) throw IOException()
      sessionConfig = e.config
      helloVerified = false; authenticated = false; lastValidatedAt = 0
    } catch (_: Exception) {
      runCatching { socket?.close() }
      if (!closed && desired === e) disconnect(MusicConnection.OFFLINE, "Music Assistant connection failed")
    }
  }

  private fun nextId(): String = "immortal-${++serial}"

  private fun send(kind: Kind, request: JSONObject?, command: Queued? = null) {
    val e = epoch ?: return
    val socket = transport ?: return
    if (closed || desired !== e) return
    if (request == null) { disconnect(MusicConnection.INCOMPATIBLE, "Invalid Music Assistant request", true); return }
    val p = Pending(request.getString("message_id"), e, kind, elapsedMs() + limits.requestTimeoutMs, command)
    pending = p
    try {
      p.timer = guard(socket, limits.requestTimeoutMs)
      socket.sendText(request.toString())
    } catch (_: Exception) {
      disconnect(MusicConnection.OFFLINE, "Music Assistant request failed")
    }
  }

  private fun guard(socket: Transport, timeoutMs: Int): ScheduledFuture<*> = synchronized(gate) {
    val ticket = ++ioSerial
    watchdog.schedule({
      synchronized(gate) {
        if (!closed && ioSerial == ticket && transport === socket) runCatching { socket.close() }
      }
    }, timeoutMs.toLong(), TimeUnit.MILLISECONDS)
  }

  private fun invalidateGuard() = synchronized(gate) { ioSerial++ }
  private fun cancelPending() {
    pending?.timer?.cancel(false)
    invalidateGuard()
    pending = null
  }

  private fun startFull(e: Epoch) {
    fullNeeded = false; immediate = false; dirty = false; progressNeeded = false
    lastFullAt = elapsedMs(); lastProgressAt = lastFullAt
    refreshing = Refresh(e, revision)
    send(Kind.PLAYERS, MaMusicProtocol.playersCommand(nextId()))
  }

  private fun startProgress(e: Epoch) {
    progressNeeded = false; lastProgressAt = elapsedMs()
    val selected = selection
    if (selected?.queueId == null || cachedPlayers == null) return
    refreshing = Refresh(e, revision, progressOnly = true).also { it.players = cachedPlayers; it.items = cachedItems }
    send(Kind.QUEUE, MaMusicProtocol.queueStateCommand(nextId(), selected.queueId))
  }

  private fun startCommand(command: Queued) {
    val selected = selection
    val id = nextId()
    val trackBound = command.action is Action.Transport || command.action is Action.Seek ||
        command.action is Action.QueueItem
    if (trackBound && (selected?.snapshot?.controlPlayerId != command.target.controlPlayerId ||
        selected?.snapshot?.queueId != command.target.queueId)) {
      result(command, Outcome.REJECTED, id, problem = "Music Assistant playback target changed before the action")
      return
    }
    if (trackBound && command.expectedTrackId != null &&
        selected?.snapshot?.track?.id != command.expectedTrackId) {
      result(command, Outcome.REJECTED, id, problem = "Music Assistant track changed before the action")
      return
    }
    val queueItem = command.action as? Action.QueueItem
    if (queueItem != null && (queueItem.expectedQueueId == null ||
        queueItem.expectedQueueId != selected?.queueId)) {
      result(command, Outcome.REJECTED, id, problem = "Music Assistant queue changed before the action")
      return
    }
    val group = command.action as? Action.Group
    if (group != null && (group.expectedLeaderId == null ||
        selected?.snapshot?.controlPlayerId != group.expectedLeaderId)) {
      result(command, Outcome.REJECTED, id, problem = "Music Assistant group leader changed before the action")
      return
    }
    val request = selected?.let { s -> when (val action = command.action) {
      is Action.Transport -> MaMusicProtocol.transportCommand(id, s, action.command)
      is Action.Seek -> MaMusicProtocol.seekCommand(id, s, action.positionMs)
      is Action.QueueItem -> MaMusicProtocol.queueItemCommand(id, s, action.id)
      is Action.Volume -> MaMusicProtocol.volumeCommand(id, s, action.roomId, action.percent)
      is Action.Group -> MaMusicProtocol.groupCommand(id, s, action.add, action.remove)
    } }
    if (request == null) {
      result(command, Outcome.REJECTED, id, problem = "Music Assistant action is unavailable")
      return
    }
    send(Kind.COMMAND, request, command)
  }

  private fun receive(text: String) {
    val e = epoch ?: return
    val envelope = envelope(text) ?: run {
      disconnect(MusicConnection.INCOMPATIBLE, "Invalid Music Assistant message", true); return
    }
    val messageId = envelope.opt("message_id")
    if (messageId == null) {
      if (envelope.has("server_version")) {
        if (envelope.opt("server_version") != "2.10.7" || envelope.opt("schema_version") != 65) {
          disconnect(MusicConnection.INCOMPATIBLE, "Music Assistant server version is unsupported", true)
        } else helloVerified = true
      } else if (authenticated) event(envelope)
      return
    }
    if (messageId == "connection" && envelope.has("error_code")) {
      disconnect(MusicConnection.INCOMPATIBLE, "Music Assistant server setup is required", true)
      return
    }
    val p = pending ?: return
    if (p.epoch !== e || desired !== e || messageId != p.id) return
    if (elapsedMs() >= p.deadline) {
      disconnect(MusicConnection.OFFLINE, "Music Assistant request timed out")
      return
    }
    val complete = assemble(p, envelope, text) ?: return
    cancelPending()
    when (p.kind) {
      Kind.AUTH -> when (val reply = MaMusicProtocol.parseAuthReply(complete, p.id)) {
        is MaMusicProtocol.Reply.Failure -> disconnect(reply.connection, reply.problem, true)
        is MaMusicProtocol.Reply.Success -> {
          if (!helloVerified) { disconnect(MusicConnection.INCOMPATIBLE, "Missing Music Assistant server contract", true); return }
          authenticated = true; fullNeeded = true; immediate = true
          lastValidatedAt = elapsedMs()
        }
      }
      Kind.COMMAND -> when (val reply = MaMusicProtocol.parseCommandReply(complete, p.id)) {
        is MaMusicProtocol.Reply.Failure -> {
          p.command?.let { result(it, Outcome.FAILED, p.id, connection = reply.connection, problem = reply.problem) }
          if (reply.connection == MusicConnection.AUTH_REQUIRED) disconnect(reply.connection, reply.problem, true)
          else { fullNeeded = true; immediate = true }
        }
        is MaMusicProtocol.Reply.Success -> {
          lastValidatedAt = elapsedMs()
          p.command?.let {
            afterAck = it to p.id
            result(it, Outcome.ACKNOWLEDGED, p.id)
          }
          fullNeeded = true; immediate = true
        }
      }
      Kind.PLAYERS -> {
        val refresh = refreshing ?: return
        refresh.players = MaMusicProtocol.parsePlayers(complete, p.id)
        if (refresh.players is MaMusicProtocol.Reply.Success) lastValidatedAt = elapsedMs()
        val provisional = MaMusicProtocol.select(e.config.sourceId, refresh.players!!)
        if (provisional.snapshot.connection != MusicConnection.READY) finish(provisional)
        else send(Kind.QUEUE, MaMusicProtocol.activeQueueCommand(nextId(), e.config.sourceId))
      }
      Kind.QUEUE -> {
        val refresh = refreshing ?: return
        refresh.queue = MaMusicProtocol.parseQueue(complete, p.id)
        if (refresh.queue is MaMusicProtocol.Reply.Success) lastValidatedAt = elapsedMs()
        val q = (refresh.queue as? MaMusicProtocol.Reply.Success)?.value
        if (refresh.progressOnly || q == null || refresh.queue is MaMusicProtocol.Reply.Failure) {
          finish(MaMusicProtocol.select(e.config.sourceId, refresh.players!!, refresh.queue,
              if (q == null) null else refresh.items, elapsedMs(), unixMs()))
        } else send(Kind.ITEMS, MaMusicProtocol.queueItemsCommand(nextId(), q.queueId, limit = 500))
      }
      Kind.ITEMS -> {
        val refresh = refreshing ?: return
        val q = (refresh.queue as? MaMusicProtocol.Reply.Success)?.value ?: return
        refresh.items = MaMusicProtocol.parseQueueItems(complete, q.queueId, p.id)
        if (refresh.items is MaMusicProtocol.Reply.Success) lastValidatedAt = elapsedMs()
        finish(MaMusicProtocol.select(e.config.sourceId, refresh.players!!, refresh.queue, refresh.items,
            elapsedMs(), unixMs()))
      }
      Kind.LIVENESS -> when (val reply = MaMusicProtocol.parsePlayers(complete, p.id)) {
        is MaMusicProtocol.Reply.Failure -> disconnect(reply.connection, reply.problem, true)
        // Only a complete, correlated, validated response proves liveness. Do not renew
        // selectionAt or publish these players with a cached queue/source mapping.
        is MaMusicProtocol.Reply.Success -> lastValidatedAt = elapsedMs()
      }
    }
  }

  /** Bound aggregate JSON bytes, array entries, and chunk count; deadlines never extend. */
  private fun assemble(p: Pending, envelope: JSONObject, text: String): String? {
    val partial = envelope.opt("partial")
    if (partial != null && partial !is Boolean) {
      disconnect(MusicConnection.INCOMPATIBLE, "Invalid Music Assistant partial reply", true); return null
    }
    p.bytes += text.toByteArray(Charsets.UTF_8).size
    if (++p.parts > 16 || p.bytes > MaWebSocket.MAX_MESSAGE_BYTES) {
      disconnect(MusicConnection.INCOMPATIBLE, "Music Assistant reply exceeds limit", true); return null
    }
    if (envelope.has("error_code")) return text
    if (partial == true || p.chunks != null) {
      if (p.kind != Kind.PLAYERS && p.kind != Kind.ITEMS && p.kind != Kind.LIVENESS) {
        disconnect(MusicConnection.INCOMPATIBLE, "Unexpected Music Assistant partial reply", true); return null
      }
      val array = envelope.optJSONArray("result")
      val chunks = p.chunks ?: JSONArray().also { p.chunks = it }
      if (array == null || array.length() + chunks.length() > 500) {
        disconnect(MusicConnection.INCOMPATIBLE, "Music Assistant partial reply exceeds limit", true); return null
      }
      for (index in 0 until array.length()) chunks.put(array.get(index))
      if (partial == true) return null
      return JSONObject().put("message_id", p.id).put("partial", false).put("result", chunks).toString()
    }
    return text
  }

  private fun finish(next: MaMusicProtocol.Selection) {
    val refresh = refreshing ?: return
    if (desired !== refresh.epoch || closed) return
    refreshing = null
    if (refresh.revision != revision) {
      // A group/source/items event arrived after this batch began. Its old replies cannot
      // publish a READY state or authorize commands; coalesce another authoritative batch.
      fullNeeded = true; dirty = true
      return
    }
    selection = next
    if (!refresh.progressOnly) selectionAt = elapsedMs()
    synchronized(gate) {
      if (!closed && desired === refresh.epoch) {
        val ready = next.snapshot.connection == MusicConnection.READY
        readyGeneration = if (ready) refresh.epoch.generation else -1
        publishedSelection = next.takeIf { ready }
      }
    }
    if (next.snapshot.connection == MusicConnection.READY) {
      cachedPlayers = refresh.players; cachedItems = refresh.items
      reconnectDelay = limits.reconnectInitialMs
    }
    connection(refresh.epoch, next.snapshot.connection)
    callback(refresh.epoch) { onSelection(refresh.epoch.generation, next) }
    afterAck?.let { (command, id) ->
      afterAck = null
      result(command, if (next.snapshot.connection == MusicConnection.READY) Outcome.REFRESHED else Outcome.FAILED,
          id, next, next.snapshot.connection, next.snapshot.problem)
    }
    if (next.snapshot.connection == MusicConnection.AUTH_REQUIRED) {
      disconnect(next.snapshot.connection, next.snapshot.problem ?: "Music Assistant authentication required", true)
    }
  }

  private fun event(event: JSONObject) {
    val type = event.opt("event") as? String ?: return
    val objectId = event.opt("object_id") as? String ?: return
    val selected = selection
    val observedControl = selected?.controlPlayer ?: refreshing?.players?.let {
      MaMusicProtocol.select(epoch?.config?.sourceId.orEmpty(), it).controlPlayer
    }
    val observedQueue = selected?.queueId
        ?: (refreshing?.queue as? MaMusicProtocol.Reply.Success)?.value?.queueId
        ?: observedControl?.activeSource ?: observedControl?.playerId
    when (type) {
      "queue_time_updated" -> if (objectId == selected?.queueId) progressNeeded = true
      "queue_updated", "queue_items_updated", "queue_added" ->
        if (objectId == observedQueue || objectId == observedControl?.playerId) {
          structuralEvent()
        }
      "player_updated", "player_added", "player_removed" -> {
        // Room availability/volume/grouping can change for any visible player.
        structuralEvent()
      }
    }
  }

  private fun structuralEvent() {
    if (!fullNeeded) fullNotBefore = elapsedMs() + limits.refreshCoalesceMs
    revision++
    fullNeeded = true; dirty = true
  }

  private fun expireCommands(now: Long) {
    while (commands.peek()?.let { it.expiresAt <= now } == true) {
      val expired = commands.poll() ?: break
      result(expired, Outcome.FAILED, connection = MusicConnection.OFFLINE,
          problem = "Music Assistant action timed out")
    }
  }

  private fun disconnect(connection: MusicConnection, problem: String, terminal: Boolean = false) {
    val e = epoch ?: return
    if (closed || desired !== e) return
    synchronized(gate) {
      if (desired === e) { readyGeneration = -1; publishedSelection = null }
    }
    val command = pending?.command
    val id = pending?.id
    cancelPending()
    val socket = synchronized(gate) { transport.also { transport = null } }
    runCatching { socket?.close() }
    authenticated = false; helloVerified = false; sessionConfig = null; refreshing = null
    blocked = terminal
    fullNeeded = true; immediate = true; dirty = false; progressNeeded = false
    selection = null; cachedPlayers = null; cachedItems = null
    command?.let { result(it, Outcome.FAILED, id, connection = connection, problem = problem) }
    afterAck?.let { (request, requestId) -> result(request, Outcome.FAILED, requestId, connection = connection, problem = problem) }
    afterAck = null
    // Queued work is rejected rather than surviving a reconnect with different queue ownership.
    while (true) {
      val queued = commands.poll() ?: break
      result(queued, Outcome.FAILED, connection = connection, problem = problem)
    }
    nextReconnectAt = elapsedMs() + reconnectDelay
    reconnectDelay = (reconnectDelay * 2).coerceAtMost(limits.reconnectMaxMs)
    connection(e, connection)
    // Endpoint validation has already classified this failure. A blank unconfigured
    // source is valid here and must not be reclassified by the player-selection adapter.
    val failed = MaMusicProtocol.Selection(MusicPlayerSnapshot(
        connection = connection, sourceId = e.config.sourceId, problem = problem))
    callback(e) { onSelection(e.generation, failed) }
  }

  private fun connection(e: Epoch, state: MusicConnection) = callback(e) { onConnection(e.generation, state) }

  private fun result(
      command: Queued, outcome: Outcome, id: String? = null,
      selected: MaMusicProtocol.Selection? = null, connection: MusicConnection? = null, problem: String? = null,
  ) = callback(command.epoch) {
    command.callback(CommandResult(outcome, command.epoch.generation, command.epoch.config.sourceId,
        id, selected, connection, problem))
  }

  private fun callback(e: Epoch, deliver: () -> Unit) = synchronized(gate) {
    // close/config wait for an in-progress callback; none can start after they return.
    if (!closed && desired === e) runCatching { deliver() }
    Unit
  }

  private fun waitForWork(ms: Long) = synchronized(gate) { if (!closed) gate.wait(ms) }

  private fun bounded(action: Action): Boolean = (action.expectedTarget?.let { target ->
    MaMusicProtocol.activeQueueCommand("validate", target.controlPlayerId) != null &&
        (target.queueId == null || MaMusicProtocol.queueStateCommand("validate", target.queueId) != null)
  } != false) && when (action) {
    is Action.QueueItem -> action.id.isNotBlank() && action.id.length in 1..256 &&
        (action.expectedQueueId == null ||
          (action.expectedQueueId.isNotBlank() && action.expectedQueueId.length in 1..256))
    is Action.Volume -> action.roomId.length in 1..259
    is Action.Group -> action.add.size + action.remove.size <= 64 &&
        (action.add + action.remove).all { it.length in 1..259 } &&
        (action.expectedLeaderId == null || (action.expectedLeaderId.length in 4..259 &&
            MaMusicProtocol.activeQueueCommand("validate", action.expectedLeaderId) != null))
    else -> true
  }

  private fun envelope(text: String): JSONObject? = try {
    require(text.length <= MaWebSocket.MAX_MESSAGE_BYTES && text.toByteArray(Charsets.UTF_8).size <= MaWebSocket.MAX_MESSAGE_BYTES)
    var depth = 0; var quoted = false; var escaped = false
    for (c in text) {
      if (quoted) {
        if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
      } else when (c) {
        '"' -> quoted = true
        '{', '[' -> { depth++; require(depth <= 32) }
        '}', ']' -> { depth--; require(depth >= 0) }
      }
    }
    require(!quoted && depth == 0)
    val tokener = JSONTokener(text)
    val value = tokener.nextValue() as? JSONObject
    require(tokener.nextClean() == '\u0000')
    value
  } catch (_: Exception) { null }

  private class SocketTransport(host: String, port: Int, private val pollMs: Int) : Transport {
    private val socket = MaWebSocket(host, port)
    override fun connect(timeoutMs: Int): Boolean = socket.connect(timeoutMs).also {
      if (it) socket.setReadTimeout(pollMs)
    }
    override fun sendText(text: String) = socket.sendText(text)
    override fun readText() = socket.readText()
    override fun close() = socket.close()
  }
}

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Asynchronous receiver observation/control only. No MA commands, identity mapping or audio policy.
 * Attach/reconnect query; only execute(UserStart) can start, after a caller's fresh admission check.
 * Cancelling an already sent command cannot undo it: observations become unknown and only queries follow.
 */
internal class MusicReceiverClient(
    private val transport: Transport,
    private val scheduler: Scheduler,
    private val onChanged: (View) -> Unit,
    private val canStart: (MusicReceiverProtocol.Observation) -> Boolean = { false },
    private val connectTimeoutMs: Long = 2_000,
    private val replyTimeoutMs: Long = 1_000,
    private val pollMs: Long = 500,
) {
  constructor(context: Context, onChanged: (View) -> Unit,
      canStart: (MusicReceiverProtocol.Observation) -> Boolean = { false }) :
      this(AndroidTransport(context.applicationContext), MainScheduler(), onChanged, canStart)

  enum class Connection { DETACHED, CONNECTING, CONNECTED, UNAVAILABLE, UNTRUSTED }
  enum class Failure { UNAVAILABLE, NOT_ADMITTED, BIND_FAILED, UNTRUSTED, CONNECT_TIMEOUT,
    REPLY_TIMEOUT, DISCONNECTED, SEND_FAILED, INVALID_REPLY, RECEIVER_ERROR, OBSERVATION_STALE,
    SUPERSEDED, CANCELLED }
  data class View(val connection: Connection, val observation: MusicReceiverProtocol.Observation?,
      val pendingWhat: Int? = null, val failure: Failure? = null)
  sealed interface Result {
    /** Receipt of protocol data, not proof of audible output or successful focus handoff. */
    data class Received(val reply: MusicReceiverProtocol.Reply) : Result
    data class Failed(val failure: Failure) : Result
  }
  fun interface Cancellation { fun cancel() }
  interface Scheduler {
    val nowElapsedMs: Long
    /** Tasks are serialized on one scheduler; production uses the Android main looper. */
    fun post(delayMs: Long, task: () -> Unit): Cancellation
  }
  enum class BindResult { BINDING, UNAVAILABLE, UNTRUSTED }
  interface Callbacks {
    fun connected()
    fun received(what: Int, fields: Map<String, Any?>)
    fun disconnected()
  }
  interface Transport {
    fun bind(callbacks: Callbacks): BindResult
    fun send(request: MusicReceiverProtocol.Request): Boolean
    fun unbind()
  }

  init {
    require(connectTimeoutMs in 1..10_000 && replyTimeoutMs in 1..5_000 && pollMs in 1..10_000)
  }
  @Volatile var view = View(Connection.DETACHED, null)
    private set
  private val desiredAttached = AtomicBoolean(false)
  private val lifecycle = AtomicLong()
  private val session = MusicReceiverProtocol.Session(replyTimeoutMs)
  private var bindingTicket = 0L
  private var bound = false
  private var connected = false
  private var pendingOperation: Operation? = null
  private var connectTimer: Cancellation? = null
  private var replyTimer: Cancellation? = null
  private var pollTimer: Cancellation? = null
  private var expiryTimer: Cancellation? = null
  private var reconnectTimer: Cancellation? = null

  private class Operation(val owner: Long, val callback: (Result) -> Unit) {
    val cancelled = AtomicBoolean(false)
    val finished = AtomicBoolean(false)
  }

  fun attach() {
    if (!desiredAttached.compareAndSet(false, true)) return
    val owner = lifecycle.incrementAndGet()
    scheduler.post(0) {
      if (!current(owner)) return@post
      release(Failure.DISCONNECTED)
      bind(owner)
    }
  }

  fun detach() {
    if (!desiredAttached.compareAndSet(true, false)) return
    val owner = lifecycle.incrementAndGet()
    scheduler.post(0) {
      if (lifecycle.get() != owner || desiredAttached.get()) return@post
      release(Failure.CANCELLED)
      publish(Connection.DETACHED, null, detached = true)
    }
  }

  /** The caller must verify client-to-MA mapping and communication/Jarvis admission in canStart. */
  fun execute(action: MusicReceiverProtocol.Action, callback: (Result) -> Unit = {}): Cancellation {
    val operation = Operation(lifecycle.get(), callback)
    val queued = scheduler.post(0) {
      if (operation.cancelled.get() || !current(operation.owner)) return@post
      if (!connected) {
        finish(operation, Result.Failed(Failure.UNAVAILABLE)); return@post
      }
      if (action is MusicReceiverProtocol.Action.UserStart &&
          !runCatching { canStart(action.observed) }.getOrDefault(false)) {
        finish(operation, Result.Failed(Failure.NOT_ADMITTED)); return@post
      }
      // Admission is caller code and can re-enter detach/cancel before returning.
      if (operation.cancelled.get() || !current(operation.owner) || !connected) return@post
      val request = session.issue(action, scheduler.nowElapsedMs)
      if (request == null) {
        finish(operation, Result.Failed(Failure.UNAVAILABLE)); return@post
      }
      pendingOperation?.let { finish(it, Result.Failed(Failure.SUPERSEDED)) }
      if (operation.cancelled.get() || !current(operation.owner) || !connected) return@post
      pendingOperation = operation
      send(request, operation.owner)
    }
    return Cancellation {
      if (!operation.finished.get() && operation.cancelled.compareAndSet(false, true)) {
        queued.cancel()
        scheduler.post(0) {
          if (!operation.finished.get() && current(operation.owner)) {
            operation.finished.set(true)
            loseBinding(Failure.CANCELLED, retry = true, owner = operation.owner)
          }
        }
      }
    }
  }

  private fun current(owner: Long): Boolean = desiredAttached.get() && lifecycle.get() == owner

  private fun bind(owner: Long) {
    if (!current(owner)) return
    reconnectTimer = null
    val ticket = ++bindingTicket
    publish(Connection.CONNECTING, null)
    if (!current(owner)) return
    val result = try {
      transport.bind(object : Callbacks {
        override fun connected() {
          scheduler.post(0) {
            if (!current(owner) || ticket != bindingTicket || !bound || connected) return@post
            connectTimer?.cancel(); connectTimer = null
            connected = true; session.connect()
            publish(Connection.CONNECTED, null)
            query(owner)
          }
        }
        override fun received(what: Int, fields: Map<String, Any?>) {
          val copy = fields.toMap()
          scheduler.post(0) {
            if (current(owner) && ticket == bindingTicket && connected) receive(what, copy, owner)
          }
        }
        override fun disconnected() {
          scheduler.post(0) {
            if (current(owner) && ticket == bindingTicket)
              loseBinding(Failure.DISCONNECTED, retry = true, owner = owner)
          }
        }
      })
    } catch (_: RuntimeException) { BindResult.UNAVAILABLE }
    bound = result == BindResult.BINDING
    when (result) {
      BindResult.BINDING -> connectTimer = scheduler.post(connectTimeoutMs) {
        if (current(owner) && ticket == bindingTicket && !connected)
          loseBinding(Failure.CONNECT_TIMEOUT, retry = true, owner = owner)
      }
      BindResult.UNTRUSTED -> {
        session.disconnect()
        publish(Connection.UNTRUSTED, Failure.UNTRUSTED)
      }
      BindResult.UNAVAILABLE -> {
        session.disconnect()
        publish(Connection.UNAVAILABLE, Failure.BIND_FAILED)
        reconnect(owner)
      }
    }
  }

  private fun query(owner: Long) {
    if (!current(owner) || !connected) return
    pollTimer?.cancel(); pollTimer = null
    val request = session.issue(MusicReceiverProtocol.Action.Query, scheduler.nowElapsedMs)
    if (request == null) scheduleQuery(owner, pollMs) else send(request, owner)
  }

  private fun send(request: MusicReceiverProtocol.Request, owner: Long) {
    if (!current(owner) || !connected || session.pending?.requestId != request.requestId) return
    pollTimer?.cancel(); pollTimer = null
    replyTimer?.cancel(); replyTimer = null
    if (request.what != 1) { expiryTimer?.cancel(); expiryTimer = null }
    val sent = try { transport.send(request) } catch (_: RuntimeException) { false }
    if (!sent) { loseBinding(Failure.SEND_FAILED, retry = true, owner = owner); return }
    publish(Connection.CONNECTED, null)
    if (!current(owner) || !connected) return
    replyTimer = scheduler.post(replyTimeoutMs) {
      if (current(owner) && connected && session.pending?.requestId == request.requestId &&
          session.timeout(scheduler.nowElapsedMs))
        loseBinding(Failure.REPLY_TIMEOUT, retry = true, owner = owner)
    }
  }

  private fun receive(what: Int, fields: Map<String, Any?>, owner: Long) {
    when (val result = session.accept(session.generation, what, fields, scheduler.nowElapsedMs)) {
      MusicReceiverProtocol.Acceptance.Ignored -> Unit
      is MusicReceiverProtocol.Acceptance.Rejected ->
        loseBinding(Failure.INVALID_REPLY, retry = true, owner = owner)
      is MusicReceiverProtocol.Acceptance.Accepted -> {
        replyTimer?.cancel(); replyTimer = null
        val operation = pendingOperation
        pendingOperation = null
        val error = when (val reply = result.reply) {
          is MusicReceiverProtocol.Reply.State -> reply.error
          is MusicReceiverProtocol.Reply.Error -> reply.error
        }
        if (error == "forbidden") {
          release(Failure.UNTRUSTED)
          publish(Connection.UNTRUSTED, Failure.UNTRUSTED)
          operation?.let { finish(it, Result.Received(result.reply)) }
          return
        }
        publish(Connection.CONNECTED, if (error == null) null else Failure.RECEIVER_ERROR)
        if (!current(owner) || !connected) return
        observeExpiry(owner)
        operation?.let { finish(it, Result.Received(result.reply)) }
        scheduleQuery(owner, if (what != 1 && error != "not_ready") 0 else pollMs)
      }
    }
  }

  private fun observeExpiry(owner: Long) {
    expiryTimer?.cancel(); expiryTimer = null
    val observed = session.observation ?: return
    val delay = MusicReceiverProtocol.FRESH_MS - (scheduler.nowElapsedMs - observed.observedAtElapsedMs)
    expiryTimer = scheduler.post(delay.coerceAtLeast(0)) {
      if (current(owner) && session.observation?.requestId == observed.requestId &&
          session.expireObservation(scheduler.nowElapsedMs)) {
        publish(Connection.CONNECTED, Failure.OBSERVATION_STALE)
        if (session.pending == null) scheduleQuery(owner, 0)
      }
    }
  }

  private fun scheduleQuery(owner: Long, delay: Long) {
    if (!current(owner) || !connected) return
    pollTimer?.cancel()
    pollTimer = scheduler.post(delay) { query(owner) }
  }
  private fun reconnect(owner: Long) {
    if (!current(owner)) return
    reconnectTimer?.cancel()
    reconnectTimer = scheduler.post(pollMs) { if (current(owner)) bind(owner) }
  }
  private fun loseBinding(failure: Failure, retry: Boolean, owner: Long) {
    if (!current(owner)) return
    release(failure)
    if (!current(owner)) return
    publish(Connection.UNAVAILABLE, failure)
    if (retry) reconnect(owner)
  }
  private fun release(failure: Failure) {
    bindingTicket++
    connectTimer?.cancel(); connectTimer = null
    replyTimer?.cancel(); replyTimer = null
    pollTimer?.cancel(); pollTimer = null
    expiryTimer?.cancel(); expiryTimer = null
    reconnectTimer?.cancel(); reconnectTimer = null
    connected = false
    session.disconnect()
    if (bound) { bound = false; runCatching { transport.unbind() } }
    val operation = pendingOperation
    pendingOperation = null
    operation?.let { finish(it, Result.Failed(failure)) }
  }
  private fun finish(operation: Operation, result: Result) {
    if (operation.finished.compareAndSet(false, true) && !operation.cancelled.get() && current(operation.owner))
      runCatching { operation.callback(result) }
  }
  private fun publish(connection: Connection, failure: Failure?, detached: Boolean = false) {
    if (!desiredAttached.get() && !detached) return
    val next = View(connection, session.observation, session.pending?.what, failure)
    if (view == next) return
    view = next
    runCatching { onChanged(next) }
  }

  companion object {
    /** Fail closed before bind, and again before using its Binder. No certificate/key material is logged. */
    internal fun trustedReceiverUid(context: Context, component: ComponentName, ownUid: Int): Int? = try {
      val caller = context.packageName
      if (caller !in setOf("com.immortal.launcher", "com.immortal.launcher.debug") ||
          component.packageName != MusicReceiverProtocol.RECEIVER_PACKAGE ||
          component.className != MusicReceiverProtocol.SERVICE_CLASS) null
      else {
        val pm = context.packageManager
        val launcher = pm.getApplicationInfo(caller, 0)
        val receiver = pm.getApplicationInfo(MusicReceiverProtocol.RECEIVER_PACKAGE, 0)
        val service = pm.getServiceInfo(component, 0)
        if (launcher.packageName != caller || launcher.uid != ownUid || !launcher.enabled ||
            receiver.packageName != MusicReceiverProtocol.RECEIVER_PACKAGE || !receiver.enabled ||
            service.packageName != MusicReceiverProtocol.RECEIVER_PACKAGE ||
            service.name != MusicReceiverProtocol.SERVICE_CLASS || !service.exported || !service.enabled ||
            service.applicationInfo?.uid != receiver.uid ||
            pm.checkSignatures(ownUid, receiver.uid) != PackageManager.SIGNATURE_MATCH) null
        else receiver.uid
      }
    } catch (_: PackageManager.NameNotFoundException) { null }
      catch (_: RuntimeException) { null }
  }
}

private class MainScheduler : MusicReceiverClient.Scheduler {
  private val handler = Handler(Looper.getMainLooper())
  override val nowElapsedMs: Long get() = SystemClock.elapsedRealtime()
  override fun post(delayMs: Long, task: () -> Unit): MusicReceiverClient.Cancellation {
    val runnable = Runnable { task() }
    handler.postDelayed(runnable, delayMs)
    return MusicReceiverClient.Cancellation { handler.removeCallbacks(runnable) }
  }
}

/** Explicit signed-package Messenger transport. Each binding owns its reply Handler and death recipient. */
private class AndroidTransport(private val context: Context) : MusicReceiverClient.Transport {
  private val main = Handler(Looper.getMainLooper())
  private val component = ComponentName(MusicReceiverProtocol.RECEIVER_PACKAGE, MusicReceiverProtocol.SERVICE_CLASS)
  private var binding: Binding? = null

  override fun bind(callbacks: MusicReceiverClient.Callbacks): MusicReceiverClient.BindResult {
    unbind()
    val uid = MusicReceiverClient.trustedReceiverUid(context, component, Process.myUid())
        ?: return MusicReceiverClient.BindResult.UNTRUSTED
    val next = Binding(callbacks, uid)
    binding = next
    val registered = try {
      context.bindService(Intent(MusicReceiverProtocol.BIND_ACTION).setComponent(component),
          next.connection, Context.BIND_AUTO_CREATE)
    } catch (_: RuntimeException) { false }
    next.registered = registered
    if (!registered) { unbind(); return MusicReceiverClient.BindResult.UNAVAILABLE }
    return MusicReceiverClient.BindResult.BINDING
  }

  override fun send(request: MusicReceiverProtocol.Request): Boolean {
    val active = binding ?: return false
    val remote = active.remote ?: return false
    return try {
      val args = Bundle()
      for ((key, value) in request.data) when (value) {
        is String -> args.putString(key, value)
        is Long -> args.putLong(key, value)
        is Int -> args.putInt(key, value)
        else -> return false
      }
      val message = Message.obtain()
      message.what = request.what
      message.data = args
      message.replyTo = active.reply
      remote.send(message)
      true
    } catch (_: RemoteException) { false } catch (_: RuntimeException) { false }
  }

  override fun unbind() {
    val old = binding ?: return
    binding = null
    old.incoming.removeCallbacksAndMessages(null)
    main.removeCallbacks(old.died)
    old.binder?.let { runCatching { it.unlinkToDeath(old.death, 0) } }
    old.binder = null; old.remote = null
    if (old.registered) {
      old.registered = false
      runCatching { context.unbindService(old.connection) }
    }
  }
  private fun fail(active: Binding) {
    if (binding !== active) return
    unbind()
    active.callbacks.disconnected()
  }

  private inner class Binding(val callbacks: MusicReceiverClient.Callbacks, val uid: Int) {
    var registered = false
    var binder: IBinder? = null
    var remote: Messenger? = null
    val died = Runnable { fail(this) }
    val death = IBinder.DeathRecipient { main.post(died) }
    val incoming = Handler(Looper.getMainLooper()) { message ->
      if (binding !== this) true
      else if (message.sendingUid != uid) { fail(this); true }
      else {
        val fields: Map<String, Any?> = try {
          val data = message.data
          data.classLoader = javaClass.classLoader
          if (data.size() > 12) emptyMap()
          else data.keySet().associateWith { data.get(it) }
        } catch (_: RuntimeException) { emptyMap() }
        callbacks.received(message.what, fields)
        true
      }
    }
    val reply = Messenger(incoming)
    val connection = object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName, service: IBinder) {
        if (binding !== this@Binding) return
        if (name != component || MusicReceiverClient.trustedReceiverUid(context, component, Process.myUid()) != uid) {
          fail(this@Binding); return
        }
        try {
          binder = service
          service.linkToDeath(death, 0)
          if (!service.isBinderAlive) { fail(this@Binding); return }
          remote = Messenger(service)
          callbacks.connected()
        } catch (_: RemoteException) { fail(this@Binding) }
          catch (_: RuntimeException) { fail(this@Binding) }
      }
      override fun onServiceDisconnected(name: ComponentName) { fail(this@Binding) }
      override fun onBindingDied(name: ComponentName) { fail(this@Binding) }
      override fun onNullBinding(name: ComponentName) { fail(this@Binding) }
    }
  }
}

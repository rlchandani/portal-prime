/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/** Each start owns its cancellation flag and handles; retiring it cannot affect a later start. */
internal class LanAudioSessions {
  private val lock = Any()
  private var current: Session? = null

  val isActive: Boolean get() = synchronized(lock) { current?.isActive == true }

  fun start(): Session {
    val next = Session()
    val retired = synchronized(lock) {
      val resources = current?.cancelLocked().orEmpty()
      current = next
      resources
    }
    retired.forEach { it.close() }
    return next
  }

  fun stop() {
    val retired = synchronized(lock) {
      val resources = current?.cancelLocked().orEmpty()
      current = null
      resources
    }
    retired.forEach { it.close() }
  }

  fun finish(session: Session) {
    val retired = synchronized(lock) {
      if (current === session) current = null
      session.cancelLocked()
    }
    retired.forEach { it.close() }
  }

  inner class Session internal constructor() {
    @Volatile var isActive = true
      private set
    private val resources = linkedSetOf<Resource>()

    /** Register before blocking IO; constructors completing after stop must close instead. */
    fun own(cleanup: () -> Unit): Resource? {
      val resource = Resource(cleanup)
      val accepted = synchronized(lock) {
        if (isActive) resources.add(resource) else false
      }
      if (!accepted) {
        resource.close()
        return null
      }
      return resource
    }

    // Called only with the owner lock held; cleanup itself runs outside that lock.
    internal fun cancelLocked(): List<Resource> {
      isActive = false
      return resources.toList().also { resources.clear() }
    }

    inner class Resource internal constructor(private val cleanup: () -> Unit) {
      private var closed = false

      /** Only short audio start/play or local bind calls belong here, never connect/read/write. */
      fun start(block: () -> Unit): Boolean = synchronized(this) {
        if (closed || !isActive) false
        else {
          block()
          true
        }
      }

      fun close() {
        synchronized(this) {
          if (closed) return
          closed = true
          runCatching { cleanup() }
        }
        synchronized(lock) { resources.remove(this) }
      }
    }
  }
}

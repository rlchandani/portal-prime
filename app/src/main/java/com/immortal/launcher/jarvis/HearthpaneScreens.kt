/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Which Hearthpane screen is in front, for Jarvis: its id (the floating card's `screen` hint) and a builder for its
 * `screen_context`. Each screen registers while it's **started** — the floating card only pauses the screen behind
 * it, so the screen stays the current one while Jarvis talks about it. The newest started screen wins. Read from the
 * tool provider's binder thread, so every read and write is synchronized; builders read immutable state snapshots.
 */
internal object HearthpaneScreens {
  const val CONTROL_CENTER = "control_center"
  const val MUSIC = "music"
  const val SETTINGS = "settings"
  const val CALLS = "calls"
  const val HOME = "home"

  private const val MAX_ID = 40
  private val idPattern = Regex("[a-z][a-z0-9_.]*")
  private val nextToken = AtomicLong()

  /** One registered screen: its current [id] (Home can be showing Music) and its `screen_context` (no pixels, ever). */
  class Entry(val id: () -> String, val context: () -> JSONObject)

  private val shown = LinkedHashMap<Long, Entry>()

  /** A screen id Jarvis accepts: a short lowercase id (mirrors Jarvis's `FloatContext.isValidScreen`). */
  fun isValidId(id: String?): Boolean = id != null && id.length <= MAX_ID && idPattern.matches(id)

  @Synchronized fun show(entry: Entry): Long = nextToken.incrementAndGet().also { shown[it] = entry }

  @Synchronized fun hide(token: Long) { shown.remove(token) }

  /** The screen in front, or null when none is registered (a third-party app, or the launcher stopped). */
  @Synchronized fun current(): Entry? = shown.values.lastOrNull()

  /** Register [entry] for as long as [owner] is started. */
  fun track(owner: LifecycleOwner, entry: Entry) {
    var token = 0L
    owner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START -> token = show(entry)
        Lifecycle.Event.ON_STOP -> hide(token)
        else -> Unit
      }
    })
  }

  /** Test seam: forget every registration. */
  @Synchronized internal fun clear() { shown.clear() }
}

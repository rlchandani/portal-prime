/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Requested cursors are disposable; only accepted scenes can change backward history. */
internal class SlideshowNavigation {
  private data class Scene(val anchor: String, val companion: String?)
  private var shown: Scene? = null
  private val history = ArrayList<Scene>()
  private var requested: String? = null
  private var historyTarget: Int? = null
  private var failedStep = false

  val currentKey: String? get() = shown?.anchor

  fun request(direction: Int, deck: List<String>): Int {
    if (deck.isEmpty()) return -1
    refresh(deck)
    val step = if (!failedStep && shown?.companion != null) 2 else 1
    val cursor = requested?.let(deck::indexOf)?.takeIf { it >= 0 }
    val target = if (direction < 0 && (cursor == null || historyTarget != null) && history.isNotEmpty()) {
      val previous = ((historyTarget ?: history.size) - 1).coerceAtLeast(0)
      historyTarget = previous
      deck.indexOf(history[previous].anchor)
    } else {
      historyTarget = null
      when {
        cursor != null -> cursor + if (direction < 0) -step else step
        shown == null -> if (direction < 0) deck.lastIndex else 0
        direction > 0 -> deck.indexOf(shown!!.companion ?: shown!!.anchor) + 1
        else -> deck.indexOf(shown!!.anchor) - step
      }
    }
    val index = ((target % deck.size) + deck.size) % deck.size
    requested = deck[index]
    return index
  }

  fun commit(anchor: String, companion: String?) {
    if (requested != null && requested != anchor) return
    if (shown?.anchor != anchor) {
      val target = historyTarget
      if (target != null && history.getOrNull(target)?.anchor == anchor) {
        while (history.size > target) history.removeAt(history.lastIndex)
      } else {
        shown?.let { history.add(it) }
        while (history.size > 32) history.removeAt(0)
      }
    }
    shown = Scene(anchor, companion?.takeUnless { it == anchor })
    cancelRequest()
  }

  /** A rejected image consumes one item, never an unaccepted two-photo page. */
  fun skipFailed() {
    historyTarget = null
    failedStep = true
  }

  fun cancelRequest() {
    requested = null
    historyTarget = null
    failedStep = false
  }

  fun refresh(deck: List<String>) {
    val keys = deck.toHashSet()
    fun Scene.rebase(): Scene? =
        if (anchor !in keys) null else copy(companion = companion?.takeIf { it in keys })
    shown = shown?.rebase()
    val previousTarget = historyTarget?.let { history.getOrNull(it)?.anchor }
    val retained = history.mapNotNull { it.rebase() }
    history.clear()
    history.addAll(retained)
    historyTarget = previousTarget?.let { key -> history.indexOfLast { it.anchor == key }.takeIf { it >= 0 } }
    if (requested !in keys) cancelRequest()
  }

  fun reset() {
    shown = null
    history.clear()
    cancelRequest()
  }
}

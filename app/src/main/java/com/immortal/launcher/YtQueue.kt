/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.random.Random

/** One playable YouTube video as the library/search layer hands it to the queue. */
data class YtTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String = "",
    /** Known length from the Data API, or null; the player's own duration wins once it reports. */
    val durationMs: Long? = null,
) {
  companion object {
    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

    /** YouTube video ids are exactly 11 URL-safe base64 characters. */
    fun isValidVideoId(id: String?): Boolean = id != null && VIDEO_ID.matches(id)
  }
}

/** A queue slot. [id] is unique within a queue's lifetime, so the same video can appear twice. */
data class YtQueueEntry(
    val id: Long,
    val track: YtTrack,
    /** Why the official player refused this video (e.g. embedding disabled), or null. */
    val unplayableReason: String? = null,
    /** Where to start when this entry next loads (a song interrupted by a shared "Play now"). */
    val resumeMs: Long? = null,
    /** The phone that shared it ("Rohit's phone"), for the quiet tag on the queue row. */
    val sharedBy: String? = null,
    /** Title/artist are placeholders until the official player reports the real ones. */
    val needsMeta: Boolean = false,
) {
  val playable: Boolean get() = unplayableReason == null
}

/** Repeat modes. Manual next/previous treat [ONE] like [ALL]; only auto-advance repeats a track. */
enum class YtRepeat { OFF, ALL, ONE }

/**
 * Immutable native play queue for the official YouTube player. Every operation returns a new
 * queue (or `this` when nothing changes). [currentIndex] is -1 exactly when [entries] is empty.
 * While shuffled, [unshuffledIds] remembers the original order so [unshuffled] can restore it.
 */
data class YtQueue(
    val entries: List<YtQueueEntry> = emptyList(),
    val currentIndex: Int = -1,
    /** Where the queue came from, e.g. "Playlist · Road trip". */
    val sourceLabel: String = "",
    val repeat: YtRepeat = YtRepeat.OFF,
    val unshuffledIds: List<Long>? = null,
    val nextId: Long = 1L,
) {
  val current: YtQueueEntry? get() = entries.getOrNull(currentIndex)
  val isEmpty: Boolean get() = entries.isEmpty()
  val isShuffled: Boolean get() = unshuffledIds != null

  /** Replaces the queue with [tracks] (invalid ids dropped), starting at [startIndex]. Unshuffles. */
  fun playNow(tracks: List<YtTrack>, startIndex: Int, label: String): YtQueue {
    val valid = tracks.filter { YtTrack.isValidVideoId(it.videoId) }
    if (valid.isEmpty()) return clear()
    val fresh = valid.mapIndexed { i, t -> YtQueueEntry(nextId + i, t) }
    return copy(entries = fresh, currentIndex = startIndex.coerceIn(0, fresh.lastIndex),
        sourceLabel = label, unshuffledIds = null, nextId = nextId + fresh.size)
  }

  /** Inserts [track] right after the current entry (or makes it current in an empty queue). */
  fun playNext(track: YtTrack): YtQueue = insertAfterCurrent(track)

  /** Appends [track] (it becomes current in an empty queue). */
  fun addToEnd(track: YtTrack): YtQueue {
    if (!YtTrack.isValidVideoId(track.videoId)) return this
    val entry = YtQueueEntry(nextId, track)
    return copy(entries = entries + entry, currentIndex = currentIndex.coerceAtLeast(0),
        unshuffledIds = unshuffledIds?.plus(entry.id), nextId = nextId + 1)
  }

  /**
   * Removes entry [id]. Removing the current entry makes the following one current (or the new
   * last one when it was last); callers compare [current] before/after to reload the player.
   */
  fun remove(id: Long): YtQueue {
    val index = entries.indexOfFirst { it.id == id }
    if (index < 0) return this
    val rest = entries.filterIndexed { i, _ -> i != index }
    val newIndex = when {
      rest.isEmpty() -> -1
      index < currentIndex -> currentIndex - 1
      else -> currentIndex.coerceAtMost(rest.lastIndex)
    }
    return copy(entries = rest, currentIndex = newIndex, unshuffledIds = unshuffledIds?.minus(id))
  }

  /** Moves the entry at [from] to [to]; the current entry stays current wherever it lands. */
  fun move(from: Int, to: Int): YtQueue {
    if (from == to || from !in entries.indices || to !in entries.indices) return this
    val currentId = current?.id
    val moving = entries[from]
    val reordered = entries.toMutableList().apply { removeAt(from); add(to, moving) }.toList()
    return copy(entries = reordered, currentIndex = reordered.indexOfFirst { it.id == currentId })
  }

  /** Moves entry [id] to play right after the current entry; current stays current. */
  fun playEntryNext(id: Long): YtQueue {
    val from = entries.indexOfFirst { it.id == id }
    if (from < 0 || from == currentIndex) return this
    return move(from, if (from > currentIndex) currentIndex + 1 else currentIndex)
  }

  /** Makes entry [id] current and gives it another chance if it was marked unplayable. */
  fun select(id: Long): YtQueue {
    val index = entries.indexOfFirst { it.id == id }
    if (index < 0) return this
    return copy(entries = entries.map { if (it.id == id) it.copy(unplayableReason = null) else it },
        currentIndex = index)
  }

  /** The next playable entry (wrapping for [YtRepeat.ALL]/[YtRepeat.ONE]), or null at the end. */
  fun next(): YtQueue? = step(forward = true, wrap = repeat != YtRepeat.OFF)?.let { copy(currentIndex = it) }

  /** Auto-advance after a track ends: [YtRepeat.ONE] stays on the same entry. */
  fun onEnded(): YtQueue? = if (repeat == YtRepeat.ONE && current?.playable == true) this else next()

  /**
   * Previous: restarts the current track (returns `this`) once more than
   * [RESTART_THRESHOLD_MS] has played, or at the start of a non-repeating queue.
   */
  fun previous(positionMs: Long): YtQueue {
    if (positionMs > RESTART_THRESHOLD_MS) return this
    val index = step(forward = false, wrap = repeat != YtRepeat.OFF) ?: return this
    return copy(currentIndex = index)
  }

  fun withRepeat(mode: YtRepeat): YtQueue = if (mode == repeat) this else copy(repeat = mode)

  /** Records why the player refused entry [id]; navigation then skips it. */
  fun markUnplayable(id: Long, reason: String): YtQueue =
      copy(entries = entries.map { if (it.id == id) it.copy(unplayableReason = reason) else it })

  /** Deterministic shuffle for [seed]: the current entry moves to the front and stays current. */
  fun shuffled(seed: Long): YtQueue {
    if (isShuffled || isEmpty) return this
    val head = current
    val others = entries.filter { it.id != head?.id }.shuffled(Random(seed))
    return copy(entries = listOfNotNull(head) + others, currentIndex = 0,
        unshuffledIds = entries.map { it.id })
  }

  /** Restores the pre-shuffle order (with later additions/removals applied); current follows. */
  fun unshuffled(): YtQueue {
    val order = unshuffledIds ?: return this
    val byId = entries.associateBy { it.id }
    val known = order.mapNotNull { byId[it] }
    val restored = known + entries.filter { it.id !in order }
    val currentId = current?.id
    return copy(entries = restored, currentIndex = restored.indexOfFirst { it.id == currentId },
        unshuffledIds = null)
  }

  /** Forgets [id]'s resume point once it has been loaded. */
  fun consumeResume(id: Long): YtQueue =
      if (entries.none { it.id == id && it.resumeMs != null }) this
      else copy(entries = entries.map { if (it.id == id) it.copy(resumeMs = null) else it })

  /** Fills placeholder metadata of entries playing [videoId] from what the official player reports. */
  fun fillMeta(videoId: String, title: String, artist: String): YtQueue {
    if (title.isBlank() || entries.none { it.needsMeta && it.track.videoId == videoId }) return this
    return copy(entries = entries.map {
      if (!it.needsMeta || it.track.videoId != videoId) it
      else it.copy(track = it.track.copy(title = title, artist = artist.ifBlank { it.track.artist }), needsMeta = false)
    })
  }

  /** Empties the queue; repeat mode survives and ids keep counting so they never collide. */
  fun clear(): YtQueue = YtQueue(repeat = repeat, nextId = nextId)

  private fun insertAfterCurrent(track: YtTrack): YtQueue {
    if (!YtTrack.isValidVideoId(track.videoId)) return this
    val entry = YtQueueEntry(nextId, track)
    val at = currentIndex + 1
    val list = entries.toMutableList().apply { add(at, entry) }.toList()
    val order = unshuffledIds?.let { ids ->
      val anchor = ids.indexOf(current?.id)
      ids.toMutableList().apply { add(anchor + 1, entry.id) }.toList()
    }
    return copy(entries = list, currentIndex = currentIndex.coerceAtLeast(0),
        unshuffledIds = order, nextId = nextId + 1)
  }

  /** Index of the nearest playable entry in a direction, or null. */
  private fun step(forward: Boolean, wrap: Boolean): Int? {
    if (isEmpty) return null
    val size = entries.size
    for (offset in 1..size) {
      val raw = if (forward) currentIndex + offset else currentIndex - offset
      if (!wrap && raw !in 0 until size) return null
      val index = ((raw % size) + size) % size
      if (index == currentIndex && !wrap) return null
      if (entries[index].playable) return index
    }
    return null
  }

  companion object {
    val EMPTY = YtQueue()
    /** "Previous" restarts the current track once more than this has played. */
    const val RESTART_THRESHOLD_MS = 3_000L
  }
}

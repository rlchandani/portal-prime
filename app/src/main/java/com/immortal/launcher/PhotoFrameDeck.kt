/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Deck building and walking for every source: ordering, the engine's local photo pool, which
 * photos pair with the current one, and the playlist arithmetic the classic renderer uses. Pure;
 * the shuffle and the header decode are passed in.
 */
internal object PhotoFrameDeck {
  /** Cap on the engine's local-photo pool: variety without repeats, a bounded header pass. */
  const val ENGINE_MAX_PHOTOS = 300
  /** Oriented sizes are measured for this many photos up front (it gates first paint). */
  const val DIMS_SEED = 80
  /** Local-folder curation: a short side below this is a thumbnail or icon, not a photo. */
  const val MIN_PHOTO_EDGE = 512

  /** [items] in play order: shuffled once at start when asked, never on refresh. */
  fun <T> ordered(items: List<T>, shuffle: Boolean, shuffler: (List<T>) -> List<T> = { it.shuffled() }): List<T> =
      if (shuffle) shuffler(items) else items

  /** The engine's local photos: `file://` URLs and the oriented sizes measured for the seed. */
  class EnginePool(val urls: List<String>, val dims: HashMap<String, Pair<Int, Int>>)

  /**
   * Images only (the HTML engine can't play local videos), at most [ENGINE_MAX_PHOTOS]. The first
   * [DIMS_SEED] photos are measured with [orientedSize]; a measured photo whose short side is under
   * [MIN_PHOTO_EDGE] is dropped as junk, and unmeasured photos still show at a safe default.
   */
  fun enginePool(ordered: List<MediaItem>, orientedSize: (String) -> Pair<Int, Int>?): EnginePool {
    val urls = ArrayList<String>()
    val dims = HashMap<String, Pair<Int, Int>>()
    var measured = 0
    for (item in ordered) {
      if (item.isVideo) continue
      if (urls.size >= ENGINE_MAX_PHOTOS) break
      val url = "file://" + item.path
      if (measured < DIMS_SEED) {
        measured++
        val d = orientedSize(item.path)
        if (d != null) {
          if (minOf(d.first, d.second) < MIN_PHOTO_EDGE) continue
          dims[url] = d
        }
      }
      urls.add(url)
    }
    return EnginePool(urls, dims)
  }

  /** The engine's share of a remote listing: image URLs only (WebView can't play the videos). */
  fun <T> engineImages(media: List<T>, isVideo: (T) -> Boolean, url: (T) -> String): List<String> =
      media.filterNot(isVideo).map(url)

  /** Classic playlist step [dir] from [index], wrapping both ways. */
  fun step(index: Int, dir: Int, size: Int): Int = ((index + dir) % size + size) % size

  /** Indices of the photos shown beside [anchor] (one, when the deck has more than one). */
  fun partnerIndices(anchor: Int, size: Int): List<Int> = (1 until minOf(size, 2)).map { (anchor + it) % size }

  /** The index after [at], wrapping. */
  fun after(at: Int, size: Int): Int = (at + 1) % size

  /** [list] with [item] inserted at [at] (a freshly shared photo spliced in after now). */
  fun <T> splice(list: List<T>, at: Int, item: T): List<T> = list.take(at) + item + list.drop(at)

  /**
   * The video URLs from [urls] in playlist order starting *after* [currentIndex] (wrapping), so
   * the prefetch worker warms the near future first. Skips the playing index; -1 means nothing
   * shown yet (playlist order from the top).
   */
  fun prefetchOrder(urls: List<String>, videos: Set<String>, currentIndex: Int): List<String> {
    if (urls.isEmpty() || videos.isEmpty()) return emptyList()
    val start = if (currentIndex in urls.indices) currentIndex + 1 else 0
    return (0 until urls.size - if (currentIndex in urls.indices) 1 else 0)
        .map { urls[(start + it) % urls.size] }
        .filter { it in videos }
  }

  /** Classic web history: forward to [index] + 1 when it exists, else null (load a fresh photo). */
  fun historyForward(index: Int, size: Int): Int? = if (index in 0 until size - 1) index + 1 else null

  /** Classic web history keeps at most this many decoded photos. */
  const val CLASSIC_HISTORY = 6
}

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * The feed slideshow keeps the previous photo for a swipe back. Before decoding a fresh photo
 * only the one on screen may stay: on a Portal+ two 2400 px photos plus the decoder's scratch
 * exceed [SlideshowPixelBudget], and the loader then waits forever for room that nothing frees,
 * so the slideshow stopped after its first two photos. Back works again once the new photo lands.
 */
internal object NativeHistoryTrim {
  fun <T> forFreshLoad(history: List<T>, index: Int): List<T> = listOfNotNull(history.getOrNull(index))
}

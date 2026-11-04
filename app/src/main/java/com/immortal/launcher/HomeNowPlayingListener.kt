/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Home's playback callback, with UI dispatch supplied by the owning composition. */
internal fun homeNowPlayingListener(
    post: (Runnable) -> Unit,
    render: (NowPlayingState?) -> Unit,
): NowPlayingHub.Listener = NowPlayingHub.Listener {
  // Initial replay and worker callbacks can arrive out of order before UI dispatch.
  post(Runnable { render(NowPlayingHub.current) })
}

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject

/** Bounded code-defined scalars only: no photo keys, Views, URL, caption or credential fields. */
internal object FilmstripRuntimeProbe {
  private var owner: Any? = null
  private var value: State? = null
  data class State(val ready: Boolean, val cards: Int, val index: Int, val advances: Long,
      val pixelsBytes: Long, val widthPx: Int, val heightPx: Int,
      val paused: Boolean, val hostActive: Boolean)
  @Synchronized fun record(owner: Any, state: State) {
    require(state.cards in 0..12 && state.index in 0..11 && state.advances >= 0 &&
        state.pixelsBytes in 0..128L*1024*1024 && state.widthPx in 0..32768 && state.heightPx in 0..32768)
    this.owner = owner
    value = state
  }
  @Synchronized fun clear(owner: Any) {
    if (this.owner === owner) { this.owner = null; value = null }
  }
  @Synchronized fun snapshot(): JSONObject? = value?.let {
    JSONObject().put("ready",it.ready).put("cards",it.cards).put("index",it.index)
        .put("advances",it.advances).put("pixelsBytes",it.pixelsBytes)
        .put("widthPx",it.widthPx).put("heightPx",it.heightPx)
        .put("paused",it.paused).put("hostActive",it.hostActive)
        .put("presentationTimeMeasured",false)
  }
}

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import java.io.File

/** Shared test inputs: the bundled dictionary (read from the source tree) and a few clocks. */
internal object MemoryFixtures {
  val dictionary: MemoryDictionary by lazy {
    MemoryDictionary.parse(File("src/main/assets/memory/dictionary.json").readText())
  }

  const val DAY = MemoryFact.DAY_MS

  /** 2026-11-23 15:00 UTC, a Monday. */
  const val NOW = 1_795_446_000_000L

  fun digest(id: String, vararg user: String, tools: List<MemoryDigest.ToolCall> = emptyList()) =
      MemoryDigest(id, NOW, null, user.toList(), tools)

  fun observe(id: String, vararg user: String, known: Set<String> = emptySet()) =
      MemoryExtractor(dictionary).extract(digest(id, *user), known)

  /** A runtime over in-memory maps, with the write timer captured instead of run. */
  class FakeStore {
    val saved = mutableMapOf<MemoryScope, MemoryProfile>()
    var usage = UsageCounts()
    var feedback = ForYouFeedback()
    var saves = 0
    var failSaves = false
    val scheduled = mutableListOf<() -> Unit>()
    var clock = NOW

    val runtime = MemoryRuntime(
        load = { saved[it] ?: MemoryProfile(it) },
        save = { p -> saves++; if (failSaves) false else { saved[p.scope] = p; true } },
        loadUsage = { usage },
        saveUsage = { usage = it; true },
        remove = { saved.remove(it); true },
        wipe = { saved.clear(); usage = UsageCounts(); feedback = ForYouFeedback(); true },
        schedule = { _, task -> scheduled += task },
        now = { clock },
        loadFeedback = { feedback },
        saveFeedback = { feedback = it; true },
    )

    fun runTimers() {
      val tasks = scheduled.toList()
      scheduled.clear()
      tasks.forEach { it() }
    }
  }
}

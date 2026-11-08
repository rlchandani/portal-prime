/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Update day (README Q3, recommended): each Portal's old local To-do list merges into the one
 * household list. Item ids derive from the text, so "Eggs" on both Portals becomes one item once
 * the replicas sync; [HouseholdItem.migratedFrom] remembers which Portals had it for the one-time
 * "Your two To-do lists are now one" dialog. The old [TodoStore] data is left untouched.
 */
internal object HouseholdListMigration {
  data class Summary(val title: String, val body: String)
  private val SPACE = Regex("\\s+")

  fun migrate(state: HouseholdListState, todos: List<TodoStore.TodoItem>, portal: String, nowMs: Long): HouseholdListState =
      todos.fold(state) { s, todo ->
        val text = HouseholdListText.clean(todo.text.trim().replace(SPACE, " ").take(HouseholdListText.MAX_TEXT))
            ?: return@fold s
        val existing = s.liveByText(text)
        if (existing != null) {
          if (portal in existing.migratedFrom) s
          else HouseholdListState(s.items + (existing.id to existing.copy(migratedFrom = existing.migratedFrom + portal)))
        } else {
          val at = todo.createdMs.takeIf { it in 1..nowMs } ?: nowMs
          HouseholdListOps.add(s, text, portal, at, migratedFrom = setOf(portal), done = todo.done).state
        }
      }

  /** Copy for the one-time dialog, or null when nothing was migrated. */
  fun summary(state: HouseholdListState): Summary? {
    val migrated = state.live().filter { it.migratedFrom.isNotEmpty() }
    if (migrated.isEmpty()) return null
    val portals = migrated.flatMap { it.migratedFrom }.distinct().sorted()
    val counts = portals.map { p -> p to migrated.count { p in it.migratedFrom } }
    fun items(n: Int) = if (n == 1) "1 item" else "$n items"
    if (portals.size == 1) {
      val (p, n) = counts.single()
      return Summary("Your To-do list is now the household list",
          "$p had ${items(n)}. They’re on the household list now, shared with every Portal and Calder Pocket on your phones.")
    }
    val had = counts.mapIndexed { i, (p, n) -> if (i == 0) "$p had ${items(n)}" else "$p had $n" }
    val dups = migrated.filter { it.migratedFrom.size > 1 }.map { it.text.value }.sorted()
    val dupLine = when (dups.size) {
      0 -> ""
      1 -> " ${dups[0]} was on both, so it’s there once."
      else -> " ${dups.joinToString(", ")} were on both, so they’re there once."
    }
    val title = if (portals.size == 2) "Your two To-do lists are now one" else "Your To-do lists are now one"
    return Summary(title, "${joinAnd(had)}. They’re all on the household list now.$dupLine It syncs with Calder Pocket on your phones too.")
  }

  private fun joinAnd(parts: List<String>): String =
      if (parts.size <= 1) parts.joinToString("") else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
}

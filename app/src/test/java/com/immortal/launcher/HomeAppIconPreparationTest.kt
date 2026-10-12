/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class HomeAppIconPreparationTest {
  private data class Entry(val pkg: String, val label: String, val brokenIcon: Boolean = false)
  private class Fixture {
    var labels = 0
    var renders = 0
    fun prepare(entries: List<Entry>): List<String> = prepareHomeAppIcons(
        entries, { it.pkg }, { labels++; if (it.label == "broken-label") error("unreadable"); it.label },
        { it == "Hidden" }, { entry, label ->
          renders++
          if (entry.brokenIcon) error("unreadable icon")
          "${entry.pkg}:$label"
        },
    )
  }

  @Test fun duplicateLauncherActivities_renderOnlyTheFirstSuccessfulTile() {
    val f = Fixture()
    assertEquals(listOf("one:Primary"), f.prepare(listOf(Entry("one", "Primary"), Entry("one", "Alternate"), Entry("one", "Debug"))))
    assertEquals(1, f.renders)
    assertEquals(1, f.labels)
  }

  @Test fun hiddenLabels_areRejectedBeforeIconPreparation() {
    val f = Fixture()
    assertEquals(listOf("visible:Normal"), f.prepare(listOf(Entry("hidden-a", "Hidden"), Entry("hidden-b", "Hidden"), Entry("visible", "Normal"))))
    assertEquals(1, f.renders)
  }

  @Test fun hiddenFirstAlias_doesNotExcludeItsVisibleSibling() {
    val f = Fixture()
    assertEquals(listOf("one:Normal"), f.prepare(listOf(Entry("one", "Hidden"), Entry("one", "Normal"))))
    assertEquals(1, f.renders)
  }

  @Test fun failedFirstIcon_fallsBackToTheNextActivityWithoutRenderingLaterDuplicates() {
    val f = Fixture()
    assertEquals(listOf("one:Working"), f.prepare(listOf(Entry("one", "Broken", true), Entry("one", "Working"), Entry("one", "Alternate"))))
    assertEquals(2, f.renders)
  }

  @Test fun failedLabel_doesNotClaimThePackage() {
    val f = Fixture()
    assertEquals(listOf("one:Working"), f.prepare(listOf(Entry("one", "broken-label"), Entry("one", "Working"))))
    assertEquals(1, f.renders)
  }

  @Test fun outputOrder_followsFirstAcceptedEntriesAcrossPackages() {
    val f = Fixture()
    assertEquals(listOf("b:Bee", "a:Aye", "c:See"), f.prepare(listOf(Entry("a", "Hidden"), Entry("b", "Bee"), Entry("a", "Aye"), Entry("b", "Other"), Entry("c", "See"))))
  }

  @Test fun allBrokenOrHiddenEntries_leaveAnEmptyInventory() {
    val f = Fixture()
    assertTrue(f.prepare(listOf(Entry("one", "broken-label"), Entry("two", "Broken", true), Entry("three", "Hidden"))).isEmpty())
  }

  @Test fun emptyInventory_doesNotLoadLabelsOrIcons() {
    val f = Fixture()
    assertTrue(f.prepare(emptyList()).isEmpty())
    assertEquals(0, f.labels)
    assertEquals(0, f.renders)
  }

  @Test fun repeatedAliasesWorkload_preparesThirtyIconsInsteadOfOneHundredTwenty() {
    val f = Fixture()
    val entries = (0 until 30).flatMap { pkg -> listOf(Entry("app$pkg", "Hidden"), Entry("app$pkg", "Primary"), Entry("app$pkg", "Alternate"), Entry("app$pkg", "Debug")) }
    val result = f.prepare(entries)
    println("homeAppIconWork candidates=${entries.size} retained=${result.size} labels=${f.labels} renders=${f.renders}")
    assertEquals(30, result.size)
    assertEquals(30, f.renders)
    assertEquals(60, f.labels)
  }
}

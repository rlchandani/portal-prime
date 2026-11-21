/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source tripwires from the 2026-11-20 accessibility audit (docs/quality/accessibility-audit-2026-11-20.md).
 * Each rule lists offending `File.kt:line`s so a new unlabeled or role-less control fails the build.
 */
class AccessibilitySourceScanTest {
  private val root: File =
      listOf(File("src/main/java/com/immortal/launcher"), File("app/src/main/java/com/immortal/launcher")).first { it.exists() }
  private val sources: List<Pair<String, String>> by lazy {
    root.walkTopDown().filter { it.isFile && it.extension == "kt" }
        .map { it.relativeTo(root).path to it.readText() }.sortedBy { it.first }.toList()
  }

  /** Raw interactive modifiers must say what they are (`role = Role.X`, or an explicit `role = null`). */
  @Test fun everyRawInteractiveModifierDeclaresARole() {
    val bad = sites(RAW_CALL).filter { (src, m) -> !argsOf(src, m).contains(ROLE_ARG) }.map { it.where }
    assertTrue("Add role = Role.Button/Tab/Switch… (or an explicit role = null for a container):\n" +
        bad.joinToString("\n"), bad.isEmpty())
  }

  /** Every interactive element carries a label: its own Text, a contentDescription, or a labelled helper. */
  @Test fun everyInteractiveElementHasALabel() {
    val bad = sites(ANY_CALL).filter { (src, m) ->
      val owner = ownerCall(src.text, m.range.first) ?: return@filter false
      val (callee, open) = owner
      if (callee == "Text" || callee.endsWith("Text")) return@filter false
      val close = closing(src.text, open)
      var k = close
      while (k < src.text.length && src.text[k].isWhitespace() && src.text[k] != '\n') k++
      val body = if (k < src.text.length && src.text[k] == '{') src.text.substring(k, closing(src.text, k)) else ""
      !LABEL.containsMatchIn(src.text.substring(open, close) + body)
    }.map { it.where }
    assertTrue("Give these controls a Text, contentDescription or label:\n" + bad.joinToString("\n"), bad.isEmpty())
  }

  /** A click handler that does nothing is announced as a button; swallow scrim taps with blockScrimTaps(). */
  @Test fun noNoOpClickHandlers() {
    val bad = sites(ANY_CALL).filter { (src, m) ->
      val after = closing(src.text, m.range.last)
      val rest = src.text.substring(after).trimStart(' ', '\t')
      if (m.value.endsWith("{")) NO_OP_BODY.matchesAt(src.text, m.range.last)
      else rest.startsWith("{") && NO_OP_BODY.matchesAt(rest, 0)
    }.map { it.where }
    assertTrue("Replace no-op clicks with blockScrimTaps():\n" + bad.joinToString("\n"), bad.isEmpty())
  }

  /**
   * Every window (setContent, Dialog, Popup) resets LocalViewConfiguration, so each one must re-apply
   * the 64 dp touch-target floor, directly or through PortalPrimeTheme.
   */
  @Test fun everyCompositionRootAppliesTheTouchTargetFloor() {
    val bad = mutableListOf<String>()
    for ((path, text) in sources) {
      for (m in ROOT_CALL.findAll(text)) {
        val lineStart = text.lastIndexOf('\n', m.range.first) + 1
        if (FUN_DECL.containsMatchIn(text.substring(lineStart, m.range.first))) continue
        var k = if (m.value.endsWith("{")) m.range.last else closing(text, m.range.last)
        while (k < text.length && text[k].isWhitespace()) k++
        if (k >= text.length || text[k] != '{') continue
        val body = text.substring(k, closing(text, k))
        if (!body.contains("ProvideHearthpaneTouchTargets") && !body.contains("PortalPrimeTheme"))
          bad += "$path:${line(text, m.range.first)}"
      }
    }
    assertTrue("Wrap these roots in ProvideHearthpaneTouchTargets { } or PortalPrimeTheme { }:\n" +
        bad.joinToString("\n"), bad.isEmpty())
  }

  /** A dimmed background that dismisses its sheet or dialog is announced as one "Close" or "Cancel" action. */
  @Test fun dismissingScrimsAreLabelledCloseOrCancel() {
    val found = mutableListOf<String>()
    val bad = mutableListOf<String>()
    for ((path, text) in sources) {
      for (m in SCRIM_BACKGROUND.findAll(text)) {
        val chain = text.substring(m.range.first).lineSequence().take(SCRIM_CHAIN_LINES).joinToString("\n")
        if (!SCRIM_DISMISS.containsMatchIn(chain)) continue
        val label = SCRIM_LABEL.find(chain)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
            ?: if (chain.contains("dismissScrim(")) "Close" else null
        found += "$path:${line(text, m.range.first)}"
        if (label !in SCRIM_LABELS) bad += "$path:${line(text, m.range.first)} ($label)"
      }
    }
    assertTrue("No dismissing scrims found; the scan pattern is stale", found.size >= MIN_DISMISSING_SCRIMS)
    assertTrue("Label these dismissing scrims \"Close\" or \"Cancel\":\n" + bad.joinToString("\n"), bad.isEmpty())
  }

  /**
   * Audit F13: a per-card action whose visible word repeats on its screen ("Video call", "Close") passes a
   * `spoken =` name saying whose it is. Unique ones (the one door's own sheet, a modal's Close) are listed.
   */
  @Test fun repeatedCardActionsNameTheirCard() {
    val found = mutableListOf<String>()
    val bad = mutableListOf<String>()
    for ((path, text) in sources) {
      for (m in CARD_ACTION_CALL.findAll(text)) {
        val lineStart = text.lastIndexOf('\n', m.range.first) + 1
        if (FUN_DECL.containsMatchIn(text.substring(lineStart, m.range.first))) continue
        val args = text.substring(m.range.last, closing(text, m.range.last))
        val generic = STRING_LITERAL.findAll(args).map { it.groupValues[1] }.firstOrNull { it in REPEATED_WORDS }
            ?: if (args.contains("CallMessageCopy.LEAVE")) CallMessageCopy.LEAVE else continue
        found += "$path:${line(text, m.range.first)}"
        if (!args.contains(SPOKEN_ARG) && "$path|$generic" !in UNIQUE_ON_SCREEN) bad += "$path:${line(text, m.range.first)} ($generic)"
      }
    }
    assertTrue("No repeated card actions found; the scan pattern is stale", found.size >= MIN_REPEATED_CARD_ACTIONS)
    assertTrue("Pass spoken = \"<word> <card name>\" so each copy is told apart:\n" + bad.joinToString("\n"), bad.isEmpty())
  }

  /** The widget header action and Customize legend build their names from the card (pure rules tested in AccessibilityDuplicateLabelsTest). */
  @Test fun widgetHeaderAndCustomizeLegendUseTheirCardName() {
    val ui = sources.first { it.first == "ApprovedHomeUi.kt" }.second
    assertTrue(ui.contains("HomeRefinements.headerActionSpoken(action, title)"))
    val grid = sources.first { it.first == "HomeWidgetGrid.kt" }.second
    assertTrue(grid.contains("HomeRefinements.widgetEditSpoken(label, widgetName)"))
    assertEquals(0, Regex("""ApprovedWidgetAction\("\w+", Modifier""").findAll(grid).count())
  }

  @Test fun tvFocusableDefaultsToAButtonRole() {
    val tv = sources.first { it.first == "TvFocus.kt" }.second
    // Both helpers (tvFocusable, tvFocusableRow) default to a Button role.
    assertEquals(2, Regex("""role: Role\? = Role\.Button""").findAll(tv).count())
    assertEquals(2, Regex("""fun Modifier\.tvFocusable(Row)?\(""").findAll(tv).count())
  }

  private class Source(val path: String, val text: String)
  private data class Site(val src: Source, val match: MatchResult) { val where get() = "${src.path}:${line(src.text, match.range.first)}" }

  private fun sites(call: Regex): List<Site> = sources.filter { it.first != "TvFocus.kt" && it.first != "ScrimModifiers.kt" }
      .flatMap { (path, text) ->
        val src = Source(path, text)
        call.findAll(text).filter { m ->
          val lineStart = text.lastIndexOf('\n', m.range.first) + 1
          !FUN_DECL.containsMatchIn(text.substring(lineStart, m.range.first))
        }.map { Site(src, it) }.toList()
      }

  private fun argsOf(src: Source, m: MatchResult): String =
      if (m.value.endsWith("(")) src.text.substring(m.range.last, closing(src.text, m.range.last)) else ""

  /** The composable call (Capitalized callee) whose argument list holds the modifier at [index]. */
  private fun ownerCall(text: String, index: Int): Pair<String, Int>? {
    var depth = 0
    var j = index
    while (j > 0) {
      j--
      when (text[j]) {
        ')', ']', '}' -> depth++
        '[' -> if (depth == 0) return null else depth--
        '{' -> if (depth == 0) return null else depth--
        '(' -> if (depth > 0) depth-- else {
          val callee = CALLEE.find(text.substring(maxOf(0, j - 80), j))?.groupValues?.get(1)?.substringAfterLast('.') ?: return null
          if (callee.firstOrNull()?.isUpperCase() == true) return callee to j
        }
      }
    }
    return null
  }

  companion object {
    private val RAW_CALL = Regex("""\.(clickable|combinedClickable|toggleable|selectable)\s*[({]""")
    private val ANY_CALL = Regex("""\.(clickable|combinedClickable|toggleable|selectable|tvFocusable|tvFocusableRow)\s*[({]""")
    private val ROOT_CALL = Regex("""(?<![\w.])(?:androidx\.compose\.ui\.window\.)?(Dialog|Popup)\s*\(|\bsetContent\s*\{""")
    private val ROLE_ARG = Regex("""\brole\s*=""")
    private val FUN_DECL = Regex("""^\s*(private |internal )?fun """)
    private val CALLEE = Regex("""([\w.]+)\s*$""")
    private val SCRIM_BACKGROUND = Regex("""\.background\((c\.scrim|\w*Colors\.Scrim|Color\(0x[0-9A-Fa-f]{2}000000\))\)""")
    private val SCRIM_DISMISS = Regex("""dismissScrim\(|\.clickable\(|callClick\(""")
    private val SCRIM_LABEL = Regex("""dismissScrim\("([^"]+)"|callClick\("([^"]+)"|contentDescription = "([^"]+)"""")
    private val SCRIM_LABELS = setOf("Close", "Cancel")
    /** The scrim modifier chain spans at most this many lines from its `.background(…)`. */
    private const val SCRIM_CHAIN_LINES = 3
    /** Fewer than the dismissing scrims in the tree on 2026-11-20 (11) means the scan stopped matching. */
    private const val MIN_DISMISSING_SCRIMS = 9
    private val CARD_ACTION_CALL = Regex("""(?<![\w.])(CallPill|CallRoundButton|CcButton)\s*\(""")
    private val STRING_LITERAL = Regex(""""([^"\\]*)"""")
    private val SPOKEN_ARG = Regex("""\bspoken\s*=""")
    /** Visible words that sit on every room, phone or alert card. */
    private val REPEATED_WORDS = setOf("Video call", "Audio call", "Show on Home", "Remove from Home", "Close", "Close…")
    /** Shown once on their screen: a door's own device sheet, a modal's dismiss, the ended-call screen's Message and Close. */
    private val UNIQUE_ON_SCREEN = setOf("ControlCenterControlDoor.kt|Close…", "ControlCenterModalChecks.kt|Close",
        "CallScreensEnded.kt|${CallMessageCopy.LEAVE}", "CallScreensEnded.kt|Close")
    /** Fewer than the card actions in the tree on 2026-11-20 (16) means the scan stopped matching. */
    private const val MIN_REPEATED_CARD_ACTIONS = 14
    private val NO_OP_BODY = Regex("""\{\s*(/\*[^*]*\*/\s*)?}""")
    private val LABEL = Regex("""\bText\s*\(|contentDescription|stateDescription|\btext\s*=|[Ll]abel\b|\btitle\b|\bname\s*=|spoken|ccSpeak|description|Caption|ToolsText""")

    /** Index just past the bracket matching the one at [open], skipping string literals. */
    private fun closing(text: String, open: Int): Int {
      var depth = 0
      var j = open
      while (j < text.length) {
        when (text[j]) {
          '"' -> { j++; while (j < text.length && text[j] != '"') j += if (text[j] == '\\') 2 else 1 }
          '(', '{', '[' -> depth++
          ')', '}', ']' -> { depth--; if (depth == 0) return j + 1 }
        }
        j++
      }
      return text.length
    }

    private fun line(text: String, index: Int): Int = text.substring(0, index).count { it == '\n' } + 1
  }
}

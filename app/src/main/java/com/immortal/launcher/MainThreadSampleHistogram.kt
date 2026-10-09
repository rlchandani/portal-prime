/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

/**
 * Caller serializes access. Retains only bounded class/method identities and counts, never
 * StackTraceElements, filenames, line numbers, Thread/Activity references or application values.
 * Inclusive frames are counted once per observation, including recursion. Counts describe
 * wall-time observations with profiling overhead, not CPU usage, running time or delivered FPS.
 */
internal class MainThreadSampleHistogram {
  private class Counts {
    var samples = 0L
    var waiting = 0L
    var outsideNativePoll = 0L
    var outsideNativePollWaiting = 0L
  }
  private val symbols = LinkedHashMap<String, Counts>()
  // Fixed counters remain available after dynamic symbol saturation. No per-sample keys or
  // retained stacks: a temporary bit mask marks each family at most once in the inspected depth.
  private val operationCounts = Array(operationRules.size) { Counts() }
  private var ticks = 0L
  private var samples = 0L
  private var waitingSamples = 0L
  private var nativePollSamples = 0L
  private var droppedSamples = 0L
  private var emptySamples = 0L
  private var errorSamples = 0L
  private var cancelledSamples = 0L
  private var limitDroppedSamples = 0L
  private var droppedTicks = 0L
  private var truncatedSamples = 0L
  private var discardedDepthFrames = 0L
  private var discardedSymbols = 0L
  private var oversizedFrames = 0L

  val canSample: Boolean get() = ticks < MAX_TICKS

  private fun beginTick(): Boolean {
    ticks++
    if (ticks <= MAX_TICKS) return true
    limitDroppedSamples++
    droppedSamples++
    return false
  }

  fun record(stack: Array<StackTraceElement>) {
    if (!beginTick()) return
    if (stack.isEmpty()) {
      emptySamples++
      droppedSamples++
      return
    }
    samples++
    val depth = minOf(stack.size, MAX_DEPTH)
    if (stack.size > depth) {
      truncatedSamples++
      discardedDepthFrames += stack.size - depth
    }
    val nativePoll = isNativePoll(stack[0])
    if (nativePoll) nativePollSamples++
    // Classification describes primitive presence in the inspected depth, not thread CPU state.
    val waiting = (0 until depth).any { isWaitPrimitive(stack[it]) }
    if (waiting) waitingSamples++
    var matchedFamilies = 0
    for (i in 0 until depth) {
      for (family in operationRules.indices) {
        val bit = 1 shl family
        if (matchedFamilies and bit == 0 && operationRules[family].matches(stack[i])) {
          matchedFamilies = matchedFamilies or bit
          val counts = operationCounts[family]
          counts.samples++
          if (waiting) counts.waiting++
          if (!nativePoll) {
            counts.outsideNativePoll++
            if (waiting) counts.outsideNativePollWaiting++
          }
        }
      }
    }
    val seen = HashSet<String>(depth)
    for (i in 0 until depth) {
      val frame = stack[i]
      if (frame.className.length.toLong() + 1 + frame.methodName.length > MAX_SYMBOL_CHARS) {
        oversizedFrames++ // Frame occurrences rejected before constructing a large identity.
        continue
      }
      val identity = frame.className + "." + frame.methodName
      if (!seen.add(identity)) continue
      val counts = symbols[identity] ?: if (symbols.size < MAX_SYMBOLS) {
        Counts().also { symbols[identity] = it }
      } else {
        discardedSymbols++ // Distinct unseen identities per sample; never evict known counts.
        continue
      }
      counts.samples++
      if (waiting) counts.waiting++
      if (!nativePoll) {
        counts.outsideNativePoll++
        if (waiting) counts.outsideNativePollWaiting++
      }
    }
  }

  fun recordError() {
    if (!beginTick()) return
    errorSamples++
    droppedSamples++
  }

  /** A completed stack read discarded because its session stopped/replaced/expired in flight. */
  fun recordDroppedSample() {
    if (!beginTick()) return
    cancelledSamples++
    droppedSamples++
  }

  /** Scheduled cadence slots skipped by collector delay, or an unsuccessful callback post. */
  fun recordDroppedTicks(count: Long) {
    require(count >= 0)
    droppedTicks += count
  }

  fun snapshot(): JSONObject {
    fun topFrames(outsideNativePoll: Boolean): JSONArray {
      fun count(c: Counts) = if (outsideNativePoll) c.outsideNativePoll else c.samples
      return JSONArray().apply {
        symbols.entries.filter { count(it.value) > 0 }
            .sortedWith(compareByDescending<Map.Entry<String, Counts>> { count(it.value) }.thenBy { it.key })
            .take(MAX_TOP_FRAMES).forEach { (symbol, c) ->
              val total = count(c)
              val waiting = if (outsideNativePoll) c.outsideNativePollWaiting else c.waiting
              put(JSONObject().put("symbol", symbol).put("samples", total)
                  .put("waitingSamples", waiting).put("otherSamples", total - waiting))
            }
      }
    }
    return JSONObject().put("measurement", "wall-time stack observations")
        .put("profilingOverhead", true).put("cpuTimeMeasured", false).put("deliveredFpsMeasured", false)
        .put("frameIdentity", "class.method; inclusive, once per observation")
        .put("classification", "Known wait primitive in inspected depth; other does not imply CPU execution")
        .put("outsideNativePollMeaning", "Leaf is not android.os.MessageQueue.nativePollOnce; may still be waiting or blocking")
        .put("sampleIntervalMs", INTERVAL_MS).put("maxDepth", MAX_DEPTH).put("maxSymbols", MAX_SYMBOLS)
        .put("maxSymbolChars", MAX_SYMBOL_CHARS).put("maxTicks", MAX_TICKS).put("maxTopFrames", MAX_TOP_FRAMES)
        .put("ticks", ticks).put("samples", samples).put("droppedSamples", droppedSamples)
        .put("emptySamples", emptySamples).put("errorSamples", errorSamples).put("cancelledSamples", cancelledSamples)
        .put("limitDroppedSamples", limitDroppedSamples).put("droppedTicks", droppedTicks)
        .put("waitingSamples", waitingSamples).put("otherSamples", samples - waitingSamples)
        .put("nativePollSamples", nativePollSamples).put("outsideNativePollSamples", samples - nativePollSamples)
        .put("uniqueSymbols", symbols.size).put("symbolLimitReached", symbols.size == MAX_SYMBOLS)
        .put("tickLimitReached", !canSample).put("truncatedSamples", truncatedSamples)
        .put("discardedDepthFrames", discardedDepthFrames).put("discardedSymbols", discardedSymbols)
        .put("oversizedFrames", oversizedFrames).put("topFrames", topFrames(false))
        .put("outsideNativePollFrames", topFrames(true))
        .put("operationFamilyDepthLimit", MAX_DEPTH)
        .put("operationFamilyCounting", "Inclusive once per non-empty observation, before dynamic symbol admission; families may overlap")
        .put("operationFamilyZeroMeaning", "No matching frame observed within the inspected depth; absence between observations or beyond that depth is unknown")
        .put("operationFamilies", JSONObject().apply {
          for (i in operationRules.indices) {
            val c = operationCounts[i]
            put(operationRules[i].name, JSONObject().put("matchRule", operationRules[i].snapshot())
                .put("samples", c.samples).put("waitingSamples", c.waiting)
                .put("otherSamples", c.samples - c.waiting)
                .put("nativePollSamples", c.samples - c.outsideNativePoll)
                .put("outsideNativePollSamples", c.outsideNativePoll)
                .put("outsideNativePollWaitingSamples", c.outsideNativePollWaiting)
                .put("outsideNativePollOtherSamples", c.outsideNativePoll - c.outsideNativePollWaiting))
          }
        })
  }

  /** Rules contain only fixed code symbols. Class/method matching is case-sensitive. */
  private class OperationRule(val name: String, private val classNames: Array<String>,
      private val includeNestedClasses: Boolean = false, private val methodNames: Set<String>? = null) {
    private val nestedPrefixes = if (includeNestedClasses) classNames.map { it + "$" } else emptyList()

    fun matches(frame: StackTraceElement): Boolean =
        (methodNames == null || frame.methodName in methodNames) &&
            (frame.className in classNames || nestedPrefixes.any { frame.className.startsWith(it) })

    fun snapshot(): JSONObject = JSONObject().put("classNames", JSONArray(classNames.asList()))
        .put("includeNestedClasses", includeNestedClasses).put("nestedClassDelimiter", "$")
        .put("allMethods", methodNames == null).put("methodNames", JSONArray(methodNames?.sorted() ?: emptyList<String>()))
        .put("caseSensitive", true)
  }

  companion object {
    // Validated against the cached Compose 1.10.4 ui/runtime classes.jar. These rules are
    // intentionally finite: no whole-compose package, substring or fuzzy method matching.
    private val operationRules = arrayOf(
        OperationRule("spatialRectManager", arrayOf("androidx.compose.ui.spatial.RectManager")),
        OperationRule("nodeCoordinatorGeometry", arrayOf("androidx.compose.ui.node.NodeCoordinator"),
            methodNames = setOf(
                "touchBoundsInRoot", "screenToLocal-MK-Hz9U", "localToScreen-MK-Hz9U",
                "windowToLocal-MK-Hz9U", "localToWindow-MK-Hz9U", "localPositionOf-R5De75A",
                "localPositionOf-S_NoaFU", "transformFrom-EL8BTi8", "transformToScreen-58bKbWc",
                "transformToAncestor-EL8BTi8", "transformFromAncestor-EL8BTi8", "localBoundingBoxOf",
                "ancestorToLocal-S_NoaFU", "ancestorToLocal", "localToRoot-MK-Hz9U",
                "toParentPosition-8S9VItk", "toParentPosition-8S9VItk\$default",
                "fromParentPosition-8S9VItk", "fromParentPosition-8S9VItk\$default",
                "rectInParent\$ui", "rectInParent\$ui\$default", "fromParentRect", "findCommonAncestor\$ui")),
        OperationRule("recomposerPerformRecompose", arrayOf("androidx.compose.runtime.Recomposer"),
            methodNames = setOf("performRecompose")),
        OperationRule("composerImpl", arrayOf("androidx.compose.runtime.ComposerImpl")),
        OperationRule("layoutMeasureDelegate", arrayOf(
            "androidx.compose.ui.node.LayoutNodeLayoutDelegate",
            "androidx.compose.ui.node.MeasureAndLayoutDelegate",
            "androidx.compose.ui.node.MeasurePassDelegate",
            "androidx.compose.ui.node.LookaheadPassDelegate"), includeNestedClasses = true),
        OperationRule("accessibilityDelegate", arrayOf(
            "androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat"), includeNestedClasses = true),
        OperationRule("semanticsWork", arrayOf(
            "androidx.compose.ui.semantics.SemanticsNode", "androidx.compose.ui.semantics.SemanticsNodeKt",
            "androidx.compose.ui.semantics.SemanticsNode_androidKt", "androidx.compose.ui.semantics.SemanticsOwner",
            "androidx.compose.ui.semantics.SemanticsOwnerKt", "androidx.compose.ui.semantics.SemanticsConfiguration",
            "androidx.compose.ui.semantics.SemanticsConfigurationKt", "androidx.compose.ui.semantics.SemanticsInfoKt",
            "androidx.compose.ui.semantics.SemanticsSortKt"), includeNestedClasses = true))

    const val INTERVAL_MS = 10L
    private const val MAX_DEPTH = 64
    private const val MAX_SYMBOLS = 256
    private const val MAX_SYMBOL_CHARS = 512
    private const val MAX_TICKS = 6000L
    private const val MAX_TOP_FRAMES = 24

    private fun isNativePoll(frame: StackTraceElement): Boolean =
        frame.className == "android.os.MessageQueue" && frame.methodName == "nativePollOnce"

    private fun isWaitPrimitive(frame: StackTraceElement): Boolean = when (frame.className) {
      "android.os.MessageQueue" -> isNativePoll(frame)
      "java.lang.Object" -> frame.methodName == "wait"
      "java.lang.Thread" -> frame.methodName == "sleep"
      "sun.misc.Unsafe", "jdk.internal.misc.Unsafe" -> frame.methodName == "park"
      "java.util.concurrent.locks.LockSupport" -> frame.methodName in setOf("park", "parkNanos", "parkUntil")
      else -> false
    }
  }
}

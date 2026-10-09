/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MainThreadSampleHistogramTest {
  private fun frame(name: String, method: String = "work", file: String? = "Source.kt", line: Int = 1) =
      StackTraceElement(name, method, file, line)

  private fun frames(snapshot: JSONObject): List<JSONObject> {
    val rows = snapshot.getJSONArray("topFrames")
    return (0 until rows.length()).map { rows.getJSONObject(it) }
  }

  @Test fun recursiveFramesCountOncePerObservationAcrossLines() {
    val h = MainThreadSampleHistogram()
    h.record(arrayOf(frame("example.Recursive", line = 10), frame("example.Recursive", line = 20),
        frame("example.Caller")))
    h.record(arrayOf(frame("example.Recursive", line = 30), frame("example.Other")))
    val j = h.snapshot()
    assertEquals(2L, j.getLong("ticks"))
    assertEquals(2L, j.getLong("samples"))
    val rows = frames(j).associateBy { it.getString("symbol") }
    assertEquals(3, rows.size)
    assertEquals(2L, rows.getValue("example.Recursive.work").getLong("samples"))
    assertEquals(1L, rows.getValue("example.Caller.work").getLong("samples"))
    assertEquals(1L, rows.getValue("example.Other.work").getLong("samples"))
  }

  @Test fun onlyClassAndMethodSymbolsLeaveTheHistogram() {
    val h = MainThreadSampleHistogram()
    h.record(arrayOf(frame("example.Work", "run", "/private/photos/household-secret.jpg", 982731)))
    val j = h.snapshot()
    assertEquals("example.Work.run", frames(j).single().getString("symbol"))
    assertFalse(j.toString().contains("household-secret"))
    assertFalse(j.toString().contains("982731"))
    assertFalse(j.toString().contains("/private/photos"))
    assertEquals("wall-time stack observations", j.getString("measurement"))
    assertTrue(j.getBoolean("profilingOverhead"))
    assertFalse(j.getBoolean("cpuTimeMeasured"))
    assertFalse(j.getBoolean("deliveredFpsMeasured"))
    assertTrue(j.get("ticks") is Number)
    assertTrue(j.get("topFrames") is org.json.JSONArray)
  }

  @Test fun waitingStacksStayVisibleAndSharedCallersKeepBothCounts() {
    val h = MainThreadSampleHistogram()
    h.record(arrayOf(frame("android.os.MessageQueue", "nativePollOnce", null, -2),
        frame("android.os.Looper", "loop")))
    h.record(arrayOf(frame("example.Work"), frame("android.os.Looper", "loop")))
    val j = h.snapshot()
    assertEquals(1L, j.getLong("waitingSamples"))
    assertEquals(1L, j.getLong("otherSamples"))
    val rows = frames(j).associateBy { it.getString("symbol") }
    assertEquals(1L, rows.getValue("android.os.MessageQueue.nativePollOnce").getLong("waitingSamples"))
    val loop = rows.getValue("android.os.Looper.loop")
    assertEquals(2L, loop.getLong("samples"))
    assertEquals(1L, loop.getLong("waitingSamples"))
    assertEquals(1L, loop.getLong("otherSamples"))
    assertTrue(j.getString("classification").contains("does not imply CPU"))
  }

  @Test fun platformWaitPrimitivesAreClassifiedWithoutMatchingUnrelatedMethodNames() {
    val h = MainThreadSampleHistogram()
    for ((name, method) in listOf("java.lang.Object" to "wait", "java.lang.Thread" to "sleep",
        "sun.misc.Unsafe" to "park", "jdk.internal.misc.Unsafe" to "park",
        "java.util.concurrent.locks.LockSupport" to "parkNanos")) h.record(arrayOf(frame(name, method)))
    h.record(arrayOf(frame("example.Player", "wait")))
    h.record(arrayOf(frame("example.Player", "park")))
    val j = h.snapshot()
    assertEquals(5L, j.getLong("waitingSamples"))
    assertEquals(2L, j.getLong("otherSamples"))
  }

  @Test fun missingAndFailedObservationsDoNotMasqueradeAsSuccessfulSamples() {
    val h = MainThreadSampleHistogram()
    h.record(emptyArray())
    h.recordError()
    h.recordDroppedSample()
    h.record(arrayOf(frame("example.Work")))
    h.recordDroppedTicks(7)
    val j = h.snapshot()
    assertEquals(4L, j.getLong("ticks"))
    assertEquals(1L, j.getLong("samples"))
    assertEquals(3L, j.getLong("droppedSamples"))
    assertEquals(1L, j.getLong("emptySamples"))
    assertEquals(1L, j.getLong("errorSamples"))
    assertEquals(1L, j.getLong("cancelledSamples"))
    assertEquals(7L, j.getLong("droppedTicks"))
    assertEquals(1L, j.getLong("otherSamples"))
  }

  @Test fun stackDepthStopsAt64AndReportsEveryDiscardedFrame() {
    val h = MainThreadSampleHistogram()
    h.record(Array(66) { frame("example.Depth$it") })
    val j = h.snapshot()
    assertEquals(64, j.getInt("uniqueSymbols"))
    assertEquals(1L, j.getLong("truncatedSamples"))
    assertEquals(2L, j.getLong("discardedDepthFrames"))
    assertEquals(0L, j.getLong("waitingSamples"))
    h.record(Array(64) { frame("example.Depth$it") } + frame("android.os.MessageQueue", "nativePollOnce"))
    assertEquals(0L, h.snapshot().getLong("waitingSamples"))
    assertEquals(3L, h.snapshot().getLong("discardedDepthFrames"))
  }

  @Test fun symbolSaturationKeepsExistingCountsAndReportsDistinctDiscardedSymbolsPerSample() {
    val h = MainThreadSampleHistogram()
    repeat(256) { h.record(arrayOf(frame("example.Symbol$it"))) }
    h.record(arrayOf(frame("example.Late"), frame("example.Late", line = 2), frame("example.Symbol0")))
    h.record(arrayOf(frame("example.Late"), frame("example.Symbol0")))
    val j = h.snapshot()
    assertEquals(258L, j.getLong("samples"))
    assertEquals(256, j.getInt("uniqueSymbols"))
    assertTrue(j.getBoolean("symbolLimitReached"))
    assertEquals(2L, j.getLong("discardedSymbols"))
    assertEquals(0L, j.getLong("droppedSamples"))
    assertEquals(258L, j.getLong("outsideNativePollSamples"))
    assertEquals(24, j.getJSONArray("outsideNativePollFrames").length())
    assertEquals("example.Symbol0.work", frames(j).first().getString("symbol"))
    assertEquals(3L, frames(j).first().getLong("samples"))
    assertTrue(frames(j).none { it.getString("symbol") == "example.Late.work" })
  }

  @Test fun top24AreRankedByObservedSampleCounts() {
    val h = MainThreadSampleHistogram()
    repeat(30) { h.record(arrayOf(frame("example.Rank$it"))) }
    repeat(3) { h.record(arrayOf(frame("example.Rank29"))) }
    val rows = frames(h.snapshot())
    assertEquals(24, rows.size)
    assertEquals("example.Rank29.work", rows.first().getString("symbol"))
    assertEquals(4L, rows.first().getLong("samples"))
    assertTrue(rows.drop(1).all { it.getLong("samples") == 1L })
  }

  @Test fun tickLimitPreventsFurtherStackAggregationAndCountsRejections() {
    val h = MainThreadSampleHistogram()
    repeat(6000) { h.record(arrayOf(frame("example.Repeated"))) }
    assertFalse(h.canSample)
    h.record(arrayOf(frame("example.BeyondLimit")))
    val j = h.snapshot()
    assertEquals(6001L, j.getLong("ticks"))
    assertEquals(6000L, j.getLong("samples"))
    assertEquals(1L, j.getLong("limitDroppedSamples"))
    assertEquals(1L, j.getLong("droppedSamples"))
    assertEquals(1, j.getInt("uniqueSymbols"))
    assertTrue(j.getBoolean("tickLimitReached"))
  }

  @Test fun excessivelyLongSymbolsAreCountedWithoutRetentionOrTruncationCollisions() {
    val h = MainThreadSampleHistogram()
    h.record(arrayOf(frame("a".repeat(508), "run"))) // identity length 512
    h.record(arrayOf(frame("a".repeat(509), "run")))
    val j = h.snapshot()
    assertEquals(1, j.getInt("uniqueSymbols"))
    assertEquals(1L, j.getLong("oversizedFrames"))
    assertEquals(2L, j.getLong("samples"))
    assertEquals(512, frames(j).single().getString("symbol").length)
  }

  @Test fun emptyHistogramHasNoInventedSamplesOrFrames() {
    val j = MainThreadSampleHistogram().snapshot()
    assertEquals(0L, j.getLong("ticks"))
    assertEquals(0L, j.getLong("samples"))
    assertEquals(0L, j.getLong("droppedSamples"))
    assertEquals(0, j.getJSONArray("topFrames").length())
    assertFalse(j.getBoolean("symbolLimitReached"))
    assertFalse(j.getBoolean("tickLimitReached"))
  }
  @Test fun outsideNativePollUsesOnlyTheLeafAndStillIncludesBlockingStacks() {
    val h = MainThreadSampleHistogram()
    h.record(arrayOf(frame("android.os.MessageQueue", "nativePollOnce"), frame("android.os.Looper", "loop")))
    h.record(arrayOf(frame("java.lang.Object", "wait"), frame("android.os.Looper", "loop")))
    h.record(arrayOf(frame("example.Work"), frame("android.os.MessageQueue", "nativePollOnce")))
    val j = h.snapshot()
    assertEquals(1L, j.getLong("nativePollSamples"))
    assertEquals(2L, j.getLong("outsideNativePollSamples"))
    assertTrue(j.getString("outsideNativePollMeaning").contains("waiting or blocking"))
    val rows = j.getJSONArray("outsideNativePollFrames")
    val bySymbol = (0 until rows.length()).map { rows.getJSONObject(it) }.associateBy { it.getString("symbol") }
    assertEquals(1L, bySymbol.getValue("java.lang.Object.wait").getLong("samples"))
    assertEquals(1L, bySymbol.getValue("java.lang.Object.wait").getLong("waitingSamples"))
    assertEquals(1L, bySymbol.getValue("android.os.MessageQueue.nativePollOnce").getLong("samples"))
    assertEquals(3L, j.getLong("waitingSamples"))
  }

  @Test fun outsideNativePollRecursiveCountsShareTheOverallSymbolBound() {
    val h = MainThreadSampleHistogram()
    h.record(arrayOf(frame("android.os.MessageQueue", "nativePollOnce"), frame("example.Recursive"),
        frame("example.Recursive", line = 2)))
    h.record(arrayOf(frame("example.Recursive"), frame("example.Recursive", line = 3)))
    val j = h.snapshot()
    assertEquals(2, j.getInt("uniqueSymbols"))
    assertEquals(2L, frames(j).first().getLong("samples"))
    val outside = j.getJSONArray("outsideNativePollFrames")
    assertEquals(1, outside.length())
    assertEquals("example.Recursive.work", outside.getJSONObject(0).getString("symbol"))
    assertEquals(1L, outside.getJSONObject(0).getLong("samples"))
  }

  @Test fun outsideNativePollRanksItsOwnTop24EvenWhenIdleAncestorsDominateOverall() {
    val h = MainThreadSampleHistogram()
    val idle = arrayOf(frame("android.os.MessageQueue", "nativePollOnce")) +
        Array(30) { frame("example.IdleAncestor$it") }
    repeat(5) { h.record(idle) }
    h.record(arrayOf(frame("androidx.compose.ui.spatial.RectManager", "onLayoutPositionChanged")))
    val j = h.snapshot()
    assertEquals(24, frames(j).size)
    assertTrue(frames(j).none { it.getString("symbol").contains("RectManager") })
    val outside = j.getJSONArray("outsideNativePollFrames")
    assertEquals(1, outside.length())
    assertEquals("androidx.compose.ui.spatial.RectManager.onLayoutPositionChanged",
        outside.getJSONObject(0).getString("symbol"))
    assertEquals(1L, outside.getJSONObject(0).getLong("samples"))
  }

  private fun family(snapshot: JSONObject, name: String): JSONObject =
      snapshot.getJSONObject("operationFamilies").getJSONObject(name)

  @Test fun fixedOperationFamiliesRemainVisibleAtZeroWithExactRulesAndDepthScope() {
    val j = MainThreadSampleHistogram().snapshot()
    val families = j.getJSONObject("operationFamilies")
    val names = setOf("spatialRectManager", "nodeCoordinatorGeometry", "recomposerPerformRecompose",
        "composerImpl", "layoutMeasureDelegate", "accessibilityDelegate", "semanticsWork")
    assertEquals(names, families.keys().asSequence().toSet())
    assertTrue(families.length() <= 10)
    assertEquals(64, j.getInt("operationFamilyDepthLimit"))
    for (name in names) {
      val row = families.getJSONObject(name)
      for (counter in listOf("samples", "waitingSamples", "otherSamples", "nativePollSamples",
          "outsideNativePollSamples", "outsideNativePollWaitingSamples", "outsideNativePollOtherSamples")) {
        assertEquals(0L, row.getLong(counter))
        assertTrue(row.get(counter) is Number)
      }
      assertTrue(row.getJSONObject("matchRule").getJSONArray("classNames").length() > 0)
    }
    val rectRule = family(j, "spatialRectManager").getJSONObject("matchRule")
    assertEquals("androidx.compose.ui.spatial.RectManager", rectRule.getJSONArray("classNames").getString(0))
    assertFalse(rectRule.getBoolean("includeNestedClasses"))
    assertTrue(rectRule.getBoolean("allMethods"))
    val recomposeRule = family(j, "recomposerPerformRecompose").getJSONObject("matchRule")
    assertFalse(recomposeRule.getBoolean("allMethods"))
    assertEquals(1, recomposeRule.getJSONArray("methodNames").length())
    assertEquals("performRecompose", recomposeRule.getJSONArray("methodNames").getString(0))
    val accessibilityRule = family(j, "accessibilityDelegate").getJSONObject("matchRule")
    assertTrue(accessibilityRule.getBoolean("includeNestedClasses"))
    assertEquals("$", accessibilityRule.getString("nestedClassDelimiter"))
    assertFalse(j.getBoolean("cpuTimeMeasured"))
    assertEquals("wall-time stack observations", j.getString("measurement"))
  }

  @Test fun operationFamiliesAreCountedEvenWhenEveryNewSymbolIsRejectedAtThe256Cap() {
    val h = MainThreadSampleHistogram()
    repeat(256) { h.record(arrayOf(frame("example.Admitted$it"))) }
    h.record(arrayOf(
        frame("androidx.compose.ui.spatial.RectManager", "onLayoutPositionChanged"),
        frame("androidx.compose.ui.node.NodeCoordinator", "localBoundingBoxOf"),
        frame("androidx.compose.runtime.Recomposer", "performRecompose"),
        frame("androidx.compose.runtime.ComposerImpl", "recompose"),
        frame("androidx.compose.ui.node.MeasurePassDelegate", "remeasure"),
        frame("androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat", "sendEvents"),
        frame("androidx.compose.ui.semantics.SemanticsNode", "getChildren")))
    val j = h.snapshot()
    assertEquals(256, j.getInt("uniqueSymbols"))
    assertEquals(7L, j.getLong("discardedSymbols"))
    assertEquals(24, j.getJSONArray("topFrames").length())
    assertEquals(24, j.getJSONArray("outsideNativePollFrames").length())
    for (name in listOf("spatialRectManager", "nodeCoordinatorGeometry", "recomposerPerformRecompose",
        "composerImpl", "layoutMeasureDelegate", "accessibilityDelegate", "semanticsWork")) {
      assertEquals(name, 1L, family(j, name).getLong("samples"))
      assertEquals(name, 1L, family(j, name).getLong("outsideNativePollSamples"))
    }
    assertTrue(frames(j).none { it.getString("symbol").startsWith("androidx.compose.") })
  }

  @Test fun recursiveAndDifferentMethodsWithinOneFamilyCountOncePerSample() {
    val h = MainThreadSampleHistogram()
    h.record(arrayOf(
        frame("androidx.compose.ui.spatial.RectManager", "onLayoutPositionChanged", line = 1),
        frame("androidx.compose.ui.spatial.RectManager", "onLayoutPositionChanged", line = 2),
        frame("androidx.compose.ui.spatial.RectManager", "updateSubhierarchy"),
        frame("androidx.compose.ui.node.MeasurePassDelegate", "measure"),
        frame("androidx.compose.ui.node.MeasurePassDelegate\$performMeasureBlock\$1", "invoke"),
        frame("androidx.compose.ui.node.LayoutNodeLayoutDelegate", "measure"),
        frame("androidx.compose.ui.node.LookaheadPassDelegate", "measure")))
    h.record(arrayOf(frame("androidx.compose.ui.spatial.RectManager", "updateSubhierarchy")))
    val j = h.snapshot()
    assertEquals(2L, family(j, "spatialRectManager").getLong("samples"))
    assertEquals(1L, family(j, "layoutMeasureDelegate").getLong("samples"))
    assertEquals(1L, family(j, "layoutMeasureDelegate").getLong("outsideNativePollSamples"))
  }

  @Test fun exactClassAndMethodFamiliesRejectNearbyNamesAndSyntheticRecomposeMethods() {
    val h = MainThreadSampleHistogram()
    for ((name, method) in listOf(
        "androidx.compose.ui.spatial.RectManagerExtra" to "updateSubhierarchy",
        "example.androidx.compose.ui.spatial.RectManager" to "updateSubhierarchy",
        "androidx.compose.ui.spatial.RectManager\$Helper" to "invoke",
        "androidx.compose.runtime.ComposerImplExtra" to "recompose",
        "androidx.compose.runtime.ComposerImpl\$Helper" to "invoke",
        "androidx.compose.runtime.RecomposerExtra" to "performRecompose",
        "androidx.compose.runtime.Recomposer" to "access\$performRecompose",
        "androidx.compose.runtime.Recomposer" to "performRecompose\$lambda\$0\$0",
        "androidx.compose.runtime.Recomposer" to "performRecomposeMore",
        "androidx.compose.ui.node.NodeCoordinator" to "draw",
        "androidx.compose.ui.node.NodeCoordinator" to "localToRoot",
        "androidx.compose.ui.node.NodeCoordinator" to "localBoundingBoxOfExtra",
        "androidx.compose.ui.node.NodeCoordinatorExtra" to "localBoundingBoxOf")) {
      h.record(arrayOf(frame(name, method)))
    }
    val before = h.snapshot()
    for (name in listOf("spatialRectManager", "composerImpl", "recomposerPerformRecompose", "nodeCoordinatorGeometry"))
      assertEquals(name, 0L, family(before, name).getLong("samples"))
    h.record(arrayOf(frame("androidx.compose.runtime.Recomposer", "performRecompose")))
    h.record(arrayOf(frame("androidx.compose.runtime.ComposerImpl", "recompose")))
    h.record(arrayOf(frame("androidx.compose.ui.spatial.RectManager", "onLayoutPositionChanged")))
    val after = h.snapshot()
    for (name in listOf("spatialRectManager", "composerImpl", "recomposerPerformRecompose"))
      assertEquals(name, 1L, family(after, name).getLong("samples"))
  }

  @Test fun geometryMatchesExactJvmMethodsConfirmedInCompose1104() {
    val h = MainThreadSampleHistogram()
    for (method in listOf("localToRoot-MK-Hz9U", "localPositionOf-R5De75A", "localPositionOf-S_NoaFU",
        "localBoundingBoxOf", "rectInParent\$ui", "rectInParent\$ui\$default", "transformFrom-EL8BTi8",
        "transformToScreen-58bKbWc", "toParentPosition-8S9VItk", "fromParentPosition-8S9VItk")) {
      h.record(arrayOf(frame("androidx.compose.ui.node.NodeCoordinator", method)))
    }
    val j = h.snapshot()
    assertEquals(10L, family(j, "nodeCoordinatorGeometry").getLong("samples"))
    val rule = family(j, "nodeCoordinatorGeometry").getJSONObject("matchRule")
    assertFalse(rule.getBoolean("allMethods"))
    assertFalse(rule.getBoolean("includeNestedClasses"))
    val methods = rule.getJSONArray("methodNames")
    assertTrue((0 until methods.length()).any { methods.getString(it) == "localToRoot-MK-Hz9U" })
  }

  @Test fun nestedFamilyMatchingRequiresTheExactDollarBoundaryAndAllowedOwners() {
    val h = MainThreadSampleHistogram()
    for (name in listOf(
        "androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompatExtra",
        "androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat_androidKt",
        "example.AndroidComposeViewAccessibilityDelegateCompat\$Nested",
        "androidx.compose.ui.node.MeasurePassDelegateExtra",
        "androidx.compose.ui.node.MeasurePassDelegateKt",
        "androidx.compose.ui.node.LayoutNodeLayoutDelegateFake\$Nested",
        "androidx.compose.ui.semantics.SemanticsNodeExtra",
        "androidx.compose.ui.semantics.SemanticsProperties",
        "example.androidx.compose.ui.semantics.SemanticsNode")) h.record(arrayOf(frame(name)))
    val before = h.snapshot()
    for (name in listOf("accessibilityDelegate", "layoutMeasureDelegate", "semanticsWork"))
      assertEquals(name, 0L, family(before, name).getLong("samples"))
    h.record(arrayOf(frame("androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat\$ComposeAccessibilityNodeProvider")))
    h.record(arrayOf(frame("androidx.compose.ui.node.MeasurePassDelegate\$performMeasureBlock\$1")))
    h.record(arrayOf(frame("androidx.compose.ui.node.LookaheadPassDelegate\$layoutChildrenBlock\$1")))
    h.record(arrayOf(frame("androidx.compose.ui.node.LayoutNodeLayoutDelegate")))
    h.record(arrayOf(frame("androidx.compose.ui.node.MeasureAndLayoutDelegate")))
    h.record(arrayOf(frame("androidx.compose.ui.semantics.SemanticsNode\$emitFakeNodes\$fakeNode\$1")))
    h.record(arrayOf(frame("androidx.compose.ui.semantics.SemanticsOwner")))
    h.record(arrayOf(frame("androidx.compose.ui.semantics.SemanticsConfiguration")))
    h.record(arrayOf(frame("androidx.compose.ui.semantics.SemanticsSortKt\$UnmergedConfigComparator\$1")))
    val after = h.snapshot()
    assertEquals(1L, family(after, "accessibilityDelegate").getLong("samples"))
    assertEquals(4L, family(after, "layoutMeasureDelegate").getLong("samples"))
    assertEquals(4L, family(after, "semanticsWork").getLong("samples"))
  }

  @Test fun familyVariantsKeepExactLeafNativePollAndOtherWaitingObservationsSeparate() {
    val h = MainThreadSampleHistogram()
    val rect = frame("androidx.compose.ui.spatial.RectManager", "onLayoutPositionChanged")
    val poll = frame("android.os.MessageQueue", "nativePollOnce")
    h.record(arrayOf(poll, rect, rect))
    h.record(arrayOf(frame("java.lang.Object", "wait"), rect, rect))
    h.record(arrayOf(rect, rect))
    h.record(arrayOf(rect, poll, rect)) // A deeper poll primitive is outside leaf-native-poll.
    val j = h.snapshot()
    val row = family(j, "spatialRectManager")
    assertEquals(4L, row.getLong("samples"))
    assertEquals(3L, row.getLong("waitingSamples"))
    assertEquals(1L, row.getLong("otherSamples"))
    assertEquals(1L, row.getLong("nativePollSamples"))
    assertEquals(3L, row.getLong("outsideNativePollSamples"))
    assertEquals(2L, row.getLong("outsideNativePollWaitingSamples"))
    assertEquals(1L, row.getLong("outsideNativePollOtherSamples"))
    assertEquals(1L, j.getLong("nativePollSamples"))
  }

  @Test fun familyMatchesAtDepth64ButNeverAtDepth65() {
    val h = MainThreadSampleHistogram()
    val rect = frame("androidx.compose.ui.spatial.RectManager", "onLayoutPositionChanged")
    h.record(Array(63) { frame("example.Ancestor") } + rect)
    h.record(Array(64) { frame("example.Ancestor") } + rect)
    val j = h.snapshot()
    assertEquals(2L, j.getLong("samples"))
    assertEquals(1L, family(j, "spatialRectManager").getLong("samples"))
    assertEquals(64, j.getInt("operationFamilyDepthLimit"))
    assertEquals(1L, j.getLong("discardedDepthFrames"))
  }

  @Test fun familyCountingPrecedesOversizedSymbolRejectionToo() {
    val h = MainThreadSampleHistogram()
    h.record(arrayOf(frame("androidx.compose.runtime.ComposerImpl", "x".repeat(1024))))
    val j = h.snapshot()
    assertEquals(1L, family(j, "composerImpl").getLong("samples"))
    assertEquals(1L, j.getLong("oversizedFrames"))
    assertEquals(0, j.getInt("uniqueSymbols"))
  }

  @Test fun errorsEmptyCancelledAndTickLimitedObservationsNeverCountAnOperationFamily() {
    val h = MainThreadSampleHistogram()
    h.record(emptyArray())
    h.recordError()
    h.recordDroppedSample()
    repeat(5997) { h.record(arrayOf(frame("example.Other"))) }
    h.record(arrayOf(frame("androidx.compose.ui.spatial.RectManager", "onLayoutPositionChanged")))
    val j = h.snapshot()
    assertEquals(5997L, j.getLong("samples"))
    assertEquals(4L, j.getLong("droppedSamples"))
    assertEquals(0L, family(j, "spatialRectManager").getLong("samples"))
  }

  @Test fun operationCountingRetainsNeitherInputStackArrayNorStackElementsOrThreadReferences() {
    val h = MainThreadSampleHistogram()
    val stack = arrayOf(frame("androidx.compose.ui.spatial.RectManager", "onLayoutPositionChanged",
        "/private/photos/household-secret.jpg", 982731))
    h.record(stack)
    val before = h.snapshot().toString()
    assertNoRetainedStackInputs(h, stack)
    stack[0] = frame("example.Replaced")
    assertEquals(before, h.snapshot().toString())
    assertEquals(1L, family(h.snapshot(), "spatialRectManager").getLong("samples"))
    assertFalse(before.contains("household-secret"))
    assertFalse(before.contains("982731"))
  }

  private fun assertNoRetainedStackInputs(h: MainThreadSampleHistogram, stack: Array<StackTraceElement>) {
    // Deterministic reachability check: adding a cached stack or a capturing family predicate
    // would violate this contract even if its JSON happened to omit filenames.
    val queue = java.util.ArrayDeque<Any>()
    val visited = java.util.IdentityHashMap<Any, Boolean>()
    queue.add(h)
    while (queue.isNotEmpty()) {
      val value = queue.removeFirst()
      if (visited.put(value, true) != null) continue
      assertFalse("Input stack array retained", value === stack)
      assertFalse("StackTraceElement retained", value is StackTraceElement)
      assertFalse("Thread retained", value is Thread)
      when (value) {
        is Map<*, *> -> for ((key, item) in value) {
          key?.let { queue.add(it) }; item?.let { queue.add(it) }
        }
        is Iterable<*> -> value.forEach { it?.let { item -> queue.add(item) } }
        is Array<*> -> value.forEach { it?.let { item -> queue.add(item) } }
        else -> if (value.javaClass.name.startsWith("com.immortal.launcher.MainThreadSampleHistogram")) {
          for (field in value.javaClass.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
            field.isAccessible = true
            field.get(value)?.let { queue.add(it) }
          }
        }
      }
    }
  }

  private fun ranked(histogram: JSONObject): List<JSONObject> {
    val rows = histogram.getJSONArray("topFrames")
    return (0 until rows.length()).map { rows.getJSONObject(it) }
  }

  @Test fun separateHistogramsPublishZeroCountsFiniteCapsAndSelectionDepths() {
    val j = MainThreadSampleHistogram().snapshot()
    val leaf = j.getJSONObject("leafHistogram")
    val app = j.getJSONObject("firstAppHistogram")
    assertEquals(128, leaf.getInt("maxSymbols"))
    assertEquals(96, app.getInt("maxSymbols"))
    assertEquals(1, leaf.getInt("depthLimit"))
    assertEquals(64, app.getInt("depthLimit"))
    assertEquals("com.immortal.launcher.", app.getString("classPrefix"))
    for (histogram in listOf(leaf, app)) {
      assertEquals(512, histogram.getInt("maxSymbolChars"))
      assertEquals(24, histogram.getInt("maxTopFrames"))
      assertEquals(64, histogram.getInt("waitingClassificationDepthLimit"))
      for (name in listOf("observations", "samples", "admittedSamples", "uniqueSymbols",
          "discardedAdmissions", "oversizedIdentities", "waitingSamples", "otherSamples",
          "nativePollSamples", "outsideNativePollSamples")) assertEquals(0L, histogram.getLong(name))
      assertFalse(histogram.getBoolean("symbolLimitReached"))
      assertEquals(0, ranked(histogram).size)
    }
    assertEquals(0L, leaf.getLong("excludedNativePollSamples"))
    assertEquals(0L, app.getLong("noMatchSamples"))
    assertEquals(0L, app.getLong("depthLimitedNoMatchSamples"))
    assertFalse(j.getBoolean("cpuTimeMeasured"))
    assertEquals("wall-time stack observations", j.getString("measurement"))
  }

  @Test fun leafRankingUsesOnlyLiteralStackZeroAndCountsRecursiveObservationsOnce() {
    val h = MainThreadSampleHistogram()
    h.record(arrayOf(frame("example.Leaf", "draw", line = 1), frame("example.Leaf", "draw", line = 2),
        frame("example.CommonAncestor")))
    h.record(arrayOf(frame("example.Leaf", "draw", line = 3), frame("example.CommonAncestor")))
    val leaf = h.snapshot().getJSONObject("leafHistogram")
    assertEquals(2L, leaf.getLong("samples"))
    assertEquals(2L, leaf.getLong("admittedSamples"))
    val row = ranked(leaf).single()
    assertEquals("example.Leaf.draw", row.getString("symbol"))
    assertEquals(2L, row.getLong("samples"))
  }

  @Test fun firstLauncherRankingSelectsOnlyNearestMatchingFrameOncePerObservation() {
    val h = MainThreadSampleHistogram()
    repeat(2) {
      h.record(arrayOf(frame("example.Framework"), frame("com.immortal.launcher.Nearest", "draw", line = it),
          frame("com.immortal.launcher.Nearest", "draw", line = 30),
          frame("com.immortal.launcher.Deeper", "prepare")))
    }
    val app = h.snapshot().getJSONObject("firstAppHistogram")
    assertEquals(2L, app.getLong("observations"))
    assertEquals(2L, app.getLong("samples"))
    assertEquals(0L, app.getLong("noMatchSamples"))
    val row = ranked(app).single()
    assertEquals("com.immortal.launcher.Nearest.draw", row.getString("symbol"))
    assertEquals(2L, row.getLong("samples"))
  }

  @Test fun leafNativePollExclusionIsExactAndLauncherRankingKeepsPollAndWaitingVariants() {
    val h = MainThreadSampleHistogram()
    val appFrame = frame("com.immortal.launcher.Callback")
    val poll = frame("android.os.MessageQueue", "nativePollOnce")
    h.record(arrayOf(poll, appFrame))
    h.record(arrayOf(frame("java.lang.Object", "wait"), appFrame))
    h.record(arrayOf(frame("android.os.MessageQueue", "nativePollOnceExtra")))
    h.record(arrayOf(frame("example.Leaf"), poll, appFrame))
    val j = h.snapshot()
    assertEquals(1L, j.getLong("nativePollSamples"))
    val leaf = j.getJSONObject("leafHistogram")
    assertEquals(4L, leaf.getLong("observations"))
    assertEquals(3L, leaf.getLong("samples"))
    assertEquals(1L, leaf.getLong("excludedNativePollSamples"))
    assertEquals(2L, leaf.getLong("waitingSamples"))
    assertEquals(1L, leaf.getLong("otherSamples"))
    assertTrue(ranked(leaf).none { it.getString("symbol") == "android.os.MessageQueue.nativePollOnce" })
    assertTrue(ranked(leaf).any { it.getString("symbol") == "android.os.MessageQueue.nativePollOnceExtra" })
    val app = j.getJSONObject("firstAppHistogram")
    assertEquals(3L, app.getLong("samples"))
    assertEquals(1L, app.getLong("noMatchSamples"))
    val row = ranked(app).single()
    assertEquals(3L, row.getLong("samples"))
    assertEquals(3L, row.getLong("waitingSamples"))
    assertEquals(1L, row.getLong("nativePollSamples"))
    assertEquals(2L, row.getLong("outsideNativePollSamples"))
    assertEquals(2L, row.getLong("outsideNativePollWaitingSamples"))
    assertEquals(0L, row.getLong("outsideNativePollOtherSamples"))
  }

  @Test fun launcherPrefixRequiresExactCaseAndTrailingPackageDelimiter() {
    val h = MainThreadSampleHistogram()
    val nearMisses = arrayOf(frame("com.immortal.launcher"), frame("com.immortal.launcherElse.Home"),
        frame("Com.immortal.launcher.Home"), frame("example.com.immortal.launcher.Home"),
        frame("com.immortal.launcherish.Home"))
    h.record(nearMisses)
    h.record(nearMisses + frame("com.immortal.launcher.Home\$Nested", "invoke"))
    h.record(arrayOf(frame("com.immortal.launcher.sub.Component", "update")))
    val app = h.snapshot().getJSONObject("firstAppHistogram")
    assertEquals(1L, app.getLong("noMatchSamples"))
    assertEquals(2L, app.getLong("samples"))
    assertEquals(setOf("com.immortal.launcher.Home\$Nested.invoke", "com.immortal.launcher.sub.Component.update"),
        ranked(app).map { it.getString("symbol") }.toSet())
  }

  @Test fun firstLauncherMatchUsesDepth64AndReportsNoMatchInTruncatedObservations() {
    val h = MainThreadSampleHistogram()
    val appFrame = frame("com.immortal.launcher.Boundary")
    h.record(Array(63) { frame("example.Framework") } + appFrame)
    h.record(Array(64) { frame("example.Framework") } + appFrame)
    h.record(Array(64) { frame("example.Framework") })
    h.record(arrayOf(frame("example.Framework")))
    val app = h.snapshot().getJSONObject("firstAppHistogram")
    assertEquals(4L, app.getLong("observations"))
    assertEquals(1L, app.getLong("samples"))
    assertEquals(3L, app.getLong("noMatchSamples"))
    assertEquals(1L, app.getLong("depthLimitedNoMatchSamples"))
    assertEquals(64, app.getInt("depthLimit"))
    assertEquals("com.immortal.launcher.Boundary.work", ranked(app).single().getString("symbol"))
  }

  @Test fun leafCapRejects129thIdentityButCountsKnownLeavesAndDoesNotBlockLauncherAdmission() {
    val h = MainThreadSampleHistogram()
    repeat(128) { h.record(arrayOf(frame("example.Leaf$it"))) }
    h.record(arrayOf(frame("example.RejectedLeaf"), frame("com.immortal.launcher.StillAdmitted")))
    h.record(arrayOf(frame("example.Leaf0")))
    val j = h.snapshot()
    val leaf = j.getJSONObject("leafHistogram")
    assertEquals(128, leaf.getInt("uniqueSymbols"))
    assertTrue(leaf.getBoolean("symbolLimitReached"))
    assertEquals(130L, leaf.getLong("samples"))
    assertEquals(129L, leaf.getLong("admittedSamples"))
    assertEquals(1L, leaf.getLong("discardedAdmissions"))
    assertEquals(0L, leaf.getLong("oversizedIdentities"))
    assertEquals("example.Leaf0.work", ranked(leaf).first().getString("symbol"))
    assertEquals(2L, ranked(leaf).first().getLong("samples"))
    val app = j.getJSONObject("firstAppHistogram")
    assertEquals(1, app.getInt("uniqueSymbols"))
    assertEquals("com.immortal.launcher.StillAdmitted.work", ranked(app).single().getString("symbol"))
  }

  @Test fun launcherCapRejects97thIdentityWithoutFallbackAndDoesNotBlockLeafAdmission() {
    val h = MainThreadSampleHistogram()
    repeat(96) { h.record(arrayOf(frame("example.Framework"), frame("com.immortal.launcher.App$it"))) }
    h.record(arrayOf(frame("example.NewLeaf"), frame("com.immortal.launcher.RejectedApp"),
        frame("com.immortal.launcher.App0")))
    h.record(arrayOf(frame("example.NewLeaf"), frame("com.immortal.launcher.App0")))
    val j = h.snapshot()
    val app = j.getJSONObject("firstAppHistogram")
    assertEquals(96, app.getInt("uniqueSymbols"))
    assertTrue(app.getBoolean("symbolLimitReached"))
    assertEquals(98L, app.getLong("samples"))
    assertEquals(97L, app.getLong("admittedSamples"))
    assertEquals(1L, app.getLong("discardedAdmissions"))
    assertEquals("com.immortal.launcher.App0.work", ranked(app).first().getString("symbol"))
    assertEquals(2L, ranked(app).first().getLong("samples"))
    val leaf = j.getJSONObject("leafHistogram")
    assertEquals(2, leaf.getInt("uniqueSymbols"))
    assertEquals(0L, leaf.getLong("discardedAdmissions"))
    assertEquals(2L, ranked(leaf).single { it.getString("symbol") == "example.NewLeaf.work" }.getLong("samples"))
  }

  @Test fun inclusiveSaturationCannotSuppressIndependentLeafLauncherOrFixedFamilyCounts() {
    val h = MainThreadSampleHistogram()
    repeat(5) { sample -> h.record(arrayOf(frame("example.SharedLeaf")) +
        Array(63) { frame("example.Ancestor${sample}_$it") }) }
    h.record(arrayOf(frame("com.immortal.launcher.NewWork", "draw"),
        frame("androidx.compose.ui.spatial.RectManager", "onLayoutPositionChanged")))
    val j = h.snapshot()
    assertEquals(256, j.getInt("uniqueSymbols"))
    assertTrue(j.getLong("discardedSymbols") > 0)
    assertTrue(frames(j).none { it.getString("symbol") == "com.immortal.launcher.NewWork.draw" })
    val leaf = j.getJSONObject("leafHistogram")
    assertEquals(2, leaf.getInt("uniqueSymbols"))
    assertEquals(0L, leaf.getLong("discardedAdmissions"))
    assertEquals(1L, ranked(leaf).single { it.getString("symbol") == "com.immortal.launcher.NewWork.draw" }.getLong("samples"))
    val app = j.getJSONObject("firstAppHistogram")
    assertEquals(1L, app.getLong("admittedSamples"))
    assertEquals("com.immortal.launcher.NewWork.draw", ranked(app).single().getString("symbol"))
    assertEquals(1L, family(j, "spatialRectManager").getLong("samples"))
  }

  @Test fun leafIdentityLength512IsAdmittedAnd513IsCountedWithoutTruncationOrAncestorFallback() {
    val h = MainThreadSampleHistogram()
    h.record(arrayOf(frame("a".repeat(508), "run")))
    h.record(arrayOf(frame("a".repeat(509), "run"), frame("example.ShortAncestor")))
    val leaf = h.snapshot().getJSONObject("leafHistogram")
    assertEquals(2L, leaf.getLong("samples"))
    assertEquals(1L, leaf.getLong("admittedSamples"))
    assertEquals(1L, leaf.getLong("oversizedIdentities"))
    assertEquals(0L, leaf.getLong("discardedAdmissions"))
    assertEquals(512, ranked(leaf).single().getString("symbol").length)
    assertEquals(1L, ranked(leaf).single().getLong("samples"))
  }

  @Test fun oversizedFirstLauncherIdentityIsCountedAndNeverReplacedByLaterValidLauncherFrame() {
    val h = MainThreadSampleHistogram()
    val prefix = "com.immortal.launcher."
    h.record(arrayOf(frame(prefix + "a".repeat(508 - prefix.length), "run")))
    h.record(arrayOf(frame(prefix + "a".repeat(509 - prefix.length), "run"),
        frame("com.immortal.launcher.LaterValid")))
    val app = h.snapshot().getJSONObject("firstAppHistogram")
    assertEquals(2L, app.getLong("samples"))
    assertEquals(1L, app.getLong("admittedSamples"))
    assertEquals(1L, app.getLong("oversizedIdentities"))
    assertEquals(0L, app.getLong("noMatchSamples"))
    assertEquals(0L, app.getLong("discardedAdmissions"))
    assertEquals(512, ranked(app).single().getString("symbol").length)
    assertEquals(1L, ranked(app).single().getLong("samples"))
  }

  @Test fun separateMapsRankTheirOwnTop24BySingleSelectedIdentityCounts() {
    val h = MainThreadSampleHistogram()
    repeat(30) { h.record(arrayOf(frame("example.Leaf$it"), frame("com.immortal.launcher.App$it"))) }
    repeat(3) { h.record(arrayOf(frame("example.Leaf29"), frame("com.immortal.launcher.App29"))) }
    val j = h.snapshot()
    val leaves = ranked(j.getJSONObject("leafHistogram"))
    val apps = ranked(j.getJSONObject("firstAppHistogram"))
    assertEquals(24, leaves.size)
    assertEquals(24, apps.size)
    assertEquals("example.Leaf29.work", leaves.first().getString("symbol"))
    assertEquals("com.immortal.launcher.App29.work", apps.first().getString("symbol"))
    for (rows in listOf(leaves, apps)) {
      assertEquals(4L, rows.first().getLong("samples"))
      assertTrue(rows.drop(1).all { it.getLong("samples") == 1L })
    }
  }

  @Test fun emptyErrorCancelledAndTickLimitedResultsDoNotEnterEitherSelectedHistogram() {
    val h = MainThreadSampleHistogram()
    h.record(emptyArray())
    h.recordError()
    h.recordDroppedSample()
    repeat(5997) { h.record(arrayOf(frame("example.Framework"), frame("com.immortal.launcher.Marker"))) }
    h.record(arrayOf(frame("android.os.MessageQueue", "nativePollOnce"), frame("com.immortal.launcher.BeyondLimit")))
    val j = h.snapshot()
    assertEquals(0L, j.getLong("nativePollSamples"))
    for (name in listOf("leafHistogram", "firstAppHistogram")) {
      val histogram = j.getJSONObject(name)
      assertEquals(5997L, histogram.getLong("observations"))
      assertEquals(5997L, histogram.getLong("samples"))
      assertEquals(5997L, histogram.getLong("admittedSamples"))
      assertEquals(1, histogram.getInt("uniqueSymbols"))
      assertEquals(5997L, ranked(histogram).single().getLong("samples"))
    }
    assertEquals(0L, j.getJSONObject("firstAppHistogram").getLong("noMatchSamples"))
  }

  @Test fun selectedMapsRetainOnlySymbolsAndCountsWithNoInputStackOrElementReferences() {
    val h = MainThreadSampleHistogram()
    val stack = arrayOf(frame("example.Framework", "run", "/private/photos/household-secret.jpg", 982731),
        frame("com.immortal.launcher.Primary", "draw", "/private/photos/household-secret.jpg", 982731),
        frame("com.immortal.launcher.Deeper", "prepare"))
    h.record(stack)
    val j = h.snapshot()
    assertEquals("example.Framework.run", ranked(j.getJSONObject("leafHistogram")).single().getString("symbol"))
    assertEquals("com.immortal.launcher.Primary.draw", ranked(j.getJSONObject("firstAppHistogram")).single().getString("symbol"))
    assertNoRetainedStackInputs(h, stack)
    val before = j.toString()
    stack.fill(frame("example.Replaced"))
    assertEquals(before, h.snapshot().toString())
    assertFalse(before.contains("household-secret"))
    assertFalse(before.contains("982731"))
  }
}

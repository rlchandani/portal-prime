/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.MockedConstruction
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.`when`

/** Exercises the real text-entry path with only Android framework boundaries mocked. */
@RunWith(Parameterized::class)
@Suppress("DEPRECATION") // Portal API 28/29 requires AccessibilityNodeInfo.recycle().
class RemoteInputNodeLifecycleTest(private val focusedIsRoot: Boolean) {
  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "focusedIsRoot={0}")
    fun nodeIdentities(): List<Array<Boolean>> = listOf(arrayOf(false), arrayOf(true))
  }

  private val service = Mockito.mock(AccessibilityService::class.java)
  private val root = Mockito.mock(AccessibilityNodeInfo::class.java)
  private val focused = if (focusedIsRoot) root else Mockito.mock(AccessibilityNodeInfo::class.java)
  private lateinit var bundles: MockedConstruction<Bundle>
  private lateinit var logs: MockedStatic<Log>

  @Before
  fun registerService() {
    bundles = Mockito.mockConstruction(Bundle::class.java)
    logs = Mockito.mockStatic(Log::class.java)
    `when`(service.rootInActiveWindow).thenReturn(root)
    `when`(root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)).thenReturn(focused)
    `when`(focused.isEditable).thenReturn(true)
    `when`(focused.text).thenReturn("old")
    `when`(focused.performAction(Mockito.eq(AccessibilityNodeInfo.ACTION_SET_TEXT),
        Mockito.any(Bundle::class.java))).thenReturn(true)
    RemoteInput.register(service)
  }

  @After
  fun releaseTestBoundaries() {
    RemoteInput.unregister()
    logs.close()
    bundles.close()
  }

  @Test
  fun acceptedEditRecyclesEachNodeOnceAfterTheAction() {
    assertTrue(RemoteInput.typeText("-new", "append"))
    assertEditAndCleanup("old-new")
  }

  @Test
  fun nullCurrentTextStillAppendsToAnEmptyStringAndRecycles() {
    `when`(focused.text).thenReturn(null)

    assertTrue(RemoteInput.typeText("new", "append"))
    assertEditAndCleanup("new")
  }

  @Test
  fun rejectedActionStillRecyclesEachNodeOnce() {
    `when`(focused.performAction(Mockito.eq(AccessibilityNodeInfo.ACTION_SET_TEXT),
        Mockito.any(Bundle::class.java))).thenReturn(false)

    assertFalse(RemoteInput.typeText("new", "set"))
    assertEditAndCleanup("new")
  }

  @Test
  fun throwingActionStillReturnsFalseAndRecyclesEachNodeOnce() {
    `when`(focused.performAction(Mockito.eq(AccessibilityNodeInfo.ACTION_SET_TEXT),
        Mockito.any(Bundle::class.java))).thenThrow(IllegalStateException("Synthetic action failure"))

    assertFalse(RemoteInput.typeText("new", "set"))
    assertEditAndCleanup("new")
  }

  @Test
  fun disconnectedServiceDoesNotAcquireOrEditNodes() {
    RemoteInput.unregister()

    assertFalse(RemoteInput.typeText("new", "set"))
    Mockito.verifyNoInteractions(service, root, focused)
    assertTrue(bundles.constructed().isEmpty())
  }

  @Test
  fun missingRootDoesNotAcquireOrEditNodes() {
    `when`(service.rootInActiveWindow).thenReturn(null)

    assertFalse(RemoteInput.typeText("new", "set"))
    Mockito.verifyNoInteractions(root, focused)
    assertTrue(bundles.constructed().isEmpty())
  }

  @Test
  fun throwingRootLookupPreservesTheFailureWithoutAcquiringNodes() {
    val failure = IllegalStateException("Synthetic root lookup failure")
    `when`(service.rootInActiveWindow).thenThrow(failure)

    assertSame(failure, assertThrows(IllegalStateException::class.java) {
      RemoteInput.typeText("new", "set")
    })
    Mockito.verifyNoInteractions(root, focused)
    assertTrue(bundles.constructed().isEmpty())
  }

  @Test
  fun missingFocusRecyclesOnlyTheAcquiredRoot() {
    `when`(root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)).thenReturn(null)

    assertFalse(RemoteInput.typeText("new", "set"))
    assertOnlyRootRecycled()
    assertNoTextOrAction()
  }

  @Test
  fun noneditableFocusRecyclesEachAcquiredNodeWithoutReadingText() {
    `when`(focused.isEditable).thenReturn(false)

    assertFalse(RemoteInput.typeText("new", "set"))
    assertEachNodeRecycledOnce()
    assertNoTextOrAction()
  }

  @Test
  fun throwingFocusLookupRecyclesOnlyTheRootAndPreservesTheFailure() {
    val failure = IllegalStateException("Synthetic focus lookup failure")
    `when`(root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)).thenThrow(failure)

    assertSame(failure, assertThrows(IllegalStateException::class.java) {
      RemoteInput.typeText("new", "set")
    })
    assertOnlyRootRecycled()
    assertNoTextOrAction()
  }

  @Test
  fun throwingEditableCheckRecyclesBothNodesAndPreservesTheFailure() {
    val failure = IllegalStateException("Synthetic editable check failure")
    `when`(focused.isEditable).thenThrow(failure)

    assertSame(failure, assertThrows(IllegalStateException::class.java) {
      RemoteInput.typeText("new", "set")
    })
    assertEachNodeRecycledOnce()
    assertNoTextOrAction()
  }

  @Test
  fun throwingTextReadRecyclesBothNodesAndPreservesTheFailure() {
    val failure = IllegalStateException("Synthetic text read failure")
    `when`(focused.text).thenThrow(failure)

    assertSame(failure, assertThrows(IllegalStateException::class.java) {
      RemoteInput.typeText("new", "set")
    })
    assertEachNodeRecycledOnce()
    assertNoAction()
  }

  @Test
  fun throwingTextConversionRecyclesBothNodesAndPreservesTheFailure() {
    val text = Mockito.mock(CharSequence::class.java)
    val failure = IllegalStateException("Synthetic text conversion failure")
    `when`(text.toString()).thenThrow(failure)
    `when`(focused.text).thenReturn(text)

    assertSame(failure, assertThrows(IllegalStateException::class.java) {
      RemoteInput.typeText("new", "set")
    })
    assertEachNodeRecycledOnce()
    assertNoAction()
  }

  @Test
  fun throwingFocusedRecycleStillRecyclesTheDistinctRootExactlyOnce() {
    val failure = IllegalStateException("Synthetic recycling failure")
    Mockito.doThrow(failure).`when`(focused).recycle()

    assertSame(failure, assertThrows(IllegalStateException::class.java) {
      RemoteInput.typeText("new", "set")
    })
    assertEditAndCleanup("new")
  }

  private fun assertEditAndCleanup(expectedText: String) {
    val args = bundles.constructed().single()
    Mockito.verify(args).putCharSequence(
        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, expectedText)
    // The root may alias the focused object; neither can be recycled before the edit finishes.
    val order = if (focusedIsRoot) Mockito.inOrder(root) else Mockito.inOrder(root, focused)
    order.verify(focused).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    order.verify(focused).recycle()
    if (!focusedIsRoot) order.verify(root).recycle()
    assertEachNodeRecycledOnce()
  }

  private fun assertEachNodeRecycledOnce() {
    Mockito.verify(root).findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    Mockito.verify(root).recycle()
    if (!focusedIsRoot) Mockito.verify(focused).recycle()
  }

  private fun assertOnlyRootRecycled() {
    Mockito.verify(root).findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    Mockito.verify(root).recycle()
    if (!focusedIsRoot) Mockito.verifyNoInteractions(focused)
  }

  private fun assertNoTextOrAction() {
    Mockito.verify(focused, Mockito.never()).text
    assertNoAction()
  }

  private fun assertNoAction() {
    Mockito.verify(focused, Mockito.never()).performAction(
        Mockito.anyInt(), Mockito.any(Bundle::class.java))
    assertTrue(bundles.constructed().isEmpty())
  }
}

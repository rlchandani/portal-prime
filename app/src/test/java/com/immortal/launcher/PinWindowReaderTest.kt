/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`

/** Exercises the reader with only Android's window acquisition and pooled objects mocked. */
@Suppress("DEPRECATION") // Portal API 29 pools windows and nodes.
class PinWindowReaderTest {
  @Test fun includesEveryApplicationWindowEvenWhenUnfocusedOrOwnedByImmortal() {
    val own = app("com.immortal.launcher", 0, focused = true, active = true)
    val underneath = app("com.synthetic.browser", 42)
    val active = app("com.synthetic.player", 43, active = true)

    assertEquals(
        listOf(
            PinAppWindow("com.immortal.launcher", 0, true, true),
            PinAppWindow("com.synthetic.browser", 42, false, false),
            PinAppWindow("com.synthetic.player", 43, false, true),
        ),
        readPinAppWindows(service(own.window, underneath.window, active.window)),
    )
    listOf(own, underneath, active).forEach(::assertMetadataOnlyAndReleased)
  }

  @Test fun systemAndAccessibilityOverlayWindowsNeverAcquireRoots() {
    val system = window(AccessibilityWindowInfo.TYPE_SYSTEM)
    val overlay = window(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)
    `when`(system.isFocused).thenReturn(true)
    `when`(overlay.isActive).thenReturn(true)

    assertEquals(emptyList<PinAppWindow>(), readPinAppWindows(service(system, overlay)))

    listOf(system, overlay).forEach {
      Mockito.verify(it).type
      Mockito.verify(it).recycle()
      Mockito.verifyNoMoreInteractions(it)
    }
  }

  @Test fun singleSelectionPrefersFocusedOverEarlierActiveAndReleasesAllAcquiredObjects() {
    val active = app("com.synthetic.active", 11, active = true)
    val focused = app("com.synthetic.focused", 12, focused = true)
    val underneath = app("com.synthetic.underneath", 13)

    assertEquals(
        PinAppWindow("com.synthetic.focused", 12, true, false),
        readPinAppWindow(service(active.window, focused.window, underneath.window)),
    )
    listOf(active, focused, underneath).forEach(::assertMetadataOnlyAndReleased)
  }

  @Test fun singleSelectionUsesFirstFocusedInWindowOrder() {
    val first = app("com.synthetic.first", 21, focused = true)
    val second = app("com.synthetic.second", 22, focused = true, active = true)

    assertEquals(
        PinAppWindow("com.synthetic.first", 21, true, false),
        readPinAppWindow(service(first.window, second.window)),
    )
    listOf(first, second).forEach(::assertMetadataOnlyAndReleased)
  }

  @Test fun singleSelectionFallsBackToFirstActiveInWindowOrder() {
    val underneath = app("com.synthetic.underneath", 30)
    val first = app("com.synthetic.first", 31, active = true)
    val second = app("com.synthetic.second", 32, active = true)

    assertEquals(
        PinAppWindow("com.synthetic.first", 31, false, true),
        readPinAppWindow(service(underneath.window, first.window, second.window)),
    )
    listOf(underneath, first, second).forEach(::assertMetadataOnlyAndReleased)
  }

  @Test fun singleSelectionReturnsNullWhenAllApplicationWindowsAreUnfocusedAndInactive() {
    val underneath = app("com.synthetic.underneath", 40)

    assertNull(readPinAppWindow(service(underneath.window)))

    assertMetadataOnlyAndReleased(underneath)
  }

  @Test fun emptyWindowSnapshotReturnsNoMetadata() {
    assertEquals(emptyList<PinAppWindow>(), readPinAppWindows(service()))
    assertNull(readPinAppWindow(service()))
  }

  @Test fun nullWindowSnapshotReturnsNoMetadata() {
    val readerService = Mockito.mock(AccessibilityService::class.java)
    `when`(readerService.windows).thenReturn(null)

    assertEquals(emptyList<PinAppWindow>(), readPinAppWindows(readerService))
    Mockito.verify(readerService).windows
    Mockito.verifyNoMoreInteractions(readerService)
  }

  @Test fun throwingWindowAcquisitionReturnsNoMetadataWithoutAnotherServiceLookup() {
    val readerService = Mockito.mock(AccessibilityService::class.java)
    `when`(readerService.windows).thenThrow(IllegalStateException("Stale service"))

    assertNull(readPinAppWindow(readerService))

    Mockito.verify(readerService).windows
    Mockito.verifyNoMoreInteractions(readerService)
  }

  @Test fun missingRootSkipsThatWindowAndReleasesEveryWindow() {
    val missing = window(AccessibilityWindowInfo.TYPE_APPLICATION)
    val healthy = app("com.synthetic.healthy", 51, focused = true)

    assertEquals(
        listOf(PinAppWindow("com.synthetic.healthy", 51, true, false)),
        readPinAppWindows(service(missing, healthy.window)),
    )
    Mockito.verify(missing).type
    Mockito.verify(missing).root
    Mockito.verify(missing).recycle()
    Mockito.verifyNoMoreInteractions(missing)
    assertMetadataOnlyAndReleased(healthy)
  }

  @Test fun staleWindowTypeDoesNotPreventReadingOrRecyclingLaterWindows() {
    val stale = window(AccessibilityWindowInfo.TYPE_APPLICATION)
    `when`(stale.type).thenThrow(IllegalStateException("Stale type"))
    val healthy = app("com.synthetic.healthy", 61)

    assertEquals(
        listOf(PinAppWindow("com.synthetic.healthy", 61, false, false)),
        readPinAppWindows(service(stale, healthy.window)),
    )
    Mockito.verify(stale).type
    Mockito.verify(stale).recycle()
    Mockito.verifyNoMoreInteractions(stale)
    assertMetadataOnlyAndReleased(healthy)
  }

  @Test fun staleRootLookupDoesNotPreventReadingOrRecyclingLaterWindows() {
    val stale = window(AccessibilityWindowInfo.TYPE_APPLICATION)
    `when`(stale.root).thenThrow(IllegalStateException("Stale root"))
    val healthy = app("com.synthetic.healthy", 71)

    assertEquals(
        listOf(PinAppWindow("com.synthetic.healthy", 71, false, false)),
        readPinAppWindows(service(stale, healthy.window)),
    )
    Mockito.verify(stale).type
    Mockito.verify(stale).root
    Mockito.verify(stale).recycle()
    Mockito.verifyNoMoreInteractions(stale)
    assertMetadataOnlyAndReleased(healthy)
  }

  @Test fun nullOrBlankPackageSkipsMetadataButStillReleasesTheRoot() {
    for (packageName in listOf(null, "", " ", "\n")) {
      val invalid = app(packageName, 80, focused = true)
      val healthy = app("com.synthetic.healthy", 81, active = true)

      assertEquals(
          listOf(PinAppWindow("com.synthetic.healthy", 81, false, true)),
          readPinAppWindows(service(invalid.window, healthy.window)),
      )
      Mockito.verify(invalid.root).packageName
      Mockito.verify(invalid.root).recycle()
      Mockito.verifyNoMoreInteractions(invalid.root)
      Mockito.verify(invalid.window).recycle()
      assertMetadataOnlyAndReleased(healthy)
    }
  }

  @Test fun undefinedWindowIdCannotBecomeAPinTarget() {
    val invalid = app("com.synthetic.invalid", -1, focused = true)

    assertNull(readPinAppWindow(service(invalid.window)))

    Mockito.verify(invalid.window).recycle()
    Mockito.verify(invalid.root).packageName
    Mockito.verify(invalid.root).recycle()
    Mockito.verifyNoMoreInteractions(invalid.root)
  }

  @Test fun staleMetadataAtEachReadReleasesTheRootAndPreservesOtherWindows() {
    for (field in listOf("package", "conversion", "id", "focused", "active")) {
      val stale = app("com.synthetic.stale", 90, focused = true)
      val failure = IllegalStateException("Stale $field")
      when (field) {
        "package" -> `when`(stale.root.packageName).thenThrow(failure)
        "conversion" -> {
          val packageName = Mockito.mock(CharSequence::class.java)
          `when`(packageName.toString()).thenThrow(failure)
          `when`(stale.root.packageName).thenReturn(packageName)
        }
        "id" -> `when`(stale.window.id).thenThrow(failure)
        "focused" -> `when`(stale.window.isFocused).thenThrow(failure)
        "active" -> `when`(stale.window.isActive).thenThrow(failure)
      }
      val healthy = app("com.synthetic.healthy", 91, active = true)

      assertEquals(
          "field=$field",
          listOf(PinAppWindow("com.synthetic.healthy", 91, false, true)),
          readPinAppWindows(service(stale.window, healthy.window)),
      )
      Mockito.verify(stale.window).recycle()
      Mockito.verify(stale.root).packageName
      Mockito.verify(stale.root).recycle()
      Mockito.verifyNoMoreInteractions(stale.root)
      assertMetadataOnlyAndReleased(healthy)
    }
  }

  @Test fun packageIsCopiedBeforeThePooledRootIsReleased() {
    val packageName = StringBuilder("com.synthetic.original")
    val snapshot = app(packageName, 101)
    Mockito.doAnswer { packageName.clear(); null }.`when`(snapshot.root).recycle()

    assertEquals(
        listOf(PinAppWindow("com.synthetic.original", 101, false, false)),
        readPinAppWindows(service(snapshot.window)),
    )
    assertMetadataOnlyAndReleased(snapshot)
  }

  @Test fun throwingRootRecycleDoesNotLeakWindowsOrOtherRoots() {
    val stale = app("com.synthetic.stale", 110)
    Mockito.doThrow(IllegalStateException("Stale pooled root")).`when`(stale.root).recycle()
    val healthy = app("com.synthetic.healthy", 111)

    assertEquals(
        listOf(
            PinAppWindow("com.synthetic.stale", 110, false, false),
            PinAppWindow("com.synthetic.healthy", 111, false, false),
        ),
        readPinAppWindows(service(stale.window, healthy.window)),
    )

    listOf(stale, healthy).forEach(::assertMetadataOnlyAndReleased)
  }

  @Test fun throwingWindowRecycleDoesNotPreventReleasingLaterWindows() {
    val stale = app("com.synthetic.stale", 120)
    Mockito.doThrow(IllegalStateException("Stale pooled window")).`when`(stale.window).recycle()
    val healthy = app("com.synthetic.healthy", 121)
    val overlay = window(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)

    assertEquals(
        listOf(
            PinAppWindow("com.synthetic.stale", 120, false, false),
            PinAppWindow("com.synthetic.healthy", 121, false, false),
        ),
        readPinAppWindows(service(stale.window, healthy.window, overlay)),
    )

    listOf(stale, healthy).forEach(::assertMetadataOnlyAndReleased)
    Mockito.verify(overlay).recycle()
  }

  @Test fun rootsAreReleasedBeforeTheirOwningWindows() {
    val first = app("com.synthetic.first", 130)
    val second = app("com.synthetic.second", 131)

    readPinAppWindows(service(first.window, second.window))

    for (snapshot in listOf(first, second)) {
      val order = Mockito.inOrder(snapshot.root, snapshot.window)
      order.verify(snapshot.root).packageName
      order.verify(snapshot.root).recycle()
      order.verify(snapshot.window).recycle()
      assertMetadataOnlyAndReleased(snapshot)
    }
  }

  private data class AppSnapshot(
      val window: AccessibilityWindowInfo,
      val root: AccessibilityNodeInfo,
  )

  private fun app(
      packageName: CharSequence?,
      id: Int,
      focused: Boolean = false,
      active: Boolean = false,
  ): AppSnapshot {
    val appWindow = window(AccessibilityWindowInfo.TYPE_APPLICATION)
    val root = Mockito.mock(AccessibilityNodeInfo::class.java)
    `when`(appWindow.root).thenReturn(root)
    `when`(appWindow.id).thenReturn(id)
    `when`(appWindow.isFocused).thenReturn(focused)
    `when`(appWindow.isActive).thenReturn(active)
    `when`(root.packageName).thenReturn(packageName)
    return AppSnapshot(appWindow, root)
  }

  private fun window(type: Int): AccessibilityWindowInfo =
      Mockito.mock(AccessibilityWindowInfo::class.java).also { `when`(it.type).thenReturn(type) }

  private fun service(vararg windows: AccessibilityWindowInfo): AccessibilityService =
      Mockito.mock(AccessibilityService::class.java).also {
        `when`(it.windows).thenReturn(windows.toList())
      }

  private fun assertMetadataOnlyAndReleased(snapshot: AppSnapshot) {
    Mockito.verify(snapshot.root).packageName
    Mockito.verify(snapshot.root).recycle()
    // Any text, children, content, actions, or retained-node lookup fails this contract.
    Mockito.verifyNoMoreInteractions(snapshot.root)
    Mockito.verify(snapshot.window).type
    Mockito.verify(snapshot.window).root
    Mockito.verify(snapshot.window).id
    Mockito.verify(snapshot.window).isFocused
    Mockito.verify(snapshot.window).isActive
    Mockito.verify(snapshot.window).recycle()
    Mockito.verifyNoMoreInteractions(snapshot.window)
  }
}

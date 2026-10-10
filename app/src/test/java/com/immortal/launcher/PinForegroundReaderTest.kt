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

/** Foreground selection must avoid expensive root lookups for unselected windows. */
@Suppress("DEPRECATION") // Portal API 29 pools windows and nodes.
class PinForegroundReaderTest {
  @Test fun laterFocusCostsOneRootLookupRegardlessOfEarlierActiveOrBackgroundWindows() {
    val active = app("com.synthetic.active", 1, active = true)
    val background = (2..17).map { app("com.synthetic.background$it", it) }
    val system = window(AccessibilityWindowInfo.TYPE_SYSTEM)
    val overlay = window(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)
    val inputMethod = window(AccessibilityWindowInfo.TYPE_INPUT_METHOD)
    for (nonApp in listOf(system, overlay, inputMethod)) {
      `when`(nonApp.isFocused).thenReturn(true)
      `when`(nonApp.isActive).thenReturn(true)
      `when`(nonApp.root).thenThrow(IllegalStateException("Non-app root must not be read"))
    }
    val selected = app("com.synthetic.focused", 18, focused = true, active = true)
    val later = app("com.synthetic.later", 19, focused = true, active = true)
    val readerService = service(
        system, active.window, overlay, *background.map { it.window }.toTypedArray(),
        inputMethod, selected.window, later.window,
    )

    assertEquals(
        PinAppWindow("com.synthetic.focused", 18, true, true),
        readPinAppWindow(readerService),
    )

    assertReadOnceAndReleased(selected)
    (listOf(active, later) + background).forEach(::assertSkippedAndReleased)
    listOf(system, overlay, inputMethod).forEach {
      Mockito.verify(it, Mockito.never()).root
      Mockito.verify(it).recycle()
    }
    Mockito.verify(readerService).windows
    Mockito.verifyNoMoreInteractions(readerService)
  }

  @Test fun missingFocusedRootTriesNextFocusBeforeEarlierActiveAndStopsAtSuccess() {
    val active = app("com.synthetic.active", 20, active = true)
    val missing = app("com.synthetic.missing", 21, focused = true)
    `when`(missing.window.root).thenReturn(null)
    val selected = app("com.synthetic.focused", 22, focused = true)
    val later = app("com.synthetic.later", 23, focused = true)

    assertEquals(
        PinAppWindow("com.synthetic.focused", 22, true, false),
        readPinAppWindow(service(active.window, missing.window, selected.window, later.window)),
    )

    val order = Mockito.inOrder(missing.window, selected.window)
    order.verify(missing.window).root
    order.verify(selected.window).root
    assertMissingRootAndReleased(missing)
    assertReadOnceAndReleased(selected)
    listOf(active, later).forEach(::assertSkippedAndReleased)
  }

  @Test fun nullOrBlankFocusedPackagesTryNextFocusWithoutRootingEarlierActive() {
    for (packageName in listOf(null, "", " ", "\n")) {
      val active = app("com.synthetic.active", 30, active = true)
      val invalid = app(packageName, 31, focused = true)
      val selected = app("com.synthetic.focused", 32, focused = true)
      val later = app("com.synthetic.later", 33, focused = true)

      assertEquals(
          "package=$packageName",
          PinAppWindow("com.synthetic.focused", 32, true, false),
          readPinAppWindow(service(active.window, invalid.window, selected.window, later.window)),
      )

      val order = Mockito.inOrder(invalid.window, selected.window)
      order.verify(invalid.window).root
      order.verify(selected.window).root
      listOf(invalid, selected).forEach(::assertReadOnceAndReleased)
      listOf(active, later).forEach(::assertSkippedAndReleased)
    }
  }

  @Test fun staleFocusedRootTriesNextFocusWithoutRootingEarlierActive() {
    val active = app("com.synthetic.active", 40, active = true)
    val stale = app("com.synthetic.stale", 41, focused = true)
    `when`(stale.window.root).thenThrow(IllegalStateException("Stale root"))
    val selected = app("com.synthetic.focused", 42, focused = true)
    val later = app("com.synthetic.later", 43, focused = true)

    assertEquals(
        PinAppWindow("com.synthetic.focused", 42, true, false),
        readPinAppWindow(service(active.window, stale.window, selected.window, later.window)),
    )

    val order = Mockito.inOrder(stale.window, selected.window)
    order.verify(stale.window).root
    order.verify(selected.window).root
    assertMissingRootAndReleased(stale)
    assertReadOnceAndReleased(selected)
    listOf(active, later).forEach(::assertSkippedAndReleased)
  }

  @Test fun staleFocusedPackageOrConversionTriesNextFocusAndReleasesFailedRoot() {
    for (field in listOf("package", "conversion")) {
      val active = app("com.synthetic.active", 50, active = true)
      val stale = app("com.synthetic.stale", 51, focused = true)
      val failure = IllegalStateException("Stale $field")
      if (field == "package") {
        `when`(stale.root.packageName).thenThrow(failure)
      } else {
        val packageName = Mockito.mock(CharSequence::class.java)
        `when`(packageName.toString()).thenThrow(failure)
        `when`(stale.root.packageName).thenReturn(packageName)
      }
      val selected = app("com.synthetic.focused", 52, focused = true)
      val later = app("com.synthetic.later", 53, focused = true)

      assertEquals(
          "field=$field",
          PinAppWindow("com.synthetic.focused", 52, true, false),
          readPinAppWindow(service(active.window, stale.window, selected.window, later.window)),
      )

      val order = Mockito.inOrder(stale.window, selected.window)
      order.verify(stale.window).root
      order.verify(selected.window).root
      listOf(stale, selected).forEach(::assertReadOnceAndReleased)
      listOf(active, later).forEach(::assertSkippedAndReleased)
    }
  }

  @Test fun activeFallbackStartsOnlyAfterEveryFocusedRootFailed() {
    val firstActive = app("", 60, active = true)
    val selected = app("com.synthetic.active", 61, active = true)
    val missingFocus = app("com.synthetic.missing", 62, focused = true)
    `when`(missingFocus.window.root).thenReturn(null)
    val invalidFocus = app(" ", 63, focused = true)
    val laterActive = app("com.synthetic.later", 64, active = true)

    assertEquals(
        PinAppWindow("com.synthetic.active", 61, false, true),
        readPinAppWindow(service(
            firstActive.window, selected.window, missingFocus.window,
            invalidFocus.window, laterActive.window,
        )),
    )

    // Root order is part of the contract: even an invalid earlier active waits for focus.
    val order = Mockito.inOrder(
        firstActive.window, selected.window, missingFocus.window, invalidFocus.window,
    )
    order.verify(missingFocus.window).root
    order.verify(invalidFocus.window).root
    order.verify(firstActive.window).root
    order.verify(selected.window).root
    assertMissingRootAndReleased(missingFocus)
    listOf(firstActive, invalidFocus, selected).forEach(::assertReadOnceAndReleased)
    assertSkippedAndReleased(laterActive)
  }

  @Test fun failedFocusedAndActiveCandidateIsNeverRetriedDuringActiveFallback() {
    for (failure in listOf(
        "missing root", "stale root", "null package", "blank package",
        "stale package", "conversion",
    )) {
      val failed = app("com.synthetic.failed", 70, focused = true, active = true)
      val retryRoot = Mockito.mock(AccessibilityNodeInfo::class.java)
      `when`(retryRoot.packageName).thenReturn("com.synthetic.retry")
      when (failure) {
        "null package" -> `when`(failed.root.packageName).thenReturn(null)
        "blank package" -> `when`(failed.root.packageName).thenReturn(" ")
        "stale package" -> `when`(failed.root.packageName)
            .thenThrow(IllegalStateException("Stale package"))
        "conversion" -> {
          val packageName = Mockito.mock(CharSequence::class.java)
          `when`(packageName.toString()).thenThrow(IllegalStateException("Stale conversion"))
          `when`(failed.root.packageName).thenReturn(packageName)
        }
      }
      // A retry would return a valid root and wrongly select this failed focused candidate.
      var rootReads = 0
      Mockito.doAnswer {
        rootReads++
        if (rootReads > 1) {
          retryRoot
        } else {
          when (failure) {
            "missing root" -> null
            "stale root" -> throw IllegalStateException("Stale root")
            else -> failed.root
          }
        }
      }.`when`(failed.window).root
      val selected = app("com.synthetic.active", 71, active = true)
      val later = app("com.synthetic.later", 72, active = true)

      assertEquals(
          "failure=$failure",
          PinAppWindow("com.synthetic.active", 71, false, true),
          readPinAppWindow(service(failed.window, selected.window, later.window)),
      )

      assertEquals("failure=$failure root reads", 1, rootReads)
      if (failure == "missing root" || failure == "stale root") {
        assertMissingRootAndReleased(failed)
      } else {
        assertReadOnceAndReleased(failed)
      }
      Mockito.verifyNoInteractions(retryRoot)
      assertReadOnceAndReleased(selected)
      assertSkippedAndReleased(later)
    }
  }

  @Test fun failedActiveRootsContinueInWindowOrderAndStopAtFirstUsableActive() {
    val missing = app("com.synthetic.missing", 80, active = true)
    `when`(missing.window.root).thenReturn(null)
    val stale = app("com.synthetic.stale", 81, active = true)
    `when`(stale.window.root).thenThrow(IllegalStateException("Stale active root"))
    val invalid = app(null, 82, active = true)
    val selected = app("com.synthetic.active", 83, active = true)
    val later = app("com.synthetic.later", 84, active = true)
    val background = app("com.synthetic.background", 85)

    assertEquals(
        PinAppWindow("com.synthetic.active", 83, false, true),
        readPinAppWindow(service(
            missing.window, stale.window, invalid.window, selected.window,
            later.window, background.window,
        )),
    )

    val order = Mockito.inOrder(missing.window, stale.window, invalid.window, selected.window)
    order.verify(missing.window).root
    order.verify(stale.window).root
    order.verify(invalid.window).root
    order.verify(selected.window).root
    listOf(missing, stale).forEach(::assertMissingRootAndReleased)
    listOf(invalid, selected).forEach(::assertReadOnceAndReleased)
    listOf(later, background).forEach(::assertSkippedAndReleased)
  }

  @Test fun staleWindowMetadataNeverAcquiresThatRootAndStillFindsLaterFocus() {
    for (field in listOf("type", "id", "focused", "active")) {
      val earlierActive = app("com.synthetic.active", 90, active = true)
      val stale = app("com.synthetic.stale", 91, focused = true, active = true)
      val failure = IllegalStateException("Stale $field")
      when (field) {
        "type" -> `when`(stale.window.type).thenThrow(failure)
        "id" -> `when`(stale.window.id).thenThrow(failure)
        "focused" -> `when`(stale.window.isFocused).thenThrow(failure)
        "active" -> `when`(stale.window.isActive).thenThrow(failure)
      }
      val selected = app("com.synthetic.focused", 92, focused = true)
      val later = app("com.synthetic.later", 93, focused = true)

      assertEquals(
          "field=$field",
          PinAppWindow("com.synthetic.focused", 92, true, false),
          readPinAppWindow(service(
              earlierActive.window, stale.window, selected.window, later.window,
          )),
      )

      assertReadOnceAndReleased(selected)
      listOf(earlierActive, stale, later).forEach(::assertSkippedAndReleased)
    }
  }

  @Test fun staleFocusedMetadataStillAllowsActiveFallbackWithoutRootingStaleWindow() {
    for (field in listOf("type", "id", "focused", "active")) {
      val selected = app("com.synthetic.active", 100, active = true)
      val stale = app("com.synthetic.stale", 101, focused = true, active = true)
      val failure = IllegalStateException("Stale $field")
      when (field) {
        "type" -> `when`(stale.window.type).thenThrow(failure)
        "id" -> `when`(stale.window.id).thenThrow(failure)
        "focused" -> `when`(stale.window.isFocused).thenThrow(failure)
        "active" -> `when`(stale.window.isActive).thenThrow(failure)
      }
      val later = app("com.synthetic.later", 102, active = true)

      assertEquals(
          "field=$field",
          PinAppWindow("com.synthetic.active", 100, false, true),
          readPinAppWindow(service(selected.window, stale.window, later.window)),
      )

      assertReadOnceAndReleased(selected)
      listOf(stale, later).forEach(::assertSkippedAndReleased)
    }
  }

  @Test fun negativeWindowIdsAreRejectedBeforeRootLookupForFocusAndActive() {
    for (id in listOf(-1, Int.MIN_VALUE)) {
      val focused = app("com.synthetic.focused", id, focused = true, active = true)
      val active = app("com.synthetic.active", id, active = true)
      val selected = app("com.synthetic.valid", 0, active = true)

      assertEquals(
          "id=$id",
          PinAppWindow("com.synthetic.valid", 0, false, true),
          readPinAppWindow(service(focused.window, active.window, selected.window)),
      )

      listOf(focused, active).forEach(::assertSkippedAndReleased)
      assertReadOnceAndReleased(selected)
    }
  }

  @Test fun noUsableFocusOrActiveReturnsNullWithoutTouchingBackgroundRoot() {
    val focused = app(null, 110, focused = true, active = true)
    val active = app("com.synthetic.active", 111, active = true)
    `when`(active.window.root).thenReturn(null)
    val background = app("com.synthetic.background", 112)
    `when`(background.window.root).thenThrow(IllegalStateException("Background root"))
    val overlay = window(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)
    `when`(overlay.isFocused).thenReturn(true)
    `when`(overlay.isActive).thenReturn(true)
    `when`(overlay.root).thenThrow(IllegalStateException("Overlay root"))

    assertNull(readPinAppWindow(service(
        focused.window, active.window, background.window, overlay,
    )))

    assertReadOnceAndReleased(focused)
    assertMissingRootAndReleased(active)
    assertSkippedAndReleased(background)
    Mockito.verify(overlay, Mockito.never()).root
    Mockito.verify(overlay).recycle()
  }

  @Test fun throwingSelectedRootRecycleStillReturnsSelectionAndReleasesUnusedWindows() {
    val selected = app("com.synthetic.focused", 120, focused = true)
    Mockito.doThrow(IllegalStateException("Stale root recycle")).`when`(selected.root).recycle()
    val active = app("com.synthetic.active", 121, active = true)
    val later = app("com.synthetic.later", 122, focused = true)

    assertEquals(
        PinAppWindow("com.synthetic.focused", 120, true, false),
        readPinAppWindow(service(active.window, selected.window, later.window)),
    )

    assertReadOnceAndReleased(selected)
    listOf(active, later).forEach(::assertSkippedAndReleased)
  }

  @Test fun simultaneousPackageAndRootRecycleFailuresStillAllowActiveFallback() {
    val stale = app("com.synthetic.stale", 130, focused = true, active = true)
    `when`(stale.root.packageName).thenThrow(IllegalStateException("Stale package"))
    Mockito.doThrow(IllegalStateException("Stale root recycle")).`when`(stale.root).recycle()
    Mockito.doThrow(IllegalStateException("Stale window recycle")).`when`(stale.window).recycle()
    val selected = app("com.synthetic.active", 131, active = true)
    val later = app("com.synthetic.later", 132, active = true)

    assertEquals(
        PinAppWindow("com.synthetic.active", 131, false, true),
        readPinAppWindow(service(stale.window, selected.window, later.window)),
    )

    listOf(stale, selected).forEach(::assertReadOnceAndReleased)
    assertSkippedAndReleased(later)
  }

  @Test fun throwingWindowRecyclesNeverPreventReleasingTheEntireAcquiredList() {
    val earlier = app("com.synthetic.earlier", 140, active = true)
    val selected = app("com.synthetic.focused", 141, focused = true)
    val later = app("com.synthetic.later", 142, focused = true)
    val overlay = window(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)
    for (owner in listOf(earlier.window, selected.window, later.window)) {
      Mockito.doThrow(IllegalStateException("Stale window recycle")).`when`(owner).recycle()
    }

    assertEquals(
        PinAppWindow("com.synthetic.focused", 141, true, false),
        readPinAppWindow(service(earlier.window, selected.window, later.window, overlay)),
    )

    assertReadOnceAndReleased(selected)
    listOf(earlier, later).forEach(::assertSkippedAndReleased)
    Mockito.verify(overlay, Mockito.never()).root
    Mockito.verify(overlay).recycle()
  }

  @Test fun laterSkippedMetadataAndRootFailuresStillReleaseEveryAcquiredWindow() {
    val selected = app("com.synthetic.focused", 150, focused = true)
    val stale = app("com.synthetic.stale", 151, focused = true, active = true)
    `when`(stale.window.type).thenThrow(IllegalStateException("Later stale type"))
    `when`(stale.window.root).thenThrow(IllegalStateException("Later stale root"))
    Mockito.doThrow(IllegalStateException("Later stale recycle")).`when`(stale.window).recycle()
    val later = app("com.synthetic.later", 152, focused = true, active = true)
    `when`(later.window.root).thenThrow(IllegalStateException("Later root must not be read"))
    val background = app("com.synthetic.background", 153)

    assertEquals(
        PinAppWindow("com.synthetic.focused", 150, true, false),
        readPinAppWindow(service(selected.window, stale.window, later.window, background.window)),
    )

    assertReadOnceAndReleased(selected)
    listOf(stale, later, background).forEach(::assertSkippedAndReleased)
  }

  @Test fun packageCopyAndRootReleasePrecedeOwningWindowRelease() {
    val packageName = StringBuilder("com.synthetic.original")
    val selected = app(packageName, 160, focused = true, active = true)
    val later = app("com.synthetic.later", 161, focused = true)
    Mockito.doAnswer { packageName.clear(); null }.`when`(selected.root).recycle()

    assertEquals(
        PinAppWindow("com.synthetic.original", 160, true, true),
        readPinAppWindow(service(selected.window, later.window)),
    )

    val order = Mockito.inOrder(selected.root, selected.window)
    order.verify(selected.root).packageName
    order.verify(selected.root).recycle()
    order.verify(selected.window).recycle()
    assertReadOnceAndReleased(selected)
    assertSkippedAndReleased(later)
  }

  @Test fun foregroundSelectionDoesNotFilterImmortalsOwnPackage() {
    val own = app("com.immortal.launcher", 0, focused = true)
    val later = app("com.synthetic.later", 170, focused = true, active = true)

    assertEquals(
        PinAppWindow("com.immortal.launcher", 0, true, false),
        readPinAppWindow(service(own.window, later.window)),
    )

    assertReadOnceAndReleased(own)
    assertSkippedAndReleased(later)
  }

  @Test fun eachReadUsesFreshWindowAndRootMetadataEvenWhenWindowIdIsUnchanged() {
    val first = app("com.synthetic.original", 180, focused = true)
    val firstBackground = app("com.synthetic.firstbackground", 181)
    val second = app("com.synthetic.replacement", 180, active = true)
    val secondBackground = app("com.synthetic.secondbackground", 182)
    val readerService = Mockito.mock(AccessibilityService::class.java)
    `when`(readerService.windows).thenReturn(
        listOf(first.window, firstBackground.window),
        listOf(secondBackground.window, second.window),
        emptyList(),
    )

    assertEquals(
        PinAppWindow("com.synthetic.original", 180, true, false),
        readPinAppWindow(readerService),
    )
    assertEquals(
        PinAppWindow("com.synthetic.replacement", 180, false, true),
        readPinAppWindow(readerService),
    )
    assertNull(readPinAppWindow(readerService))

    Mockito.verify(readerService, Mockito.times(3)).windows
    Mockito.verifyNoMoreInteractions(readerService)
    listOf(first, second).forEach(::assertReadOnceAndReleased)
    listOf(firstBackground, secondBackground).forEach(::assertSkippedAndReleased)
  }

  @Test fun nullWindowSnapshotReturnsNullWithoutOtherServiceLookups() {
    val readerService = Mockito.mock(AccessibilityService::class.java)
    `when`(readerService.windows).thenReturn(null)

    assertNull(readPinAppWindow(readerService))

    Mockito.verify(readerService).windows
    Mockito.verifyNoMoreInteractions(readerService)
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
    `when`(appWindow.id).thenReturn(id)
    `when`(appWindow.isFocused).thenReturn(focused)
    `when`(appWindow.isActive).thenReturn(active)
    `when`(appWindow.root).thenReturn(root)
    `when`(root.packageName).thenReturn(packageName)
    return AppSnapshot(appWindow, root)
  }

  private fun window(type: Int): AccessibilityWindowInfo =
      Mockito.mock(AccessibilityWindowInfo::class.java).also { `when`(it.type).thenReturn(type) }

  private fun service(vararg windows: AccessibilityWindowInfo): AccessibilityService =
      Mockito.mock(AccessibilityService::class.java).also {
        `when`(it.windows).thenReturn(windows.toList())
      }

  private fun assertReadOnceAndReleased(snapshot: AppSnapshot) {
    Mockito.verify(snapshot.window).root
    Mockito.verify(snapshot.window).recycle()
    Mockito.verify(snapshot.root).packageName
    Mockito.verify(snapshot.root).recycle()
    // Acquired nodes contribute only a copied package name, never content or retained handles.
    Mockito.verifyNoMoreInteractions(snapshot.root)
  }

  private fun assertMissingRootAndReleased(snapshot: AppSnapshot) {
    Mockito.verify(snapshot.window).root
    Mockito.verify(snapshot.window).recycle()
    Mockito.verifyNoInteractions(snapshot.root)
  }

  private fun assertSkippedAndReleased(snapshot: AppSnapshot) {
    Mockito.verify(snapshot.window, Mockito.never()).root
    Mockito.verify(snapshot.window).recycle()
    // The stubbed root was never acquired, so it must not be read or recycled.
    Mockito.verifyNoInteractions(snapshot.root)
  }
}

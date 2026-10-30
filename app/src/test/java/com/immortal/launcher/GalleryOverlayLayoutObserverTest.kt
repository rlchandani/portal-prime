/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.view.View
import android.view.ViewTreeObserver
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito

class GalleryOverlayLayoutObserverTest {
  private class Fixture {
    val root = Mockito.mock(View::class.java)
    var tree = Mockito.mock(ViewTreeObserver::class.java)
    var attached = true
    var refreshes = 0
    init {
      Mockito.`when`(root.isAttachedToWindow).thenAnswer { attached }
      Mockito.`when`(root.viewTreeObserver).thenAnswer { tree }
      Mockito.`when`(tree.isAlive).thenReturn(true)
    }
    val observer = GalleryOverlayLayoutObserver(root) { refreshes++ }
  }

  @Test fun suspendedHostRejectsAlreadyDispatchedLayoutAndCatchesUpOnResume() {
    val f = Fixture()
    f.observer.setActive(true)
    assertEquals(1, f.refreshes)
    f.observer.onGlobalLayout()
    assertEquals(2, f.refreshes)
    f.observer.setActive(false)
    f.observer.onGlobalLayout()
    assertEquals(2, f.refreshes)
    Mockito.verify(f.tree).removeOnGlobalLayoutListener(f.observer)
    f.observer.setActive(true)
    assertEquals(3, f.refreshes)
    f.observer.close()
  }

  @Test fun reattachmentUsesTheNewTreeAndDoesNotRefreshWhileDetached() {
    val f = Fixture()
    val oldTree = f.tree
    f.observer.setActive(true)
    f.attached = false
    f.observer.onViewDetachedFromWindow(f.root)
    f.observer.onGlobalLayout()
    assertEquals(1, f.refreshes)
    Mockito.verify(oldTree).removeOnGlobalLayoutListener(f.observer)
    f.tree = Mockito.mock(ViewTreeObserver::class.java)
    Mockito.`when`(f.tree.isAlive).thenReturn(true)
    f.attached = true
    f.observer.onViewAttachedToWindow(f.root)
    assertEquals(2, f.refreshes)
    Mockito.verify(f.tree).addOnGlobalLayoutListener(f.observer)
    f.observer.close()
  }

  @Test fun closeIsTerminalEvenForLateLayoutAndAttachCallbacks() {
    val f = Fixture()
    f.observer.setActive(true)
    f.observer.close()
    f.observer.close()
    f.observer.onGlobalLayout()
    f.observer.onViewAttachedToWindow(f.root)
    f.observer.setActive(true)
    assertEquals(1, f.refreshes)
    Mockito.verify(f.tree).removeOnGlobalLayoutListener(f.observer)
    Mockito.verify(f.root).removeOnAttachStateChangeListener(f.observer)
  }

  @Test fun activationBeforeAttachmentWaitsForMeasuredHost() {
    val f = Fixture()
    f.attached = false
    f.observer.setActive(true)
    f.observer.onGlobalLayout()
    assertEquals(0, f.refreshes)
    f.attached = true
    f.observer.onViewAttachedToWindow(f.root)
    assertEquals(1, f.refreshes)
    f.observer.close()
  }

  @Test fun detachRejectsLateLayoutEvenBeforeTheFrameworkClearsTheAttachFlag() {
    val f = Fixture()
    f.observer.setActive(true)
    f.observer.onViewDetachedFromWindow(f.root)
    // A previously dispatched callback must respect the listener's own detached state.
    f.observer.onGlobalLayout()
    assertEquals(1, f.refreshes)
    f.observer.close()
  }
}

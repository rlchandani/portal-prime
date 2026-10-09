/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.appwidget.AppWidgetHost
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Test
import org.mockito.Mockito

/** Real AndroidX event replay; only the AppWidget framework boundary is mocked. */
class HomeWidgetListeningTest {
  private class Owner(state: Lifecycle.State = Lifecycle.State.CREATED) : LifecycleOwner {
    val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = state }
    override val lifecycle: Lifecycle get() = registry
  }

  @Test fun attachingToStartedHomeRequestsTheSystemListenerExactlyOnce() {
    val owner = Owner(Lifecycle.State.STARTED)
    val host = Mockito.mock(AppWidgetHost::class.java)
    val close = listenToHomeWidgets(host, owner.lifecycle, true)
    Mockito.verify(host).startListening()
    close()
    Mockito.verify(host).stopListening()
    Mockito.verifyNoMoreInteractions(host)
  }

  @Test fun attachingToResumedHomeDoesNotDuplicateTheLifecycleReplay() {
    val owner = Owner(Lifecycle.State.RESUMED)
    val host = Mockito.mock(AppWidgetHost::class.java)
    val close = listenToHomeWidgets(host, owner.lifecycle, true)
    Mockito.verify(host).startListening()
    close()
    Mockito.verify(host).stopListening()
    Mockito.verifyNoMoreInteractions(host)
  }

  @Test fun composeOnlyHomeNeverAcquiresAListenerAcrossActivationOrDisposal() {
    val owner = Owner(Lifecycle.State.RESUMED)
    val host = Mockito.mock(AppWidgetHost::class.java)
    val close = listenToHomeWidgets(host, owner.lifecycle, false)
    owner.registry.currentState = Lifecycle.State.CREATED
    owner.registry.currentState = Lifecycle.State.RESUMED
    close()
    Mockito.verifyNoInteractions(host)
  }

  @Test fun inactiveHomeDoesNotIssueAnUnownedStopWhenDisposed() {
    val owner = Owner()
    val host = Mockito.mock(AppWidgetHost::class.java)
    val close = listenToHomeWidgets(host, owner.lifecycle, true)
    close()
    Mockito.verifyNoInteractions(host)
  }

  @Test fun resumeAndPauseKeepOneRequestUntilHomeStops() {
    val owner = Owner()
    val host = Mockito.mock(AppWidgetHost::class.java)
    val close = listenToHomeWidgets(host, owner.lifecycle, true)
    owner.registry.currentState = Lifecycle.State.STARTED
    owner.registry.currentState = Lifecycle.State.RESUMED
    owner.registry.currentState = Lifecycle.State.STARTED
    Mockito.verify(host).startListening()
    Mockito.verify(host, Mockito.never()).stopListening()
    owner.registry.currentState = Lifecycle.State.CREATED
    close()
    Mockito.verify(host).stopListening()
    Mockito.verifyNoMoreInteractions(host)
  }

  @Test fun retainedHomeRestartsOnceOnReturnAndBalancesBothIntervals() {
    val owner = Owner()
    val host = Mockito.mock(AppWidgetHost::class.java)
    val close = listenToHomeWidgets(host, owner.lifecycle, true)
    owner.registry.currentState = Lifecycle.State.RESUMED
    owner.registry.currentState = Lifecycle.State.CREATED
    owner.registry.currentState = Lifecycle.State.RESUMED
    owner.registry.currentState = Lifecycle.State.CREATED
    close()
    Mockito.verify(host, Mockito.times(2)).startListening()
    Mockito.verify(host, Mockito.times(2)).stopListening()
    Mockito.verifyNoMoreInteractions(host)
  }

  @Test fun destructionThenRepeatedDisposalStopsOnlyTheOwnedRequest() {
    val owner = Owner(Lifecycle.State.RESUMED)
    val host = Mockito.mock(AppWidgetHost::class.java)
    val close = listenToHomeWidgets(host, owner.lifecycle, true)
    owner.registry.currentState = Lifecycle.State.DESTROYED
    close(); close()
    Mockito.verify(host).startListening()
    Mockito.verify(host).stopListening()
    Mockito.verifyNoMoreInteractions(host)
  }

  @Test fun throwingStartStillGetsOneCleanupWithoutAnImmediateDuplicateAttempt() {
    val owner = Owner(Lifecycle.State.RESUMED)
    val host = Mockito.mock(AppWidgetHost::class.java)
    Mockito.doThrow(IllegalStateException("Synthetic start failure")).`when`(host).startListening()
    val close = listenToHomeWidgets(host, owner.lifecycle, true)
    close(); close()
    Mockito.verify(host).startListening()
    Mockito.verify(host).stopListening()
    Mockito.verifyNoMoreInteractions(host)
  }

  @Test fun throwingStopDoesNotDuplicateCleanupOrPreventNextActivation() {
    val owner = Owner()
    val host = Mockito.mock(AppWidgetHost::class.java)
    Mockito.doThrow(IllegalStateException("Synthetic stop failure")).`when`(host).stopListening()
    val close = listenToHomeWidgets(host, owner.lifecycle, true)
    owner.registry.currentState = Lifecycle.State.RESUMED
    owner.registry.currentState = Lifecycle.State.CREATED
    owner.registry.currentState = Lifecycle.State.RESUMED
    close(); close()
    Mockito.verify(host, Mockito.times(2)).startListening()
    Mockito.verify(host, Mockito.times(2)).stopListening()
    Mockito.verifyNoMoreInteractions(host)
  }

  @Test fun removingLastAndroidWidgetAndAddingAnotherBalancesSeparateOwners() {
    val owner = Owner(Lifecycle.State.RESUMED)
    val host = Mockito.mock(AppWidgetHost::class.java)
    val first = listenToHomeWidgets(host, owner.lifecycle, true)
    first()
    val none = listenToHomeWidgets(host, owner.lifecycle, false)
    owner.registry.currentState = Lifecycle.State.CREATED
    owner.registry.currentState = Lifecycle.State.RESUMED
    none()
    val second = listenToHomeWidgets(host, owner.lifecycle, true)
    second()
    Mockito.verify(host, Mockito.times(2)).startListening()
    Mockito.verify(host, Mockito.times(2)).stopListening()
    Mockito.verifyNoMoreInteractions(host)
  }
}

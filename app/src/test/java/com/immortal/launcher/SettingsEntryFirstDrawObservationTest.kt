/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.view.View
import android.view.ViewTreeObserver
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class SettingsEntryFirstDrawObservationTest {
  private fun view(observer: ViewTreeObserver): View = Mockito.mock(View::class.java).also {
    Mockito.`when`(it.viewTreeObserver).thenReturn(observer)
  }

  @Test fun attachmentReplacementRemovesFromTheCurrentObserver() {
    val original = Mockito.mock(ViewTreeObserver::class.java)
    val attached = Mockito.mock(ViewTreeObserver::class.java)
    val decor = view(original)
    var reports = 0
    val observation = SettingsEntryFirstDrawObservation(decor) { reports++ }
    // Android's merge transfers the listener, then kills the unattached observer.
    Mockito.`when`(decor.viewTreeObserver).thenReturn(attached)
    Mockito.`when`(attached.isAlive).thenReturn(true)
    observation.dispose()
    Mockito.verify(attached).removeOnPreDrawListener(observation)
    Mockito.verify(original, Mockito.never()).removeOnPreDrawListener(observation)
    assertTrue(observation.onPreDraw())
    assertEquals(0, reports)
  }

  @Test fun firstDrawAfterAttachmentRemovesFromTheCurrentObserverBeforeReport() {
    val original = Mockito.mock(ViewTreeObserver::class.java)
    val attached = Mockito.mock(ViewTreeObserver::class.java)
    val decor = view(original)
    var reports = 0
    lateinit var observation: SettingsEntryFirstDrawObservation
    observation = SettingsEntryFirstDrawObservation(decor) {
      Mockito.verify(attached).removeOnPreDrawListener(observation)
      reports++
    }
    Mockito.`when`(decor.viewTreeObserver).thenReturn(attached)
    Mockito.`when`(attached.isAlive).thenReturn(true)
    assertTrue(observation.onPreDraw())
    observation.dispose()
    assertEquals(1, reports)
    Mockito.verify(attached, Mockito.times(1)).removeOnPreDrawListener(observation)
  }

  @Test fun firstPreDrawRemovesBeforeReportingAndNeverCancelsDrawing() {
    val observer = Mockito.mock(ViewTreeObserver::class.java)
    Mockito.`when`(observer.isAlive).thenReturn(true)
    var reports = 0
    lateinit var observation: SettingsEntryFirstDrawObservation
    observation = SettingsEntryFirstDrawObservation(view(observer)) {
      Mockito.verify(observer).removeOnPreDrawListener(observation)
      reports++
    }
    assertTrue(observation.onPreDraw())
    assertTrue(observation.onPreDraw())
    observation.dispose()
    assertEquals(1, reports)
    Mockito.verify(observer, Mockito.times(1)).removeOnPreDrawListener(observation)
  }

  @Test fun captureDisposalSuppressesAlreadyQueuedPreDrawCallback() {
    val observer = Mockito.mock(ViewTreeObserver::class.java)
    Mockito.`when`(observer.isAlive).thenReturn(true)
    var reports = 0
    val observation = SettingsEntryFirstDrawObservation(view(observer)) { reports++ }
    observation.dispose()
    assertTrue(observation.onPreDraw())
    assertEquals(0, reports)
    Mockito.verify(observer).removeOnPreDrawListener(observation)
  }

  @Test fun deadViewTreeIsNotReadOrRemovedAgain() {
    val observer = Mockito.mock(ViewTreeObserver::class.java)
    val observation = SettingsEntryFirstDrawObservation(view(observer)) {}
    observation.dispose(); observation.dispose()
    Mockito.verify(observer, Mockito.times(1)).isAlive
    Mockito.verify(observer, Mockito.never()).removeOnPreDrawListener(observation)
  }

  @Test fun observerRemovalFailureCannotCancelDrawingOrRepeatTheProbe() {
    val observer = Mockito.mock(ViewTreeObserver::class.java)
    Mockito.`when`(observer.isAlive).thenReturn(true)
    var reports = 0
    val observation = SettingsEntryFirstDrawObservation(view(observer)) { reports++ }
    Mockito.doThrow(IllegalStateException("synthetic observer removal failure"))
        .`when`(observer).removeOnPreDrawListener(observation)
    assertTrue(observation.onPreDraw()); assertTrue(observation.onPreDraw())
    assertEquals(1, reports)
  }

  @Test fun diagnosticFailureDoesNotCancelOrRepeatDrawing() {
    val observer = Mockito.mock(ViewTreeObserver::class.java)
    Mockito.`when`(observer.isAlive).thenReturn(true)
    var reports = 0
    val observation = SettingsEntryFirstDrawObservation(view(observer)) {
      reports++
      throw IllegalStateException("synthetic diagnostic failure")
    }
    assertTrue(observation.onPreDraw()); assertTrue(observation.onPreDraw())
    assertEquals(1, reports)
  }
}

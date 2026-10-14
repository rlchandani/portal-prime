/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class WebAppContentRevealGateTest {
  @Test fun firstCommittedDocumentRevealsWithoutWaitingForPageFinishedAndOnlyOnce() {
    val gate = WebAppContentRevealGate()
    gate.request("https://fixture/one", null); gate.started()
    val ticket = gate.capture("https://fixture/one", "https://fixture/one")!!
    assertTrue(gate.complete(ticket))
    assertFalse(gate.complete(ticket))
    assertNull(gate.capture("https://fixture/one", "https://fixture/one"))
  }
  @Test fun queuedOldVisualCallbackCannotRevealAfterNewDocumentRequest() {
    val gate = WebAppContentRevealGate(); gate.started()
    val old = gate.capture("https://fixture/one", "https://fixture/one")!!
    gate.request("https://fixture/two", "https://fixture/one")
    assertFalse(gate.complete(old)); gate.started()
    assertFalse(gate.complete(old))
    assertTrue(gate.complete(gate.capture("https://fixture/two", "https://fixture/two")!!))
  }
  @Test fun lateOldCommitBetweenNewRequestAndNewStartCannotCaptureNewRevision() {
    val gate = WebAppContentRevealGate(); gate.started()
    gate.request("https://fixture/two", "https://fixture/one")
    assertNull(gate.capture("https://fixture/one", "https://fixture/one"))
    gate.started()
    assertNotNull(gate.capture("https://fixture/two", "https://fixture/two"))
  }
  @Test fun redirectedDocumentIsAcceptedAndSameEntryReturnStillDoesNotReload() {
    val gate = WebAppContentRevealGate()
    gate.request("https://fixture/entry", null); gate.started()
    assertTrue(gate.complete(gate.capture("https://fixture/final", "https://fixture/final")!!))
    assertFalse(WebAppEntryState("https://fixture/entry").request("https://fixture/entry"))
  }
  @Test fun fragmentOnlyRequestRetainsReadinessWithoutWaitingForAbsentStart() {
    val gate = WebAppContentRevealGate(); gate.started()
    val ready = gate.capture("https://fixture/page", "https://fixture/page")!!
    gate.request("https://fixture/page#chapter", "https://fixture/page")
    assertEquals(ready, gate.capture("https://fixture/page", "https://fixture/page#chapter"))
    assertTrue(gate.complete(ready))
    gate.request("https://fixture/page#next", "https://fixture/page#chapter")
    assertFalse(gate.complete(ready))
  }
  @Test fun destructionRejectsQueuedVisualCompletionAndLaterCallbacks() {
    val gate = WebAppContentRevealGate(); gate.started()
    val ready = gate.capture("https://fixture/page", "https://fixture/page")!!
    gate.destroy(); gate.started()
    assertFalse(gate.complete(ready))
    assertNull(gate.capture("https://fixture/page", "https://fixture/page"))
  }
  @Test fun noDocumentCanRevealBeforeItsFirstMainFrameStart() {
    val gate = WebAppContentRevealGate()
    assertNull(gate.capture("https://fixture/page", "https://fixture/page"))
    assertFalse(gate.complete(0))
    gate.request("https://fixture/page", null)
    assertNull(gate.capture("https://fixture/page", "https://fixture/page"))
  }
  @Test fun nullOrDifferentDocumentsCannotCaptureVisualReadiness() {
    val gate = WebAppContentRevealGate(); gate.started()
    assertNull(gate.capture(null, "https://fixture/page"))
    assertNull(gate.capture("https://fixture/page", null))
    assertNull(gate.capture("https://fixture/one", "https://fixture/two"))
    assertNull(gate.capture("https://fixture/page?a=1", "https://fixture/page?a=2"))
  }
  @Test fun inSiteMainFrameNavigationInvalidatesPreviousPendingVisualCallback() {
    val gate = WebAppContentRevealGate(); gate.started()
    val old = gate.capture("https://fixture/one", "https://fixture/one")!!
    gate.started()
    assertFalse(gate.complete(old))
    val next = gate.capture("https://fixture/two", "https://fixture/two")!!
    assertTrue(gate.complete(next)); assertFalse(gate.complete(next))
  }
  @Test fun failedDocumentInvalidatesQueuedVisualCompletionAndErrorPageReadiness() {
    val gate = WebAppContentRevealGate()
    gate.request("https://fixture/one", null); gate.started("https://fixture/one")
    val old = gate.capture("https://fixture/one", "https://fixture/one")!!
    assertTrue(gate.fail("https://fixture/one", "https://fixture/one"))
    assertFalse(gate.complete(old))
    assertNull(gate.capture("https://fixture/one", "https://fixture/one"))
    assertFalse(gate.started("https://fixture/one"))
    assertNull(gate.capture("https://fixture/one", "https://fixture/one"))
  }
  @Test fun forcedSameDocumentRetryRejectsOldCallbacksAndCanRevealNewContent() {
    val gate = WebAppContentRevealGate()
    gate.request("https://fixture/one", null); gate.started("https://fixture/one")
    val old = gate.capture("https://fixture/one", "https://fixture/one")!!
    assertTrue(gate.fail("https://fixture/one", "https://fixture/one"))
    gate.request("https://fixture/one", "https://fixture/one", force = true)
    assertFalse(gate.complete(old))
    assertNull(gate.capture("https://fixture/one", "https://fixture/one"))
    assertTrue(gate.started("https://fixture/one"))
    assertTrue(gate.complete(gate.capture("https://fixture/one", "https://fixture/one")!!))
  }
  @Test fun supersededDocumentFailureCannotReplacePendingOrStartedNewDocument() {
    val gate = WebAppContentRevealGate()
    gate.request("https://fixture/one", null); gate.started("https://fixture/one")
    gate.request("https://fixture/two", "https://fixture/one")
    assertFalse(gate.fail("https://fixture/one", "https://fixture/one"))
    gate.started("https://fixture/two")
    assertFalse(gate.fail("https://fixture/one", "https://fixture/two"))
    assertTrue(gate.complete(gate.capture("https://fixture/two", "https://fixture/two")!!))
  }
  @Test fun redirectFailureUsesRedirectTargetAndCanRetryIt() {
    val gate = WebAppContentRevealGate()
    gate.request("https://fixture/entry", null); gate.started("https://fixture/final")
    assertTrue(gate.fail("https://fixture/final", "https://fixture/final"))
    gate.request("https://fixture/final", "https://fixture/final", force = true)
    assertTrue(gate.started("https://fixture/final"))
    assertNotNull(gate.capture("https://fixture/final", "https://fixture/final"))
  }
  @Test fun destructionRejectsFailureAndRetry() {
    val gate = WebAppContentRevealGate()
    gate.request("https://fixture/one", null); gate.started("https://fixture/one")
    gate.destroy()
    assertFalse(gate.fail("https://fixture/one", "https://fixture/one"))
    gate.request("https://fixture/one", "https://fixture/one", force = true)
    assertFalse(gate.started("https://fixture/one"))
    assertNull(gate.capture("https://fixture/one", "https://fixture/one"))
  }
  @Test fun nullAndDifferentQueryFailuresAreRejected() {
    val gate = WebAppContentRevealGate()
    gate.request("https://fixture/page?a=1", null); gate.started("https://fixture/page?a=1")
    assertFalse(gate.fail(null, "https://fixture/page?a=1"))
    assertFalse(gate.fail("https://fixture/page?a=2", "https://fixture/page?a=1"))
    assertTrue(gate.fail("https://fixture/page?a=1#chapter", "https://fixture/page?a=1"))
  }

  @Test fun fragmentChangeDoesNotAskCallerToShowLoadingWithoutNewDocumentStart() {
    val gate = WebAppContentRevealGate()
    assertTrue(gate.request("https://fixture/page", null))
    gate.started("https://fixture/page")
    assertTrue(gate.complete(gate.capture("https://fixture/page", "https://fixture/page")!!))
    assertFalse(gate.request("https://fixture/page#chapter", "https://fixture/page"))
    assertTrue(gate.request("https://fixture/page", "https://fixture/page", force = true))
  }

}

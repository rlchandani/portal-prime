/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

/** Boundary cases: package identity alone must never authorize input or disclose field lengths. */
class HomeUiDiagnosticsTest {
  private fun node(key: String? = null, left: Int = 0, top: Int = 100, right: Int = 100,
      bottom: Int = 150, parent: Int = 1, clickable: Boolean = true,
      editable: Boolean = false, password: Boolean = false, hint: String? = null, id: Int = -1) =
      HomeUiDiagnostics.Node(parent, HomeUiDiagnostics.Bounds(left, top, right, bottom), key,
          clickable, false, editable, false, editable, password, editable, hint, 17, id)
  private fun dock() = listOf(node("home", 100, 700, 200, 780),
      node("apps", 220, 700, 320, 780), node("tools", 340, 700, 440, 780))
  private fun state(nodes: List<HomeUiDiagnostics.Node>, truncated: Boolean = false) =
      HomeUiDiagnostics.State(1000, 800, nodes.size, truncated, nodes)

  private fun unmergedDock(): List<HomeUiDiagnostics.Node> = dock().flatMapIndexed { i, row ->
    val rowId = 101 + i * 2
    listOf(row.copy(key = null, parent = 90, id = rowId),
        row.copy(bounds = HomeUiDiagnostics.Bounds(row.bounds.left + 20, 720, row.bounds.right - 20, 750),
            clickable = false, parent = rowId, id = rowId + 1))
  }.reversed() // IDs deliberately differ from the compact visible-node array's indexes.

  @Test fun unmergedDockLabelsResolveToTheirThreeSiblingRowsAndFullTouchBounds() {
    val snapshot = state(unmergedDock())
    assertTrue(snapshot.dockPresent())
    assertTrue(snapshot.eligible())
    val response = snapshot.json().getJSONArray("nodes")
    for (row in dock()) {
      val control = (0 until response.length()).map { response.getJSONObject(it) }
          .single { it.getString("key") == row.key }
      assertTrue(control.getBoolean("clickable"))
      assertEquals(row.bounds.json().toString(), control.getJSONArray("bounds").toString())
    }
    assertFalse(state(unmergedDock(), truncated = true).eligible())
  }
  @Test fun wrongOutsideCredentialOrTooDistantAncestorsDoNotBecomeControls() {
    val label = node("home", 120, 720, 180, 750, parent = 40, clickable = false, id = 41)
    val row = node(left = 100, top = 700, right = 200, bottom = 780, parent = 90, id = 40)
    val appsTools = dock().drop(1).mapIndexed { i, n -> n.copy(parent = 90, id = 60 + i) }
    val rejected = listOf(
        row.copy(key = "tools"),
        row.copy(bounds = HomeUiDiagnostics.Bounds(140, 700, 200, 780)),
        row.copy(editable = true), row.copy(password = true))
    rejected.forEach { ancestor ->
      val snapshot = state(appsTools + listOf(label, ancestor))
      assertFalse(snapshot.dockPresent())
      assertFalse(snapshot.eligible())
    }
    val wrapper = row.copy(clickable = false, parent = 39)
    val grandparent = wrapper.copy(id = 39, parent = 38)
    val third = wrapper.copy(id = 38, parent = 37)
    val fourth = row.copy(id = 37)
    assertFalse(state(appsTools + listOf(label, wrapper, grandparent, third, fourth)).dockPresent())
    assertTrue(state(appsTools + listOf(label, wrapper, grandparent, third.copy(clickable = true, parent = 90))).dockPresent())
    assertFalse(state(appsTools + listOf(label, wrapper.copy(parent = 41))).dockPresent())
  }
  @Test fun unmergedAppMenuLabelsResolveTheirActualRowsWithoutAdmittingEditableContent() {
    val keys = listOf("menuOpen", "menuAppInfo", "menuMove", "menuAddFavorite")
    val nodes = keys.flatMapIndexed { i, key ->
      val row = node(left = 100, top = 100 + i * 100, right = 500, bottom = 180 + i * 100,
          parent = 80, id = 10 + i * 2)
      listOf(row, row.copy(key = key,
          bounds = HomeUiDiagnostics.Bounds(120, row.bounds.top + 20, 280, row.bounds.bottom - 20),
          clickable = false, parent = row.id, id = row.id + 1))
    }
    val snapshot = state(nodes)
    assertTrue(snapshot.appMenuPresent())
    assertFalse(snapshot.dockPresent())
    assertTrue(snapshot.eligible())
    val controls = snapshot.json().getJSONArray("nodes")
    assertEquals(4, controls.length())
    keys.forEachIndexed { i, key ->
      val control = (0 until controls.length()).map { controls.getJSONObject(it) }
          .single { it.getString("key") == key }
      assertEquals(HomeUiDiagnostics.Bounds(100, 100 + i * 100, 500, 180 + i * 100).json().toString(),
          control.getJSONArray("bounds").toString())
    }
    assertFalse(state(nodes + node(editable = true)).appMenuPresent())
    assertFalse(state(nodes.map { if (it.id == 12) it.copy(password = true) else it }).appMenuPresent())
  }
  @Test fun publicAppLabelUsesOnlyItsOwnAncestorLongPressBoundsAndDoesNotDuplicateControls() {
    val row = node(left = 100, top = 200, right = 300, bottom = 400, parent = 80, id = 40)
        .copy(longClickable = true, focused = true, accessibilityFocused = true)
    val label = node("browser", 140, 300, 260, 340, parent = 40, clickable = false, id = 41)
    val snapshot = state(unmergedDock() + listOf(row, label))
    assertTrue(snapshot.publicLongPressTarget(110.0, 210.0))
    assertFalse(snapshot.publicLongPressTarget(300.0, 210.0))
    val controls = snapshot.json().getJSONArray("nodes")
    val browser = (0 until controls.length()).map { controls.getJSONObject(it) }
        .single { it.getString("key") == "browser" }
    assertTrue(browser.getBoolean("longClickable"))
    assertTrue(browser.getBoolean("focused"))
    assertTrue(browser.getBoolean("accessibilityFocused"))
    val mismatched = state(unmergedDock() + listOf(row.copy(key = "jarvis"), label))
    // The independently labelled public Jarvis row remains valid; Browser cannot borrow it.
    assertTrue(mismatched.publicLongPressTarget(110.0, 210.0))
    val mismatchedRows = mismatched.json().getJSONArray("nodes")
    val rejectedBrowser = (0 until mismatchedRows.length()).map { mismatchedRows.getJSONObject(it) }
        .single { it.getString("key") == "browser" }
    assertFalse(rejectedBrowser.getBoolean("clickable"))
    assertFalse(rejectedBrowser.getBoolean("longClickable"))
    assertFalse(state(unmergedDock() + listOf(row.copy(key = "searchButton"), label))
        .publicLongPressTarget(110.0, 210.0))
    val unknown = state(unmergedDock() + listOf(row, label.copy(key = "PRIVATE_LABEL_SENTINEL")))
    assertFalse(unknown.publicLongPressTarget(110.0, 210.0))
    assertFalse(unknown.json().toString().contains("PRIVATE_LABEL_SENTINEL"))
    val mergedAndChild = state(unmergedDock() + listOf(row.copy(key = "browser"), label))
    val result = mergedAndChild.json().getJSONArray("nodes")
    assertEquals(1, (0 until result.length()).count { result.getJSONObject(it).getString("key") == "browser" })
  }

  @Test fun unrelatedOwnScreenDoesNotQualifyThroughWordsAlone() {
    assertFalse(state(listOf(node("home"), node("apps"), node("tools"))).eligible())
    assertFalse(state(dock().map { it.copy(clickable = false) }).eligible())
    assertFalse(state(dock().mapIndexed { i, n -> n.copy(parent = i) }).eligible())
    assertTrue(state(dock()).dockPresent())
  }
  @Test fun partialTreeCannotAuthorizeInputOrDisclosure() {
    val snapshot = state(dock(), truncated = true)
    assertFalse(snapshot.eligible())
    assertNull(snapshot.searchNode())
  }
  @Test fun AppActionsDialogHasItsOwnFiniteSignatureWithoutAuthorizingDockInput() {
    val snapshot = state(listOf(node("menuOpen"), node("menuAppInfo"), node("menuMove"), node("menuAddFavorite")))
    assertTrue(snapshot.eligible())
    assertTrue(snapshot.appMenuPresent())
    assertFalse(snapshot.dockPresent())
    assertFalse(state(listOf(node("menuOpen"), node("menuAppInfo"))).eligible())
  }
  @Test fun credentialOrFolderFieldNeverBecomesSearchByBeingEditable() {
    val field = node(editable = true)
    assertNull(state(dock() + field).searchNode())
    assertNull(state(listOf(field.copy(searchHint = "searchInstalledApps"))).searchNode())
    assertNull(state(dock() + field.copy(password = true, searchHint = "searchApps")).searchNode())
  }
  @Test fun typedAppsDraftIsRecognizedByFixedPageStructureAfterPlaceholderDisappears() {
    val field = node(top = 280, bottom = 350, editable = true)
    val snapshot = state(dock() + listOf(node("appsHeading", top = 100, bottom = 150),
        node("appsSubtitle", top = 160, bottom = 200), field))
    assertEquals(field to "searchInstalledApps", snapshot.searchNode())
    assertNull(state(snapshot.nodes + node(top = 400, editable = true)).searchNode())
    assertNull(state(snapshot.nodes.map { if (it === field) it.copy(password = true) else it }).searchNode())
  }
  @Test fun toolsEditableDraftDoesNotBecomeAnAppsSearchOrAnAppsPage() {
    val snapshot = state(dock() + listOf(node("tools", clickable = false),
        node("toolsSubtitle", clickable = false), node(editable = true)))
    assertTrue(snapshot.toolsLibraryVisible())
    assertFalse(snapshot.appsPageVisible())
    assertNull(snapshot.searchNode())
    assertFalse(state(snapshot.nodes, truncated = true).toolsLibraryVisible())
  }
  @Test fun ordinaryControlsAndUnknownAppsCannotBecomeLongPressTargets() {
    val browser = node("browser", left = 100, top = 200, right = 220, bottom = 320)
        .copy(longClickable = true)
    assertTrue(state(dock() + browser).publicLongPressTarget(150.0, 250.0))
    assertFalse(state(dock() + browser.copy(longClickable = false)).publicLongPressTarget(150.0, 250.0))
    assertFalse(state(dock() + browser.copy(key = "searchButton")).publicLongPressTarget(150.0, 250.0))
    assertFalse(state(dock() + browser.copy(key = null)).publicLongPressTarget(150.0, 250.0))
    assertFalse(state(dock() + browser).publicLongPressTarget(220.0, 250.0))
    assertFalse(state(listOf(browser)).publicLongPressTarget(150.0, 250.0))
    assertFalse(state(dock() + browser, truncated = true).publicLongPressTarget(150.0, 250.0))
  }
  @Test fun coordinateBoundaryRejectsNonfiniteAndOutsideScreenWithoutClamping() {
    assertTrue(HomeUiDiagnostics.validPoint(0.0, 0.0, 1000, 800))
    assertTrue(HomeUiDiagnostics.validPoint(999.5, 799.5, 1000, 800))
    listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 1000.0).forEach {
      assertFalse(HomeUiDiagnostics.validPoint(it, 50.0, 1000, 800))
    }
    assertFalse(HomeUiDiagnostics.validPoint(50.0, 800.0, 1000, 800))
    // A Double inside the screen must not round to an outside pixel in the Float gesture API.
    assertFalse(HomeUiDiagnostics.validPoint(999.999999999, 50.0, 1000, 800))
  }
  @Test fun failedDockEvidenceDoesNotDisclosePrivateFieldsOrAuthorizeInput() {
    val privateField = node("searchInstalledApps", editable = true, hint = "searchInstalledApps")
        .copy(textLength = 137)
    val snapshot = state(dock().mapIndexed { i, n -> n.copy(parent = i) } + listOf(
        privateField, node("PRIVATE_LABEL_SENTINEL"), node("menuAppInfo"),
        node("home", editable = true).copy(textLength = 203),
        node("apps", password = true),
        node("browser", left = 100, top = 200, right = 220, bottom = 320)
            .copy(longClickable = true)))
    val response = snapshot.json()
    assertFalse(response.getBoolean("eligible"))
    assertEquals(0, response.getJSONArray("nodes").length())
    assertFalse(snapshot.publicLongPressTarget(150.0, 250.0))
    assertNull(snapshot.searchNode())
    val evidence = response.getJSONObject("ineligibleDockEvidence")
    val candidates = evidence.getJSONArray("candidates")
    assertEquals(3, candidates.length())
    for (key in listOf("home", "apps", "tools")) {
      assertEquals(1, evidence.getJSONObject("labelCounts").getInt(key))
      assertEquals(1, evidence.getJSONObject("clickableCounts").getInt(key))
    }
    // The missing sibling-parent relation is visible, while neither draft length nor labels leak.
    assertEquals(0, candidates.getJSONObject(0).getInt("parent"))
    assertEquals(1, candidates.getJSONObject(1).getInt("parent"))
    assertEquals(2, candidates.getJSONObject(2).getInt("parent"))
    assertFalse(response.toString().contains("PRIVATE_LABEL_SENTINEL"))
    assertFalse(response.toString().contains("inputTextLength"))
    assertFalse(response.toString().contains("searchInstalledApps"))
    assertFalse(response.toString().contains("menuAppInfo"))
    assertFalse(response.toString().contains("browser"))
  }
  @Test fun arbitraryTextCannotBecomeAResponseKeyEvenWhenItContainsAKnownLabel() {
    assertEquals("browser", HomeUiDiagnostics.fixedKey("Browser"))
    assertNull(HomeUiDiagnostics.fixedKey("https://private.invalid/Browser?q=secret"))
    assertNull(HomeUiDiagnostics.fixedKey("No apps match “Browser”"))
    assertNull(HomeUiDiagnostics.fixedKey("Private app"))
  }
}

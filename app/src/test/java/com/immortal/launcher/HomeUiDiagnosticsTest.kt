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

  @Test fun appearanceControlsExportPublicGeometryWithoutAdmittingSettingsAsHome() {
    val labels = listOf("Settings", "App appearance", "Appearance", "App tile size",
        "Now-playing mini-player", "Clock", "Hide status bar")
    val snapshot = state(labels.mapIndexed { i, label ->
      node(HomeUiDiagnostics.fixedKey(label), top = 20 + i * 80, bottom = 80 + i * 80)
          .copy(longClickable = true)
    })
    val response = snapshot.json()
    val rows = response.getJSONArray("nodes")
    assertEquals(listOf("settingsTitle", "appAppearance", "appearance", "tileSize",
        "showMiniPlayer", "clockFormat", "hideStatusBar"),
        (0 until rows.length()).map { rows.getJSONObject(it).getString("key") })
    assertFalse(response.getBoolean("eligible"))
    assertFalse(snapshot.dockPresent())
    assertFalse(snapshot.appMenuPresent())
    assertNull(snapshot.searchNode())
    assertFalse(snapshot.publicLongPressTarget(50.0, 210.0))
    // Even with a real dock, a newly labelled control is never a public app long-press target.
    assertFalse(state(dock() + snapshot.nodes).publicLongPressTarget(50.0, 210.0))
    assertEquals("[0,260,100,320]", rows.getJSONObject(3).getJSONArray("bounds").toString())
    assertFalse(response.toString().contains("App tile size"))
    assertFalse(response.toString().contains("inputTextLength"))
  }

  @Test fun registryDescriptionAliasesResolveAnUnmergedControlAndRetainItsTouchBounds() {
    val row = node(left = 100, top = 200, right = 500, bottom = 300, parent = 80, id = 40)
    val label = node(HomeUiDiagnostics.fixedKey("immortal.tileSize"),
        120, 220, 280, 260, parent = 40, clickable = false, id = 41)
    val snapshot = state(listOf(row, label))
    val controls = snapshot.json().getJSONArray("nodes")
    assertEquals(1, controls.length())
    assertEquals("tileSize", controls.getJSONObject(0).getString("key"))
    assertTrue(controls.getJSONObject(0).getBoolean("clickable"))
    assertEquals("[100,200,500,300]", controls.getJSONObject(0).getJSONArray("bounds").toString())
    val aliases = state(listOf("immortal.settingsTheme", "immortal.clockFormat",
        "immortal.showMiniPlayer", "immortal.hideStatusBar").map { node(HomeUiDiagnostics.fixedKey(it)) })
        .json().getJSONArray("nodes")
    assertEquals(listOf("settingsTheme", "clockFormat", "showMiniPlayer", "hideStatusBar"),
        (0 until aliases.length()).map { aliases.getJSONObject(it).getString("key") })
  }

  @Test fun pickerChoicesAndDismissalExportOnlyFixedKeysOnADialogSurface() {
    val dialogs = listOf(
        listOf("Appearance", "Light", "Dark", "Close dialog") to
            listOf("appearance", "choiceLight", "choiceDark", "menuDismiss"),
        listOf("App tile size", "Standard", "Large", "XL", "Close dialog") to
            listOf("tileSize", "choiceStandard", "choiceLarge", "choiceXL", "menuDismiss"),
        listOf("Clock", "Auto", "12-hour", "24-hour", "Close dialog") to
            listOf("clockFormat", "choiceAuto", "choice12", "choice24", "menuDismiss"))
    for ((labels, keys) in dialogs) {
      val snapshot = state(labels.map { node(HomeUiDiagnostics.fixedKey(it)) })
      val rows = snapshot.json().getJSONArray("nodes")
      assertEquals(keys, (0 until rows.length()).map { rows.getJSONObject(it).getString("key") })
      assertFalse(snapshot.eligible())
      assertNull(snapshot.searchNode())
      assertFalse(snapshot.json().toString().contains("inputTextLength"))
    }
  }

  @Test fun advancedConsentAndAppearanceNavigationStayReadOnlyPublicSelectors() {
    val labels = listOf("Display", "Wallpaper", "Home screen", "Advanced options",
        "Show advanced options", "Advanced settings", "Turn on Advanced settings", "Cancel")
    val snapshot = state(labels.map { node(HomeUiDiagnostics.fixedKey(it)) })
    val rows = snapshot.json().getJSONArray("nodes")
    assertEquals(listOf("display", "wallpaper", "homeScreen", "advancedOptions",
        "advancedHint", "advancedTitle", "consentEnable", "consentCancel"),
        (0 until rows.length()).map { rows.getJSONObject(it).getString("key") })
    assertFalse(snapshot.eligible())
    assertFalse(snapshot.publicLongPressTarget(50.0, 120.0))
  }

  @Test fun settingsExportStillExcludesUnknownEditablePasswordAndSearchDrafts() {
    val snapshot = state(listOf(
        node(HomeUiDiagnostics.fixedKey("App appearance")),
        node("PRIVATE_LABEL_SENTINEL"),
        node(HomeUiDiagnostics.fixedKey("Appearance"), editable = true).copy(textLength = 137),
        node(HomeUiDiagnostics.fixedKey("App tile size"), password = true),
        node(HomeUiDiagnostics.fixedKey("Cancel"), editable = true),
        node(HomeUiDiagnostics.fixedKey("Close dialog"), password = true),
        node(editable = true, hint = "searchApps").copy(textLength = 203),
        node(HomeUiDiagnostics.fixedKey("Search settings"), editable = true)))
    val response = snapshot.json()
    val rows = response.getJSONArray("nodes")
    assertEquals(1, rows.length())
    assertEquals("appAppearance", rows.getJSONObject(0).getString("key"))
    assertNull(snapshot.searchNode())
    assertFalse(response.getBoolean("focusedEditable"))
    assertFalse(response.toString().contains("PRIVATE_LABEL_SENTINEL"))
    assertFalse(response.toString().contains("inputTextLength"))
    assertFalse(response.toString().contains("searchApps"))
    assertNull(HomeUiDiagnostics.fixedKey("Private Appearance value"))
    assertNull(HomeUiDiagnostics.fixedKey("https://private.invalid/immortal.tileSize"))
    assertNull(HomeUiDiagnostics.fixedKey("light"))
  }

  // Literal source titles from ApprovedSettingsNavigation; expected wire keys are independent
  // of the inspector map, and do not require initializing the navigation or settings registry.
  private val settingsNavigationLabels = mapOf(
      "Appearance" to "appearance", "Photos & screensaver" to "photosScreensaver",
      "Home & widgets" to "homeWidgets", "Sound & wake" to "soundWake",
      "Connections" to "connections", "Input & accessibility" to "inputAccessibility",
      "Device" to "device", "Advanced" to "advanced",
      "Display" to "display", "App appearance" to "appAppearance", "Wallpaper" to "wallpaper",
      "Photo clock faces" to "photoClockFaces", "Digital clock" to "digitalClock",
      "Photo sources" to "photoSources", "Weather" to "weather", "Calendar" to "calendar",
      "Alarm" to "alarm", "Quick buttons" to "quickButtons", "Touch sounds" to "touchSounds",
      "Sounds" to "sounds", "Welcome greeting" to "welcomeGreeting", "Sunrise & wake" to "sunriseWake",
      "Home dashboard" to "homeDashboard", "Intercom" to "intercom", "Device name" to "deviceName",
      "Sleep timer" to "sleepTimer", "About & device health" to "aboutDeviceHealth",
      "Android & Portal settings" to "androidPortalSettings",
  )

  @Test fun sourceDefinedSettingsTitlesExportFixedKeysOnNonEligibleSurfaces() {
    for ((title, key) in settingsNavigationLabels) {
      assertEquals(title, key, HomeUiDiagnostics.fixedKey(title))
      val snapshot = state(listOf(node(HomeUiDiagnostics.fixedKey(title), left = 300, right = 900)))
      val response = snapshot.json()
      val rows = response.getJSONArray("nodes")
      assertFalse(title, response.getBoolean("eligible"))
      assertEquals(title, 1, rows.length())
      assertEquals(key, rows.getJSONObject(0).getString("key"))
      assertEquals("[300,100,900,150]", rows.getJSONObject(0).getJSONArray("bounds").toString())
      assertFalse(response.toString().contains("\"$title\""))
    }
  }

  @Test fun sharedCategoryAndPageTitlesKeepOneKeyAndDistinctTouchAndHeadingBounds() {
    for ((title, key) in listOf("Photos & screensaver" to "photosScreensaver",
        "Home & widgets" to "homeWidgets")) {
      val row = node(left = 0, right = 250, parent = 80, id = 40)
      val label = node(HomeUiDiagnostics.fixedKey(title), 20, 110, 230, 140,
          parent = 40, clickable = false, id = 41)
      val heading = node(HomeUiDiagnostics.fixedKey(title), 300, 60, 900, 90,
          parent = 90, clickable = false, id = 42)
      val snapshot = state(listOf(row, label, heading))
      val rows = snapshot.json().getJSONArray("nodes")
      assertEquals(title, 2, rows.length())
      assertEquals(key, rows.getJSONObject(0).getString("key"))
      assertEquals(key, rows.getJSONObject(1).getString("key"))
      assertTrue(rows.getJSONObject(0).getBoolean("clickable"))
      assertFalse(rows.getJSONObject(1).getBoolean("clickable"))
      assertEquals("[0,100,250,150]", rows.getJSONObject(0).getJSONArray("bounds").toString())
      assertEquals("[300,60,900,90]", rows.getJSONObject(1).getJSONArray("bounds").toString())
      assertFalse(snapshot.eligible())
    }
  }

  @Test fun settingsNavigationTitlesDoNotBroadenHomeSearchMenuOrLongPressAdmission() {
    val nodes = settingsNavigationLabels.keys.mapIndexed { i, title ->
      node(HomeUiDiagnostics.fixedKey(title), top = 20 + i * 20, bottom = 36 + i * 20)
          .copy(longClickable = true)
    }
    val field = node(top = 600, bottom = 650, editable = true)
    val settings = state(nodes + field.copy(searchHint = "searchInstalledApps"))
    assertFalse(settings.eligible())
    assertFalse(settings.dockPresent())
    assertFalse(settings.appMenuPresent())
    assertNull(settings.searchNode())
    val home = state(dock() + nodes + field)
    assertTrue(home.eligible())
    assertFalse(home.appsPageVisible())
    assertFalse(home.toolsLibraryVisible())
    assertNull(home.searchNode())
    nodes.forEach {
      assertFalse(settings.publicLongPressTarget(50.0, it.bounds.top + 1.0))
      assertFalse(home.publicLongPressTarget(50.0, it.bounds.top + 1.0))
    }
    val menu = listOf(node("menuOpen"), node("menuAppInfo"), node("menuMove"), node("menuAddFavorite"))
    assertFalse(state(menu + nodes + field).appMenuPresent())
    val partial = state(nodes, truncated = true)
    assertEquals(0, partial.json().getJSONArray("nodes").length())
    assertFalse(partial.eligible())
    assertNull(partial.searchNode())
  }

  @Test fun settingsNavigationExportNeverDisclosesEditablePasswordUnknownOrValueText() {
    val fields = settingsNavigationLabels.keys.flatMap { title ->
      val key = HomeUiDiagnostics.fixedKey(title)
      assertNull(HomeUiDiagnostics.fixedKey("$title = PRIVATE_VALUE_SENTINEL"))
      listOf(node(key, editable = true).copy(textLength = 137),
          node(key, password = true).copy(searchHint = "PRIVATE_PASSWORD_SENTINEL", textLength = 203))
    }
    val snapshot = state(listOf(node(HomeUiDiagnostics.fixedKey("App appearance")),
        node("PRIVATE_FIELD_SENTINEL"),
        node(editable = true, hint = "searchApps").copy(textLength = 251)) + fields)
    val response = snapshot.json()
    val rows = response.getJSONArray("nodes")
    assertEquals(1, rows.length())
    assertEquals("appAppearance", rows.getJSONObject(0).getString("key"))
    assertEquals(setOf("key", "bounds", "clickable", "longClickable", "focused",
        "accessibilityFocused", "editable"), rows.getJSONObject(0).keys().asSequence().toSet())
    assertFalse(response.getBoolean("eligible"))
    assertFalse(response.getBoolean("focusedEditable"))
    assertNull(snapshot.searchNode())
    for (privateText in listOf("PRIVATE_FIELD_SENTINEL", "PRIVATE_PASSWORD_SENTINEL",
        "PRIVATE_VALUE_SENTINEL", "inputTextLength", "searchApps")) {
      assertFalse(response.toString().contains(privateText))
    }
    assertNull(HomeUiDiagnostics.fixedKey("https://private.invalid/Weather?q=secret"))
    assertNull(HomeUiDiagnostics.fixedKey("PRIVATE_FIELD_SENTINEL"))
  }

  @Test fun truncatedSettingsTreeCannotExportAppearanceRowsOrAuthorizeInput() {
    val snapshot = state(listOf(node(HomeUiDiagnostics.fixedKey("Appearance")),
        node(HomeUiDiagnostics.fixedKey("Standard"))), truncated = true)
    assertEquals(0, snapshot.json().getJSONArray("nodes").length())
    assertFalse(snapshot.eligible())
    assertNull(snapshot.searchNode())
    assertFalse(snapshot.publicLongPressTarget(50.0, 120.0))
  }

  @Test fun healthStatusesAreReadOnlySettingsEvidence() {
    val statuses = mapOf("Checking device permissions" to "deviceHealthChecking",
        "Device health checked" to "deviceHealthChecked",
        "Device health check unavailable" to "deviceHealthUnavailable")
    for ((label, key) in statuses) {
      val snapshot = state(listOf(node(HomeUiDiagnostics.fixedKey(label), left = 300, right = 900)))
      assertFalse(snapshot.eligible())
      assertFalse(snapshot.publicLongPressTarget(350.0, 150.0))
      assertNull(snapshot.searchNode())
      val rows = snapshot.json().getJSONArray("nodes")
      assertEquals(1, rows.length())
      assertEquals(key, rows.getJSONObject(0).getString("key"))
    }
  }

  @Test fun healthEvidenceNeverPublishesEditablePasswordOrPermissionDetails() {
    assertNull(HomeUiDiagnostics.fixedKey("Screen off: granted"))
    assertNull(HomeUiDiagnostics.fixedKey("Device health checked: PRIVATE_VALUE"))
    val key = HomeUiDiagnostics.fixedKey("Device health checked")
    val snapshot = state(listOf(node(key, editable = true), node(key, password = true)))
    assertEquals(0, snapshot.json().getJSONArray("nodes").length())
  }

}

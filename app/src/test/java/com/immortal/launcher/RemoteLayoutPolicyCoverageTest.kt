/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which Home layout edits a phone may push: strict preflight of untrusted layout JSON. */
class RemoteLayoutPolicyCoverageTest {
  private val bound = HomeWidgetStore.HomeWidget(appWidgetId = 7, providerPackage = "com.example.clock", providerClass = "com.example.clock.Provider")

  private fun layout(
      assignments: Any? = JSONObject(),
      homeOrder: Any? = JSONArray(),
      gridOrder: Any? = JSONArray(),
      gridSlots: Any? = JSONArray(),
      widgets: Any? = JSONArray(),
  ) = JSONObject().apply {
    assignments?.let { put("assignments", it) }
    homeOrder?.let { put("homeOrder", it) }
    gridOrder?.let { put("gridOrder", it) }
    gridSlots?.let { put("gridSlots", it) }
    widgets?.let { put("widgets", it) }
  }
  private fun custom(kind: String = "weather", id: String = "$kind:a") =
      JSONObject().put("kind", kind).put("customId", id).put("spanX", 2).put("spanY", 2)
  private fun appWidget(id: Any = 7, pkg: String = "com.example.clock", cls: String = "com.example.clock.Provider") =
      JSONObject().put("kind", "appwidget").put("id", id).put("package", pkg).put("class", cls).put("spanX", 1).put("spanY", 1)
  private fun errors(l: JSONObject, existing: List<HomeWidgetStore.HomeWidget> = listOf(bound)) =
      RemoteLayoutPolicy.validate(l, existing).errors

  @Test fun emptyLayoutIsValidAndMissingSectionsAreEachReported() {
    assertEquals(emptyList<String>(), errors(layout()))
    assertEquals(listOf("assignments_required", "widgets_required", "homeOrder_required", "gridOrder_required", "gridSlots_required"),
        errors(JSONObject()))
  }

  @Test fun unknownTopLevelFieldIsRejected() {
    assertEquals(listOf("unknown_layout_field"), errors(layout().put("shell", "x")))
  }

  @Test fun assignmentsMustMapPackagesToShortText() {
    assertTrue(errors(layout(assignments = JSONObject().put("com.example.app", "Work"))).isEmpty())
    assertTrue(errors(layout(assignments = JSONObject().put("com.example.app", "w".repeat(48)))).isEmpty())
    for (a in listOf(JSONObject().put("not a package", "Work"), JSONObject().put("com.example.app", "w".repeat(49)),
        JSONObject().put("com.example.app", 3), JSONObject().put("com.example.app", "a\tb"))) {
      assertEquals(a.toString(), listOf("invalid_assignment"), errors(layout(assignments = a)))
    }
    val big = JSONObject().apply { repeat(257) { put("com.example.app$it", "x") } }
    assertEquals(listOf("assignments_required"), errors(layout(assignments = big)))
    assertEquals(listOf("assignments_required"), errors(layout(assignments = "com.example.app=x")))
  }

  @Test fun widgetsArrayIsBounded() {
    val many = JSONArray((0..64).map { custom(id = "weather:$it") })
    assertEquals(listOf("widgets_required"), errors(layout(widgets = many)))
    assertTrue(errors(layout(widgets = JSONArray((0 until 64).map { custom(id = "weather:$it") }))).isEmpty())
    assertEquals(listOf("invalid_widget"), errors(layout(widgets = JSONArray().put("weather"))))
  }

  @Test fun widgetFieldsSpansAndExplicitSizeAreChecked() {
    assertEquals(listOf("unknown_widget_field"), errors(layout(widgets = JSONArray().put(custom().put("script", "x")))))
    for ((x, y) in listOf(0 to 1, 5 to 1, 1 to 0, 1 to 5, "2" to 2)) {
      assertEquals("$x,$y", listOf("invalid_span"), errors(layout(widgets = JSONArray().put(custom().put("spanX", x).put("spanY", y)))))
    }
    assertEquals(listOf("invalid_span"), errors(layout(widgets = JSONArray().put(custom().apply { remove("spanY") }))))
    assertEquals(listOf("invalid_explicitSize"), errors(layout(widgets = JSONArray().put(custom().put("explicitSize", "yes")))))
    assertTrue(errors(layout(widgets = JSONArray().put(custom().put("explicitSize", true)))).isEmpty())
  }

  @Test fun slotMustBeCompleteAndIntegral() {
    val ok = custom().put("page", 63).put("column", 0).put("row", 0)
    assertTrue(errors(layout(widgets = JSONArray().put(ok))).isEmpty())
    for (bad in listOf(custom().put("page", 64).put("column", 0).put("row", 0),
        custom().put("page", "0").put("column", 0).put("row", 0),
        custom().put("page", 0).put("column", -1).put("row", 0),
        custom().put("page", 0)))
      assertEquals(listOf("invalid_slot"), errors(layout(widgets = JSONArray().put(bad))))
  }

  @Test fun appWidgetMustMatchAnAlreadyBoundProvider() {
    assertTrue(errors(layout(widgets = JSONArray().put(appWidget()))).isEmpty())
    for (bad in listOf(appWidget(id = 8), appWidget(pkg = "com.attacker.app"), appWidget(cls = "com.example.clock.Other"),
        appWidget(id = "7"), appWidget().put("customId", "weather:a")))
      assertEquals(bad.toString(), listOf("unbound_widget"), errors(layout(widgets = JSONArray().put(bad))))
    // A custom (non-app) widget with the same id is not a binding.
    val notApp = HomeWidgetStore.HomeWidget(appWidgetId = 7, providerPackage = "com.example.clock",
        providerClass = "com.example.clock.Provider", kind = "weather", customId = "weather:x")
    assertEquals(listOf("unbound_widget"), errors(layout(widgets = JSONArray().put(appWidget())), listOf(notApp)))
  }

  @Test fun customWidgetsNeedKnownKindAndPrefixedIdWithoutProviderFields() {
    for (kind in RemoteLayoutPolicy.kinds) assertTrue(kind, errors(layout(widgets = JSONArray().put(custom(kind)))).isEmpty())
    assertTrue(errors(layout(widgets = JSONArray().put(custom().put("id", 0)))).isEmpty())
    val bad = listOf(
        custom(kind = "shell"),
        custom(id = "news:a"),
        custom(id = "   "),
        custom(id = "weather:" + "a".repeat(113)),
        custom().put("customId", 5),
        custom().put("id", 3),
        custom().put("package", "com.example.clock"),
        custom().put("class", "Provider"),
        custom().apply { remove("kind") },
        custom().put("kind", 1),
    )
    for ((i, w) in bad.withIndex()) assertEquals("case $i", listOf("invalid_custom_widget"), errors(layout(widgets = JSONArray().put(w))))
  }

  @Test fun duplicateWidgetsAreRejected() {
    assertEquals(listOf("duplicate_widget"), errors(layout(widgets = JSONArray().put(custom()).put(custom()))))
    assertEquals(listOf("duplicate_widget"), errors(layout(widgets = JSONArray().put(appWidget()).put(appWidget()))))
  }

  @Test fun homeOrderAcceptsPackagesAndFlattenedComponents() {
    for (id in listOf("com.example.app", "com.example.app/.Main", "com.example.app/com.example.app.Main\$Inner", "com.example.app/_Shortcut"))
      assertTrue(id, errors(layout(homeOrder = JSONArray().put(id))).isEmpty())
    for (id in listOf<Any>("", "app", "com.example.app/", "com.example.app/1Main", "com.example.app/a/b", "/com.example.Main",
        "com.example.app/" + "M".repeat(400), 5, JSONObject.NULL))
      assertEquals("$id", listOf("invalid_homeOrder"), errors(layout(homeOrder = JSONArray().put(id))))
    assertEquals(listOf("duplicate_homeOrder"), errors(layout(homeOrder = JSONArray().put("com.example.app").put("com.example.app"))))
  }

  @Test fun gridTilesAcceptAppsFoldersBuiltinsAndOnlyDeclaredWidgetTiles() {
    val widgets = JSONArray().put(custom())
    val tiles = listOf("app:com.example.app", "app:com.example.app/.Main", "folder:Games", "folder:" + "f".repeat(48),
        "widget-tile:custom:weather:a", "builtin:calls", "builtin:control", "builtin:settings")
    assertTrue(errors(layout(gridOrder = JSONArray(tiles), widgets = widgets)).isEmpty())
    for (tile in listOf("app:not valid", "folder:", "folder:  ", "folder:" + "f".repeat(49), "folder:a\nb",
        "widget-tile:custom:weather:missing", "widget-tile:widget:7", "builtin:shell", "intent:#Intent;end", ""))
      assertEquals(tile, listOf("invalid_gridOrder"), errors(layout(gridOrder = JSONArray().put(tile), widgets = widgets)))
    assertEquals(listOf("duplicate_gridOrder"), errors(layout(gridOrder = JSONArray().put("builtin:calls").put("builtin:calls"))))
  }

  @Test fun gridSlotsTolerateEmptyHolesButNotDuplicates() {
    assertTrue(errors(layout(gridSlots = JSONArray().put(JSONObject.NULL).put("").put("builtin:store").put(JSONObject.NULL))).isEmpty())
    assertEquals(listOf("duplicate_gridSlots"), errors(layout(gridSlots = JSONArray().put("builtin:store").put("builtin:store"))))
    assertEquals(listOf("invalid_gridSlots"), errors(layout(gridSlots = JSONArray().put(4))))
    assertEquals(listOf("invalid_gridOrder"), errors(layout(gridOrder = JSONArray().put(JSONObject.NULL))))
  }

  @Test fun orderArraysAreBoundedAndMustBeArrays() {
    val big = JSONArray((0..512).map { "folder:f$it" })
    assertEquals(listOf("gridOrder_required"), errors(layout(gridOrder = big)))
    assertEquals(listOf("homeOrder_required"), errors(layout(homeOrder = "com.example.app")))
  }

  @Test fun packageNameRejectsNonStringsAndOverlongNames() {
    assertTrue(RemoteLayoutPolicy.packageName("a.b"))
    for (v in listOf<Any?>(null, 1, "a", "1a.b", "a..b", "a.b-c", "a." + "b".repeat(199))) assertFalse("$v", RemoteLayoutPolicy.packageName(v))
    assertFalse(RemoteLayoutPolicy.homeId(null))
  }

  @Test fun widgetsNormalizeCustomKindsToZeroIdAndKeepAppWidgetIds() {
    val l = layout(widgets = JSONArray().put(custom()).put(appWidget()))
    val parsed = RemoteLayoutPolicy.widgets(l)
    assertEquals(2, parsed.size)
    assertEquals(0, parsed.first { !it.isAppWidget }.appWidgetId)
    assertEquals("weather:a", parsed.first { !it.isAppWidget }.customId)
    assertEquals(7, parsed.first { it.isAppWidget }.appWidgetId)
  }

  @Test fun validationResultExposesValidity() {
    assertTrue(RemoteCompanionValidation(emptyList()).valid)
    assertFalse(RemoteCompanionValidation(listOf("x")).valid)
  }
}

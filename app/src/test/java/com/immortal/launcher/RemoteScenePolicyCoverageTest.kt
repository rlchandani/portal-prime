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

/** What a phone may put into a scene: wire preflight for hostile or malformed scene payloads. */
class RemoteScenePolicyCoverageTest {
  private val allowAll: (String, JSONObject) -> Boolean = { _, _ -> true }

  private fun step(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }
  private fun scene(steps: JSONArray?, id: Any? = "scene_1", name: Any? = "Morning", extra: Map<String, Any?> = emptyMap()) =
      JSONObject().apply {
        put("id", id ?: JSONObject.NULL)
        put("name", name ?: JSONObject.NULL)
        if (steps != null) put("steps", steps)
        extra.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) }
      }
  private fun one(vararg steps: JSONObject) = JSONArray().apply { steps.forEach { put(it) } }
  private fun errors(scenes: JSONArray, settings: (String, JSONObject) -> Boolean = allowAll) =
      RemoteScenePolicy.validate(scenes, settings).errors
  private fun schedule(enabled: Any? = true, hour: Any? = 7, minute: Any? = 30, days: Any? = JSONArray(listOf(1, 2, 3))) =
      JSONObject().put("enabled", enabled).put("hour", hour).put("minute", minute).put("days", days)
  private val homeKey get() = step("type" to "key", "action" to "home")

  @Test fun acceptsEveryAllowedStepKind() {
    val steps = one(
        step("type" to "settings", "domain" to "display", "values" to JSONObject().put("brightness", 5)),
        step("type" to "key", "action" to "sleep"),
        step("type" to "media", "action" to "next"),
        step("type" to "launch", "packageName" to "com.example.app"),
        step("type" to "source", "values" to JSONObject().put("source", "local").put("folderPath", "/sdcard/DCIM")),
        step("type" to "wait", "ms" to 0),
    )
    assertEquals(emptyList<String>(), errors(one(scene(steps))))
  }

  @Test fun settingsStepIsDelegatedWithItsDomainAndRejectedWhenDelegateRefuses() {
    val seen = mutableListOf<String>()
    val s = step("type" to "settings", "domain" to "audio", "values" to JSONObject().put("volume", 3))
    assertTrue(RemoteScenePolicy.validate(one(scene(one(s)))) { domain, _ -> seen += domain; true }.valid)
    assertEquals(listOf("audio"), seen)
    assertEquals(listOf("invalid_step:0:0"), errors(one(scene(one(s)))) { _, _ -> false })
  }

  @Test fun settingsStepRejectsMissingEmptyOrMistypedFields() {
    val bad = listOf(
        step("type" to "settings", "domain" to "audio", "values" to JSONObject()), // empty values
        step("type" to "settings", "domain" to 7, "values" to JSONObject().put("a", 1)), // non-string domain
        step("type" to "settings", "domain" to "audio"), // missing values
        step("type" to "settings", "domain" to "audio", "values" to "volume=3"), // values not an object
        step("type" to "settings", "domain" to "audio", "values" to JSONObject().put("a", 1), "shell" to "rm"),
    )
    var calls = 0
    for ((i, s) in bad.withIndex()) {
      assertEquals("case $i", listOf("invalid_step:0:0"), errors(one(scene(one(s)))) { _, _ -> calls++; true })
    }
    assertEquals("delegate is only consulted for well-formed values", 0, calls)
  }

  @Test fun keyMediaAndLaunchStepsOnlyAcceptKnownActionsAndPackages() {
    val bad = listOf(
        step("type" to "key", "action" to "power"),
        step("type" to "key", "action" to "home", "extra" to 1),
        step("type" to "key"),
        step("type" to "media", "action" to "stop"),
        step("type" to "media", "action" to 1),
        step("type" to "launch", "packageName" to "not a package"),
        step("type" to "launch", "packageName" to "com.example; rm -rf /"),
        step("type" to "launch"),
        step("type" to "launch", "packageName" to "com.example.app", "activity" to "Main"),
        step("type" to 5),
        step(),
    )
    for ((i, s) in bad.withIndex()) assertEquals("case $i", listOf("invalid_step:0:0"), errors(one(scene(one(s)))))
    for (action in RemoteScenePolicy.media) assertTrue(errors(one(scene(one(step("type" to "media", "action" to action))))).isEmpty())
    for (action in RemoteScenePolicy.keys) assertTrue(errors(one(scene(one(step("type" to "key", "action" to action))))).isEmpty())
  }

  @Test fun nonObjectStepIsInvalidButOtherStepsStillChecked() {
    val steps = JSONArray().put("home").put(homeKey).put(JSONObject.NULL)
    assertEquals(listOf("invalid_step:0:0", "invalid_step:0:2"), errors(one(scene(steps))))
  }

  @Test fun waitStepsMustBeIntegralBoundedAndShareOneBudget() {
    for (ms in listOf<Any>(-1, 1.5, "100", JSONObject.NULL)) {
      assertEquals("ms=$ms", listOf("invalid_step:0:0"), errors(one(scene(one(step("type" to "wait", "ms" to ms))))))
    }
    // An over-long single wait is both an invalid step and over the scene budget.
    for (ms in listOf<Any>(5001, 10_000_000_000L)) {
      assertEquals("ms=$ms", listOf("invalid_step:0:0", "wait_budget_exceeded"),
          errors(one(scene(one(step("type" to "wait", "ms" to ms))))))
    }
    assertTrue(errors(one(scene(one(step("type" to "wait", "ms" to 5000L))))).isEmpty())
    val split = one(step("type" to "wait", "ms" to 3000), step("type" to "wait", "ms" to 2000))
    assertTrue(errors(one(scene(split))).isEmpty())
    val over = one(step("type" to "wait", "ms" to 3000), step("type" to "wait", "ms" to 2001))
    assertEquals(listOf("wait_budget_exceeded"), errors(one(scene(over))))
    assertEquals(listOf("invalid_step:0:0"), errors(one(scene(one(step("type" to "wait", "ms" to 1, "x" to 1))))))
  }

  @Test fun stepCountMustBeBetweenOneAndSixteen() {
    assertEquals(listOf("invalid_steps"), errors(one(scene(JSONArray()))))
    assertEquals(listOf("invalid_steps"), errors(one(scene(JSONArray((1..17).map { homeKey })))))
    assertTrue(errors(one(scene(JSONArray((1..16).map { homeKey })))).isEmpty())
    assertEquals(listOf("invalid_steps"), errors(one(scene(null))))
    assertEquals(listOf("invalid_steps"), errors(one(scene(null, extra = mapOf("steps" to "home")))))
  }

  @Test fun sceneIdentityRejectsBadIdsNamesUnknownFieldsAndDuplicates() {
    val steps = one(homeKey)
    val cases = listOf(
        scene(steps, id = 12),
        scene(steps, id = ""),
        scene(steps, id = "has space"),
        scene(steps, id = "a".repeat(65)),
        scene(steps, name = ""),
        scene(steps, name = "   "),
        scene(steps, name = "a".repeat(65)),
        scene(steps, name = "line\nbreak"),
        scene(steps, name = 42),
        scene(steps, name = null),
        scene(steps, extra = mapOf("script" to "x")),
    )
    for ((i, s) in cases.withIndex()) assertEquals("case $i", listOf("invalid_scene_identity"), errors(one(s)))
    assertTrue(errors(one(scene(steps, id = "A-z_0".padEnd(64, '9'), name = "n".repeat(64)))).isEmpty())
    assertEquals(listOf("invalid_scene_identity"), errors(JSONArray().put(scene(steps, id = "same")).put(scene(steps, id = "same", name = "Other"))))
  }

  @Test fun nonObjectScenesAndTooManyScenesAreRejected() {
    assertEquals(listOf("invalid_scene"), errors(JSONArray().put("scene").put(scene(one(homeKey)))))
    val many = JSONArray((0..20).map { scene(one(homeKey), id = "s$it") })
    assertEquals(listOf("too_many_scenes"), errors(many))
    assertTrue(errors(JSONArray((0 until 20).map { scene(one(homeKey), id = "s$it") })).isEmpty())
  }

  @Test fun errorsAreDistinctAcrossScenes() {
    val bad = JSONArray().put(scene(JSONArray(), id = "a")).put(scene(JSONArray(), id = "b"))
    assertEquals(listOf("invalid_steps"), errors(bad))
  }

  @Test fun scheduleIsOptionalNullableAndOtherwiseStrict() {
    val steps = one(homeKey)
    assertTrue(errors(one(scene(steps, extra = mapOf("schedule" to schedule())))).isEmpty())
    assertTrue(errors(one(scene(steps, extra = mapOf("schedule" to null)))).isEmpty())
    assertEquals(listOf("invalid_schedule"), errors(one(scene(steps, extra = mapOf("schedule" to "07:30")))))
  }

  @Test fun validScheduleBoundaries() {
    assertTrue(RemoteScenePolicy.validSchedule(schedule(hour = 0, minute = 0, days = JSONArray(listOf(7)))))
    assertTrue(RemoteScenePolicy.validSchedule(schedule(enabled = false, hour = 23, minute = 59, days = JSONArray((1..7).toList()))))
    val bad = listOf(
        null,
        schedule(enabled = "true"),
        schedule(hour = -1), schedule(hour = 24), schedule(hour = "7"), schedule(hour = 7.5),
        schedule(minute = -1), schedule(minute = 60),
        schedule(days = JSONArray()),
        schedule(days = JSONArray(listOf(0))), schedule(days = JSONArray(listOf(8))),
        schedule(days = JSONArray(listOf(1, 1))),
        schedule(days = JSONArray(listOf("mon"))),
        schedule(days = JSONArray(listOf(1, 2, 3, 4, 5, 6, 7, 1))),
        schedule(days = "1,2"),
        schedule().apply { remove("days") },
        schedule().apply { remove("hour") },
        schedule().put("timezone", "UTC"),
    )
    for ((i, s) in bad.withIndex()) assertFalse("case $i", RemoteScenePolicy.validSchedule(s))
  }

  @Test fun sourceValuesEnforceFolderRootAndNoTraversal() {
    fun src(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }
    for (s in RemoteScenePolicy.sources - setOf("folder", "local")) assertTrue(s, RemoteScenePolicy.validSource(src("source" to s)))
    assertTrue(RemoteScenePolicy.validSource(src("source" to "local", "folderPath" to "/sdcard/")))
    val bad = listOf(
        null,
        src(),
        src("source" to "folder"),
        src("source" to "local", "folderPath" to "/data/data/x"),
        src("source" to "folder", "folderPath" to "/sdcard/a/../../data"),
        src("source" to "folder", "folderPath" to "/sdcard/" + "a".repeat(505)),
        src("source" to "folder", "folderPath" to "/sdcard/a\u0000b"),
        src("source" to "folder", "folderPath" to 5),
        src("source" to "web", "folderPath" to "/sdcard/x"),
        src("source" to 3),
        src("source" to null),
        src("source" to "album", "albumUrl" to "https://example.invalid"),
    )
    for ((i, s) in bad.withIndex()) assertFalse("case $i", RemoteScenePolicy.validSource(s))
    assertTrue(RemoteScenePolicy.validSource(src("source" to "folder", "folderPath" to "/sdcard/" + "a".repeat(504))))
  }

  @Test fun validIdOnlyAcceptsShortSafeStrings() {
    assertTrue(RemoteScenePolicy.validId("ok_ID-1"))
    for (id in listOf(null, 1, "", "a/b", "a.b", "é", "a".repeat(65))) assertFalse("$id", RemoteScenePolicy.validId(id))
  }
}

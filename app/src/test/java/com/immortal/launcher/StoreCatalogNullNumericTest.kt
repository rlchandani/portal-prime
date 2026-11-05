/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreCatalogNullNumericTest {
    private fun app(name: String) = JSONObject()
        .put("name", name).put("packageName", "org.example.$name")
        .put("description", "Generated catalog entry")

    private fun category(name: String, vararg apps: JSONObject) =
        JSONObject().put("name", name).put("apps", JSONArray(apps.toList()))

    private fun catalog(vararg categories: JSONObject) =
        JSONObject().put("schemaVersion", 2).put("categories", JSONArray(categories.toList())).toString()

    private fun accepted(json: String): List<CatalogApp> {
        val result = runCatching { StoreCatalog.parse(json) }
        assertTrue("Schema-valid optional numeric null must parse", result.isSuccess)
        return result.getOrThrow()
    }

    @Test fun explicitNullMinSdkIsAbsent() {
        val parsed = accepted(catalog(category("Media", app("photo").put("minSdk", JSONObject.NULL)))).single()
        assertNull(parsed.minSdk)
        assertEquals("org.example.photo", parsed.packageName)
    }

    @Test fun explicitNullVersionCodeIsAbsent() {
        val parsed = accepted(catalog(category("Media", app("photo").put("versionCode", JSONObject.NULL)))).single()
        assertNull(parsed.versionCode)
        assertEquals("org.example.photo", parsed.packageName)
    }

    @Test fun bothNullFieldsKeepSurroundingAppsInOrder() {
        val parsed = accepted(catalog(category("Media", app("first"),
            app("middle").put("minSdk", JSONObject.NULL).put("versionCode", JSONObject.NULL),
            app("last").put("minSdk", 28).put("versionCode", 47))))
        assertEquals(listOf("first", "middle", "last"), parsed.map { it.name })
        assertNull(parsed[1].minSdk)
        assertNull(parsed[1].versionCode)
        assertEquals(28, parsed[2].minSdk)
        assertEquals(47L, parsed[2].versionCode)
    }

    @Test fun numericNullInLaterCategoryKeepsEveryCategory() {
        val parsed = accepted(catalog(category("Media", app("first")),
            category("Tools", app("last").put("versionCode", JSONObject.NULL))))
        assertEquals(listOf("first", "last"), parsed.map { it.name })
        assertEquals(listOf("Media", "Tools"), parsed.map { it.category })
        assertNull(parsed[1].versionCode)
    }

    @Test fun absentNumericFieldsKeepTheirDefaults() {
        val parsed = accepted(catalog(category("Media", app("photo")))).single()
        assertNull(parsed.minSdk)
        assertNull(parsed.versionCode)
        assertEquals("fdroid", parsed.source)
    }

    @Test fun integerNumericFieldsKeepTheirValues() {
        val parsed = accepted(catalog(category("Media",
            app("photo").put("minSdk", 29).put("versionCode", 47)))).single()
        assertEquals(29, parsed.minSdk)
        assertEquals(47L, parsed.versionCode)
    }

    @Test fun longVersionCodeKeepsItsPrecision() {
        val parsed = accepted(catalog(category("Media",
            app("photo").put("versionCode", 4_294_967_296L)))).single()
        assertEquals(4_294_967_296L, parsed.versionCode)
    }

    @Test fun existingNumericStringGetterCoercionIsPreserved() {
        val parsed = accepted(catalog(category("Media",
            app("photo").put("minSdk", "28").put("versionCode", "47")))).single()
        assertEquals(28, parsed.minSdk)
        assertEquals(47L, parsed.versionCode)
    }

    @Test fun invalidNonNullVersionCodeStillRejectsTheCatalog() {
        assertThrows(JSONException::class.java) {
            StoreCatalog.parse(catalog(category("Media",
                app("photo").put("versionCode", "not-a-number"))))
        }
    }

    @Test fun invalidNonNullMinSdkStillRejectsTheCatalog() {
        assertThrows(JSONException::class.java) {
            StoreCatalog.parse(catalog(category("Media",
                app("photo").put("minSdk", "not-a-number"))))
        }
    }
}

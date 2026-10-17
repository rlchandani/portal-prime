/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import java.util.Calendar
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class IssPredictionOutcomeTest {
  private fun prefs(latitude: String = "90.0", validOrbit: Boolean = true): ScreensaverTestPrefs {
    val now = Calendar.getInstance()
    val epoch = String.format(Locale.US, "%02d%03d.50000000", now.get(Calendar.YEAR) % 100,
        now.get(Calendar.DAY_OF_YEAR))
    return ScreensaverTestPrefs(mapOf("lat" to latitude, "lon" to "0.0", "resolved_at" to now.timeInMillis,
        "tle_time" to now.timeInMillis,
        "tle1" to if (validOrbit) "1 25544U 98067A   $epoch  .00016717  00000-0  30000-3 0  9007" else "invalid",
        "tle2" to "2 25544  51.6400 208.0000 0006703 130.0000 325.0000 15.50000000    07")).also { prefs ->
      for (name in listOf("immortal_weather", "immortal_iss"))
        org.mockito.Mockito.`when`(prefs.context.getSharedPreferences(name, Context.MODE_PRIVATE)).thenReturn(prefs)
    }
  }

  private fun outcome(context: Context): Any {
    val method = IssPasses::class.java.methods.firstOrNull { it.name == "predictStatus" && it.parameterCount == 3 }
    assertNotNull("Prediction must expose successful empty versus unavailable outcomes", method)
    return requireNotNull(method!!.invoke(IssPasses, context, 5, 10.0))
  }
  private fun problem(result: Any): String? = result.javaClass.getMethod("getProblem").invoke(result)?.toString()
  @Suppress("UNCHECKED_CAST")
  private fun passes(result: Any) = result.javaClass.getMethod("getPasses").invoke(result) as List<IssPasses.Pass>

  @Test fun entirelyFailedPropagation_isNotSuccessfulEmpty() {
    val satellite = org.mockito.Mockito.mock(IssPasses.Sat::class.java)
    val scan = IssPasses::class.java.getDeclaredMethod("scan", IssPasses.Sat::class.java,
        Double::class.javaPrimitiveType, Double::class.javaPrimitiveType,
        Int::class.javaPrimitiveType, Double::class.javaPrimitiveType).apply { isAccessible = true }
    try {
      scan.invoke(IssPasses, satellite, 90.0, 0.0, 5, 10.0)
      fail("A scan with no usable orbital samples must report calculation failure")
    } catch (failure: java.lang.reflect.InvocationTargetException) {
      assertTrue(failure.cause is IllegalStateException)
    }
  }

  @Test fun validPolarOrbitScan_isSuccessfulEmptyWithoutCacheWrites() {
    val prefs = prefs()
    val result = outcome(prefs.context)
    assertNull(problem(result))
    assertTrue(passes(result).isEmpty())
    assertEquals(0, prefs.writeCount)
  }
  @Test fun unreadableFreshOrbit_isUnavailableRatherThanSuccessfulEmpty() {
    val prefs = prefs(validOrbit = false)
    val result = outcome(prefs.context)
    assertEquals("ORBIT", problem(result))
    assertTrue(passes(result).isEmpty())
    assertEquals(0, prefs.writeCount)
    assertTrue(IssPasses.predict(prefs.context).isEmpty())
  }
  @Test fun populatedScan_preservesPassesAndLegacyListContract() {
    val prefs = prefs(latitude = "0.0")
    val result = outcome(prefs.context)
    assertNull(problem(result))
    assertFalse(passes(result).isEmpty())
    assertTrue(passes(result).all { it.startMillis <= it.peakMillis && it.peakMillis <= it.endMillis })
    assertEquals(passes(result).size, IssPasses.predict(prefs.context).size)
    assertEquals(0, prefs.writeCount)
  }
}

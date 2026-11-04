/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.MutableState
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Actual compiled private dialog callbacks, with generated state and no composed UI or Context. */
class LampNumberStepTest {
  @Test fun brightnessMaximumIntPlusChooses100() =
      step(ApprovedLampControl.BRIGHTNESS, "2147483647", plus = true, expected = "100")

  @Test fun brightnessMinimumIntMinusChooses5() =
      step(ApprovedLampControl.BRIGHTNESS, "-2147483648", plus = false, expected = "5")

  @Test fun brightness90PlusChooses91() =
      step(ApprovedLampControl.BRIGHTNESS, "90", plus = true, expected = "91")

  @Test fun brightness5MinusRetains5() =
      step(ApprovedLampControl.BRIGHTNESS, "5", plus = false, expected = "5")

  @Test fun brightness100PlusRetains100() =
      step(ApprovedLampControl.BRIGHTNESS, "100", plus = true, expected = "100")

  @Test fun brightnessBlankPlusChooses6() =
      step(ApprovedLampControl.BRIGHTNESS, "", plus = true, expected = "6")

  @Test fun warmthMaximumIntPlusChooses100() =
      step(ApprovedLampControl.WARMTH, "2147483647", plus = true, expected = "100")

  @Test fun warmthMinimumIntMinusChooses0() =
      step(ApprovedLampControl.WARMTH, "-2147483648", plus = false, expected = "0")

  @Test fun warmth90PlusChooses91() =
      step(ApprovedLampControl.WARMTH, "90", plus = true, expected = "91")

  @Test fun warmth0MinusRetains0() =
      step(ApprovedLampControl.WARMTH, "0", plus = false, expected = "0")

  @Test fun warmth100PlusRetains100() =
      step(ApprovedLampControl.WARMTH, "100", plus = true, expected = "100")

  @Test fun warmthBlankPlusChooses1() =
      step(ApprovedLampControl.WARMTH, "", plus = true, expected = "1")

  private fun step(control: ApprovedLampControl, input: String, plus: Boolean, expected: String) {
    val draft = TrackingState(input)
    val error = TrackingState<String?>("Previous validation error")
    // These two verified compiled names bind to the actual private minus/plus click callbacks.
    // Never call stepSettingNumber directly or copy the dialog arithmetic into this fixture.
    val name = if (plus) PLUS_CALLBACK else MINUS_CALLBACK
    val callback = Class.forName("com.immortal.launcher.LampActivityKt").getDeclaredMethod(
        name, ApprovedLampControl::class.java, MutableState::class.java, MutableState::class.java)
    callback.isAccessible = true
    try {
      callback.invoke(null, control, draft, error)
    } catch (failure: InvocationTargetException) {
      throw failure.targetException
    }
    assertEquals("One draft publication", 1, draft.writes)
    assertEquals("One validation-error publication", 1, error.writes)
    assertNull("A step clears the previous error", error.value)
    assertEquals("Actual callback draft", expected, draft.value)
  }

  private class TrackingState<T>(initial: T) : MutableState<T> {
    private var current = initial
    var writes = 0
      private set
    override var value: T
      get() = current
      set(value) {
        current = value
        writes++
      }
    override fun component1(): T = value
    override fun component2(): (T) -> Unit = { value = it }
  }

  private companion object {
    const val MINUS_CALLBACK =
        "LampControlDialog\$lambda\$106\$lambda\$105\$lambda\$104\$lambda\$100\$lambda\$97\$lambda\$96"
    const val PLUS_CALLBACK =
        "LampControlDialog\$lambda\$106\$lambda\$105\$lambda\$104\$lambda\$100\$lambda\$99\$lambda\$98"
  }
}

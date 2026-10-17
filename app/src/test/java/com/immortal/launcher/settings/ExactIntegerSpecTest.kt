/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher.settings

import android.content.Context
import java.math.BigDecimal
import java.math.BigInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Exercise actual spec/domain writes rather than a duplicate numeric parser. */
class ExactIntegerSpecTest {
  private val context = Mockito.mock(Context::class.java)

  @Test fun integralNumbersAcrossSupportedTypesReachTheSetterExactly() {
    val fixture = SpecFixture(min = 5, max = 600)
    val numbers = listOf<Pair<Number, Int>>(
        5.toByte() to 5, 5.toShort() to 5, 5 to 5, 5L to 5,
        5.0f to 5, 5.0 to 5, BigInteger("5") to 5, BigDecimal("5.000") to 5,
        600 to 600, 600L to 600, 600.0f to 600, 600.0 to 600,
        BigInteger("600") to 600, BigDecimal("6E+2") to 600,
    )
    numbers.forEach { (number, expected) ->
      assertTrue("$number (${number.javaClass.simpleName})", fixture.apply(number))
      assertEquals(expected, fixture.writes.last())
    }
    assertEquals(numbers.size, fixture.writes.size)
  }

  @Test fun exactIntegerLimitsRemainAcceptedWithinAdvertisedBounds() {
    val fixture = SpecFixture()
    val values = listOf(
        Int.MIN_VALUE to Int.MIN_VALUE,
        Int.MAX_VALUE to Int.MAX_VALUE,
        Int.MIN_VALUE.toLong() to Int.MIN_VALUE,
        Int.MAX_VALUE.toLong() to Int.MAX_VALUE,
        Int.MIN_VALUE.toDouble() to Int.MIN_VALUE,
        Int.MAX_VALUE.toDouble() to Int.MAX_VALUE,
        1_000_000_064f to 1_000_000_064,
        -1_000_000_064f to -1_000_000_064,
        2_147_483_520f to 2_147_483_520,
        -2_147_483_520f to -2_147_483_520,
        Int.MIN_VALUE.toFloat() to Int.MIN_VALUE,
        BigInteger("-2147483648") to Int.MIN_VALUE,
        BigInteger("2147483647") to Int.MAX_VALUE,
        BigDecimal("-2147483648.000") to Int.MIN_VALUE,
        BigDecimal("2147483647.000") to Int.MAX_VALUE,
    )
    values.forEach { (value, expected) ->
      assertTrue(fixture.apply(value))
      assertEquals(expected, fixture.writes.last())
    }
    assertEquals(values.size, fixture.writes.size)
  }

  @Test fun fractionalNumbersCannotBeTruncatedIntoValidIntegers() {
    assertRejected(listOf(
        5.9, 600.9, -5.9, -600.9, 5.9f, 600.9f, -5.9f,
        Int.MAX_VALUE.toDouble() - 0.5, Int.MAX_VALUE.toDouble() + 0.5,
        Int.MIN_VALUE.toDouble() - 0.5, Int.MIN_VALUE.toDouble() + 0.5,
        BigDecimal("5.9"), BigDecimal("-5.9"),
        BigDecimal("5.0000000000000000000000000001"),
    ))
  }

  @Test fun tinyNonzeroFractionsCannotUnderflowIntoZero() {
    assertRejected(listOf(
        Float.MIN_VALUE, -Float.MIN_VALUE,
        Double.MIN_VALUE, -Double.MIN_VALUE,
        1E-300, BigDecimal("1E-10000"), BigDecimal("-1E-10000"),
    ))
  }

  @Test fun wideLongsCannotOverflowOrWrapIntoValidIntegers() {
    assertRejected(listOf(
        4_294_967_301L, -4_294_967_301L, Long.MIN_VALUE, Long.MAX_VALUE,
        Int.MAX_VALUE.toLong() + 1L, Int.MIN_VALUE.toLong() - 1L,
        2_147_483_648f, -2_147_483_904f,
    ))
  }

  @Test fun wideBigNumbersCannotBeNarrowedIntoTheIntegerRange() {
    assertRejected(listOf(
        BigInteger("2147483648"), BigInteger("-2147483649"),
        BigInteger("4294967301"), BigInteger("999999999999999999999999999999"),
        BigDecimal("2147483648.000"), BigDecimal("-2147483649.000"),
        BigDecimal("4294967301.0"), BigDecimal("1E+10000"),
    ))
  }

  @Test fun boundedSpecRejectsFractionalAndOverflowInputsThatPreviouslyLookedInRange() {
    val fixture = SpecFixture(min = 5, max = 600)
    listOf<Number>(5.9, 600.9, 5.9f, 600.9f, 4_294_967_301L).forEach {
      assertFalse("Must reject $it without writing a narrowed value", fixture.apply(it))
    }
    assertTrue(fixture.writes.isEmpty())
  }

  @Test fun existingBoundsAndUiOnlyStepBehaviorRemainUnchanged() {
    val fixture = SpecFixture(min = 5, max = 600, step = 5)
    listOf(5, 6, 600).forEach { assertTrue(fixture.apply(it)) }
    assertEquals(listOf(5, 6, 600), fixture.writes)
    assertFalse(fixture.apply(4))
    assertFalse(fixture.apply(601))
    assertEquals(listOf(5, 6, 600), fixture.writes)
  }

  @Test fun trimmedIntegerStringsRemainAcceptedWithoutAcceptingDecimalStrings() {
    val fixture = SpecFixture()
    listOf(
        " \t5\n" to 5, " +600 " to 600,
        " -2147483648 " to Int.MIN_VALUE, "2147483647" to Int.MAX_VALUE,
    ).forEach { (value, expected) ->
      assertTrue(fixture.apply(value))
      assertEquals(expected, fixture.writes.last())
    }
    val before = fixture.writes.toList()
    listOf("5.0", "5.9", "1e1", "4294967301", "", "garbage").forEach {
      assertFalse(fixture.apply(it))
    }
    assertEquals(before, fixture.writes)
  }

  @Test fun wrappingSpecStillAcceptsRepresentableValuesOutsideItsDomainBounds() {
    val fixture = SpecFixture(min = 0, max = 1439, wrap = true)
    listOf(-1, 1440, Int.MIN_VALUE, Int.MAX_VALUE).forEach {
      assertTrue(fixture.apply(it))
    }
    assertEquals(listOf(-1, 1440, Int.MIN_VALUE, Int.MAX_VALUE), fixture.writes)
  }

  @Test fun wrappingSpecCannotBypassExactNumberValidation() {
    val fixture = SpecFixture(min = 0, max = 1439, wrap = true)
    listOf<Number>(
        -1.5, 5.9, 600.9f, Float.MIN_VALUE, BigDecimal("1E-10000"),
        4_294_967_301L, Long.MIN_VALUE, Long.MAX_VALUE,
        BigInteger("2147483648"), BigDecimal("1E+10000"),
    ).forEach { assertFalse("Wrap must not admit $it", fixture.apply(it)) }
    assertTrue(fixture.writes.isEmpty())
  }

  @Test fun nonfiniteNumbersSlippingThroughOptAreRejectedBeforeTheSetter() {
    val fixture = SpecFixture()
    listOf<Number>(
        Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
        Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
    ).forEach { number ->
      // JSONObject.put rejects these upstream. Opt interception exercises the actual spec guard.
      val body = Mockito.mock(JSONObject::class.java)
      Mockito.`when`(body.has("value")).thenReturn(true)
      Mockito.`when`(body.opt("value")).thenReturn(number)
      assertFalse("$number must not reach the setter", fixture.spec.applyFrom(context, body))
    }
    assertTrue(fixture.writes.isEmpty())
  }

  @Test fun aliasesAcceptExactInputsAndRejectFractionalNumbers() {
    val fixture = SpecFixture(min = 5, max = 600)
    assertTrue(fixture.spec.applyFrom(context, JSONObject().put("legacy", 5.0)))
    assertTrue(fixture.spec.applyFrom(context, JSONObject().put("legacy", " 600 ")))
    assertFalse(fixture.spec.applyFrom(context, JSONObject().put("legacy", 5.9)))
    assertEquals(listOf(5, 600), fixture.writes)
  }

  @Test fun allInvalidDomainKeysProduceNoSettersAndNoSideEffectHook() {
    val fixture = DomainFixture()
    val applied = fixture.domain.apply(context, JSONObject()
        .put("first", 5.9)
        .put("legacySecond", 4_294_967_301L)
        .put("third", BigDecimal("1E-10000"))
        .put("unknown", 5))
    assertTrue(applied.isEmpty())
    assertTrue(fixture.writes.isEmpty())
    assertTrue(fixture.hooks.isEmpty())
  }

  @Test fun mixedDomainBatchKeepsPartialApplyAndHooksOnceWithCanonicalKeys() {
    val fixture = DomainFixture()
    val applied = fixture.domain.apply(context, JSONObject()
        .put("legacyFirst", 5.0)
        .put("second", 600.9)
        .put("legacyThird", " 600 ")
        .put("unknown", 5))
    assertEquals(setOf("first", "third"), applied)
    assertEquals(listOf("first" to 5, "third" to 600), fixture.writes)
    assertEquals(listOf(setOf("first", "third")), fixture.hooks)
  }

  @Test fun invalidCanonicalValueDoesNotFallBackToAnOtherwiseValidAlias() {
    val fixture = DomainFixture()
    val applied = fixture.domain.apply(context,
        JSONObject().put("first", 5.9).put("legacyFirst", 5))
    assertTrue(applied.isEmpty())
    assertTrue(fixture.writes.isEmpty())
    assertTrue(fixture.hooks.isEmpty())
  }

  private fun assertRejected(values: List<Number>) {
    val fixture = SpecFixture()
    values.forEach { assertFalse("Must reject $it (${it.javaClass.simpleName})", fixture.apply(it)) }
    assertTrue("Rejected numeric inputs must never invoke the setter", fixture.writes.isEmpty())
  }

  private inner class SpecFixture(
      min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE, step: Int = 1, wrap: Boolean = false,
  ) {
    val writes = mutableListOf<Int>()
    val spec = IntSpec<Int>(
        "value", "Public integer", get = { it }, set = { _, value -> writes.add(value) },
        min = min, max = max, step = step, wrap = wrap, aliases = listOf("legacy"),
    )
    fun apply(value: Any): Boolean = spec.applyFrom(context, JSONObject().put("value", value))
  }

  private class DomainFixture {
    val writes = mutableListOf<Pair<String, Int>>()
    val hooks = mutableListOf<Set<String>>()
    val domain = SettingsDomain<Int>(
        "public-test", "Public integer settings", load = { 0 },
        specs = listOf(
            IntSpec<Int>("first", "First", get = { it }, set = { _, v -> writes.add("first" to v) },
                min = 5, max = 600, aliases = listOf("legacyFirst")),
            IntSpec<Int>("second", "Second", get = { it }, set = { _, v -> writes.add("second" to v) },
                min = 5, max = 600, aliases = listOf("legacySecond")),
            IntSpec<Int>("third", "Third", get = { it }, set = { _, v -> writes.add("third" to v) },
                min = 5, max = 600, aliases = listOf("legacyThird")),
        ),
        onApplied = { _, keys -> hooks.add(keys.toSet()) },
    )
  }
}

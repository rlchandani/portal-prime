/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class GreetingRendered(val time: String, val day: String, val date: String, val hour: Int)

/** Eight frozen literal vectors against the actual source-captured retained leaf. */
fun main() {
  val savedZone = TimeZone.getDefault()
  val savedLocale = Locale.getDefault()
  val savedFormat = Locale.getDefault(Locale.Category.FORMAT)
  val savedDisplay = Locale.getDefault(Locale.Category.DISPLAY)
  val now = Date(1793822400000L) // 2026-11-04T20:00:00Z
  val failures = mutableListOf<String>()
  var count = 0
  fun zone(id: String) { TimeZone.setDefault(TimeZone.getTimeZone(id)) }
  fun verify(name: String, initial: String, target: String, use24: Boolean,
      expected: GreetingRendered, intermediate: String? = null) {
    count++
    try {
      zone(initial)
      val retained = ActualGreetingLeaf(use24)
      if (intermediate != null) {
        zone(intermediate)
        retained.render(now) // Exercise the retained object before returning its zone.
      }
      zone(target)
      val actual = retained.render(now)
      println("CASE\t$name\texpected=$expected\tactual=$actual\tpass=${actual == expected}")
      if (actual != expected) failures.add("$name: expected=$expected actual=$actual")
    } catch (error: Throwable) {
      failures.add("$name: ${error.javaClass.name}: ${error.message}")
      println("CASE\t$name\tpass=false\terror=${error.javaClass.name}")
    }
  }
  try {
    Locale.setDefault(Locale.US)
    Locale.setDefault(Locale.Category.FORMAT, Locale.US)
    Locale.setDefault(Locale.Category.DISPLAY, Locale.US)
    verify("forward-12h", "UTC", "GMT+05:30", false,
        GreetingRendered("1:30", "Thursday", "November 5", 1))
    verify("forward-24h", "UTC", "GMT+05:30", true,
        GreetingRendered("1:30", "Thursday", "November 5", 1))
    verify("reverse-12h", "GMT+05:30", "UTC", false,
        GreetingRendered("8:00", "Wednesday", "November 4", 20))
    verify("reverse-24h", "GMT+05:30", "UTC", true,
        GreetingRendered("20:00", "Wednesday", "November 4", 20))
    verify("same-utc-12h", "UTC", "UTC", false,
        GreetingRendered("8:00", "Wednesday", "November 4", 20))
    verify("same-utc-24h", "UTC", "UTC", true,
        GreetingRendered("20:00", "Wednesday", "November 4", 20))
    verify("return-utc-24h", "UTC", "UTC", true,
        GreetingRendered("20:00", "Wednesday", "November 4", 20), "GMT+05:30")
    verify("fresh-india-12h", "GMT+05:30", "GMT+05:30", false,
        GreetingRendered("1:30", "Thursday", "November 5", 1))
  } finally {
    TimeZone.setDefault(savedZone)
    Locale.setDefault(savedLocale)
    Locale.setDefault(Locale.Category.FORMAT, savedFormat)
    Locale.setDefault(Locale.Category.DISPLAY, savedDisplay)
  }
  check(count == 8)
  check(TimeZone.getDefault() == savedZone)
  check(Locale.getDefault() == savedLocale)
  check(Locale.getDefault(Locale.Category.FORMAT) == savedFormat)
  check(Locale.getDefault(Locale.Category.DISPLAY) == savedDisplay)
  println("RESULT\tcases=$count\tfailures=${failures.size}\tdefaultsRestored=true")
  if (failures.isNotEmpty()) throw AssertionError(failures.joinToString("\n"))
}

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.Locale
import java.util.SimpleTimeZone
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class CalendarPreparedFeedTest {
  private fun events(source: String) = listOf(CalendarFeed.Event(10, 20, source, false))
  private fun reader(calls: AtomicInteger, clock: AtomicLong = AtomicLong(),
      prepare: (String, TimeZone, Locale) -> List<CalendarFeed.Event> = { s, _, _ -> calls.incrementAndGet(); events(s) },
      retain: (List<CalendarFeed.Event>) -> Boolean = { true }) = CalendarPreparedFeed(
          prepare, { rows, now, days -> rows.filter { it.endMillis >= now &&
              it.startMillis <= now + days * 86_400_000L } }, retain, clock::get)
  private fun <T> defaults(block: () -> T): T {
    val zone = TimeZone.getDefault(); val locale = Locale.getDefault()
    val format = Locale.getDefault(Locale.Category.FORMAT); val display = Locale.getDefault(Locale.Category.DISPLAY)
    try { TimeZone.setDefault(TimeZone.getTimeZone("UTC")); Locale.setDefault(Locale.US); return block() }
    finally { TimeZone.setDefault(zone); Locale.setDefault(locale)
      Locale.setDefault(Locale.Category.FORMAT,format); Locale.setDefault(Locale.Category.DISPLAY,display) }
  }

  @Test fun eightConcurrentReadersPrepareOnce() {
    val calls=AtomicInteger(); val entered=CountDownLatch(1); val release=CountDownLatch(1)
    val model=reader(calls, prepare={ s, _, _ -> calls.incrementAndGet(); entered.countDown()
      check(release.await(2,TimeUnit.SECONDS)); events(s) })
    val pool=Executors.newFixedThreadPool(8)
    try {
      val results=(1..8).map { pool.submit<List<CalendarFeed.Event>> { model.parse("a",0,1) } }
      assertTrue(entered.await(1,TimeUnit.SECONDS)); release.countDown()
      results.forEach { assertEquals(events("a"),it.get(2,TimeUnit.SECONDS)) }; assertEquals(1,calls.get())
    } finally { release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS)) }
  }
  @Test fun twentyWarmReadersPrepareOnce() {
    val calls=AtomicInteger();val model=reader(calls)
    repeat(20) { assertEquals(events("a"),model.parse("a",0,1)) };assertEquals(1,calls.get())
  }
  @Test fun nowFilteringStaysFresh() {
    val calls=AtomicInteger();val model=reader(calls)
    assertEquals(1,model.parse("a",0,1).size);assertTrue(model.parse("a",21,1).isEmpty())
  }
  @Test fun callerHorizonStaysFresh() {
    val calls=AtomicInteger();val model=reader(calls,prepare={ _,_,_ -> calls.incrementAndGet()
      listOf(CalendarFeed.Event(2*86_400_000L,2*86_400_000L+1,"Later",false)) })
    assertTrue(model.parse("a",0,1).isEmpty());assertEquals(1,model.parse("a",0,3).size)
  }
  @Test fun changedContentReprepares() {
    val calls=AtomicInteger();val model=reader(calls)
    model.parse("a",0,1);model.parse("b",0,1);model.parse("a",0,1);assertEquals(2,calls.get())
  }
  @Test fun timezoneChangeReprepares() = defaults {
    val calls=AtomicInteger();val model=reader(calls)
    model.parse("a",0,1);TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    model.parse("a",0,1);model.parse("a",0,1);assertEquals(2,calls.get())
  }
  @Test fun equalIdsWithDifferentTimezoneRulesStaySeparate() = defaults {
    val calls=AtomicInteger();val model=reader(calls)
    TimeZone.setDefault(SimpleTimeZone(0,"Fixture"));model.parse("a",0,1)
    TimeZone.setDefault(SimpleTimeZone(3_600_000,"Fixture"));model.parse("a",0,1)
    model.parse("a",0,1);assertEquals(2,calls.get())
  }
  @Test fun localeChangeReprepares() = defaults {
    val calls=AtomicInteger();val model=reader(calls)
    model.parse("a",0,1);Locale.setDefault(Locale.JAPAN)
    model.parse("a",0,1);model.parse("a",0,1);assertEquals(2,calls.get())
  }
  @Test fun formattingLocaleChangeRepreparesWithoutChangingDisplayLocale() = defaults {
    val calls=AtomicInteger();val model=reader(calls)
    model.parse("a",0,1);Locale.setDefault(Locale.Category.FORMAT,Locale.JAPAN)
    model.parse("a",0,1);model.parse("a",0,1);assertEquals(2,calls.get())
    assertEquals(Locale.US,Locale.getDefault(Locale.Category.DISPLAY))
  }
  @Test fun capturedTimezoneCannotMutateCacheKey() = defaults {
    val calls=AtomicInteger();var captured:TimeZone?=null
    val model=reader(calls,prepare={s,z,_ ->calls.incrementAndGet();captured=z;events(s)})
    model.parse("a",0,1);captured!!.rawOffset=3_600_000;model.parse("a",0,1);assertEquals(1,calls.get())
  }
  @Test fun thirtySecondExpiryAndClockRollbackReprepare() {
    val calls=AtomicInteger();val clock=AtomicLong();val model=reader(calls,clock)
    model.parse("a",0,1);clock.set(29_999);model.parse("a",0,1);assertEquals(1,calls.get())
    clock.set(30_000);model.parse("a",0,1);assertEquals(2,calls.get())
    clock.set(29_999);model.parse("a",0,1);assertEquals(3,calls.get())
  }
  @Test fun onlyFourDocumentsRetained() {
    val calls=AtomicInteger();val model=reader(calls)
    repeat(5) {model.parse("a$it",0,1)};model.parse("a4",0,1);assertEquals(5,calls.get())
    model.parse("a0",0,1);assertEquals(6,calls.get())
  }
  @Test fun oversizedInputsSupportedWithoutRetention() {
    val calls=AtomicInteger();val model=reader(calls);val source="a".repeat(262_145)
    repeat(2) {assertEquals(1,model.parse(source,0,1).size)};assertEquals(2,calls.get())
  }
  @Test fun oversizedPreparedDocumentsSupportedWithoutRetention() {
    val calls=AtomicInteger();val model=reader(calls,retain={false})
    repeat(2) {assertEquals(1,model.parse("a",0,1).size)};assertEquals(2,calls.get())
  }
  @Test fun failedPreparationIsNotRetained() {
    val calls=AtomicInteger();val failure=IllegalStateException("fixture")
    val model=reader(calls,prepare={s,_,_->if(calls.incrementAndGet()==1)throw failure;events(s)})
    try {model.parse("a",0,1);fail("failure required")}catch(e:IllegalStateException){assertSame(failure,e)}
    assertEquals(1,model.parse("a",0,1).size);assertEquals(2,calls.get())
  }
  @Test fun cancelledWaiterPreservesActiveOwner() {
    val calls=AtomicInteger();val entered=CountDownLatch(1);val release=CountDownLatch(1)
    val model=reader(calls,prepare={s,_,_->calls.incrementAndGet();entered.countDown()
      check(release.await(2,TimeUnit.SECONDS));events(s)})
    val pool=Executors.newFixedThreadPool(2)
    try {
      val owner=pool.submit<List<CalendarFeed.Event>>{model.parse("a",0,1)}
      assertTrue(entered.await(1,TimeUnit.SECONDS))
      val waiter=pool.submit<List<CalendarFeed.Event>>{model.parse("a",0,1)}
      waiter.cancel(true);release.countDown();assertEquals(1,owner.get(2,TimeUnit.SECONDS).size)
      assertEquals(1,model.parse("a",0,1).size);assertEquals(1,calls.get())
    }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS))}
  }
  @Test fun unrelatedDocumentsPrepareConcurrently() {
    val both=CountDownLatch(2);val release=CountDownLatch(1);val calls=AtomicInteger()
    val model=reader(calls,prepare={s,_,_->calls.incrementAndGet();both.countDown()
      check(release.await(2,TimeUnit.SECONDS));events(s)})
    val pool=Executors.newFixedThreadPool(2)
    try {
      val a=pool.submit{model.parse("a",0,1)};val b=pool.submit{model.parse("b",0,1)}
      assertTrue(both.await(1,TimeUnit.SECONDS));release.countDown();a.get(2,TimeUnit.SECONDS);b.get(2,TimeUnit.SECONDS)
    }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS))}
  }
  private val feed="""
    BEGIN:VCALENDAR
    BEGIN:VEVENT
    DTSTART:20261012T100000Z
    DTEND:20261012T110000Z
    SUMMARY:First
    END:VEVENT
    BEGIN:VEVENT
    DTSTART:20261012T120000Z
    DTEND:20261012T130000Z
    SUMMARY:Second
    END:VEVENT
    END:VCALENDAR
  """.trimIndent()
  private val now=1_791_792_000_000L
  @Test fun returnedCalendarListsRemainIndependent() = defaults {
    val first=CalendarFeed.parse(feed,now,45);assertEquals(2,first.size)
    (first as MutableList).clear();assertEquals(2,CalendarFeed.parse(feed,now,45).size)
  }
  @Test fun calendarDateFormsAndTextRemainCorrect() = defaults {
    val source="""
      BEGIN:VCALENDAR
      BEGIN:VEVENT
      DTSTART;VALUE=DATE:20261012
      SUMMARY:All day
      END:VEVENT
      BEGIN:VEVENT
      DTSTART;TZID=America/New_York:20261012T100000
      DTEND;TZID=America/New_York:20261012T110000
      SUMMARY:Folded
       \, title
      LOCATION:Home\; room
      END:VEVENT
      END:VCALENDAR
    """.trimIndent()
    val rows=CalendarFeed.parse(source,now,45);assertEquals(2,rows.size)
    assertTrue(rows[0].allDay);assertEquals("Folded, title",rows[1].title);assertEquals("Home; room",rows[1].location)
    assertEquals(14,java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply{timeInMillis=rows[1].startMillis}.get(java.util.Calendar.HOUR_OF_DAY))
  }
  @Test fun recurrenceAndFilteringStayFreshAfterWarmRead() = defaults {
    val source=feed.replace("SUMMARY:First","SUMMARY:First\nRRULE:FREQ=DAILY;COUNT=3")
    assertEquals(2,CalendarFeed.parse(source,now,1).size)
    assertEquals(4,CalendarFeed.parse(source,now,5).size)
    assertTrue(CalendarFeed.parse(source,now+10*86_400_000L,5).isEmpty())
  }
  @Test fun endedEventsCannotConsumeTheResultCap() = defaults {
    val past="BEGIN:VEVENT\nDTSTART:20200101T100000Z\nDTEND:20200101T110000Z\nSUMMARY:Past\nEND:VEVENT\n"
    assertEquals(listOf("First","Second"),CalendarFeed.parse(past.repeat(501)+feed,now,45).map{it.title})
  }
}

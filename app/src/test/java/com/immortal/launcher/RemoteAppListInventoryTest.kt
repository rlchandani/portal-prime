/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Configuration
import android.content.res.Resources
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class RemoteAppListInventoryTest {
  private class Fixture {
    val app = sharedApp
    val caller = mock(Context::class.java)
    val pm = mock(PackageManager::class.java)
    val resources = mock(Resources::class.java)
    val configuration = mock(Configuration::class.java)
    val scans = AtomicInteger()
    var receiver: BroadcastReceiver?
      get() = sharedReceiver
      set(value) { sharedReceiver = value }
    var subscribedContext: Context?
      get() = sharedSubscribedContext
      set(value) { sharedSubscribedContext = value }
    var label = "Generated app"
    init {
      val resourceVersion = owners.incrementAndGet()
      val owner = "generated.owner"
      for (context in listOf(app, caller)) {
        `when`(context.applicationContext).thenReturn(app)
        `when`(context.packageName).thenReturn(owner)
        `when`(context.packageManager).thenReturn(pm)
        `when`(context.resources).thenReturn(resources)
      }
      `when`(resources.configuration).thenReturn(configuration)
      `when`(configuration.toString()).thenReturn("generated-resources-$resourceVersion")
      val activity = mock(ActivityInfo::class.java).apply { packageName = "generated.app" }
      val resolve = mock(ResolveInfo::class.java).apply { activityInfo = activity }
      val application = mock(ApplicationInfo::class.java)
      `when`(pm.queryIntentActivities(any(Intent::class.java), eq(0))).thenAnswer {
        scans.incrementAndGet(); listOf(resolve, resolve)
      }
      `when`(pm.getApplicationInfo("generated.app", 0)).thenReturn(application)
      `when`(pm.getApplicationLabel(application)).thenAnswer { label }
    }
  }
  private fun fixture(body: (Fixture) -> Unit) {
    val f = Fixture()
    mockConstruction(Intent::class.java) { intent, _ ->
      `when`(intent.addCategory(anyString())).thenReturn(intent)
    }.use {
      mockConstruction(IntentFilter::class.java).use {
        mockStatic(ContextCompat::class.java).use { compat ->
          compat.`when`<Intent?> {
            ContextCompat.registerReceiver(any(Context::class.java), any(BroadcastReceiver::class.java),
                any(IntentFilter::class.java), eq(ContextCompat.RECEIVER_NOT_EXPORTED))
          }.thenAnswer {
            f.subscribedContext = it.getArgument(0)
            f.receiver = it.getArgument(1)
            null
          }
          body(f)
        }
      }
    }
  }
  @Test fun twentyWarmRequestsScanAndReadLabelOnce() = fixture { f ->
    repeat(20) {
      val json = RemoteApps.listJson(f.caller, "portal")
      assertEquals(1, json.length())
      assertEquals("Generated app", json.getJSONObject(0).getString("label"))
    }
    assertEquals(1, f.scans.get())
  }
  @Test fun responsesOwnTheirJsonWithoutScanningAgain() = fixture { f ->
    val first = RemoteApps.listJson(f.caller, "portal")
    first.getJSONObject(0).put("label", "Changed by caller")
    val second = RemoteApps.listJson(f.caller, "portal")
    assertNotSame(first, second)
    assertEquals("Generated app", second.getJSONObject(0).getString("label"))
    assertEquals(1, f.scans.get())
  }
  @Test fun callersShareApplicationInventoryWithoutRetainingActivity() = fixture { f ->
    RemoteApps.listJson(f.caller, "portal")
    RemoteApps.listJson(f.app, "portal")
    assertEquals(1, f.scans.get())
    assertSame(f.app, f.subscribedContext)
  }
  @Test fun packageBroadcastRefreshesTheNextRequest() = fixture { f ->
    RemoteApps.listJson(f.caller, "portal")
    assertNotNull("Package subscription required before retaining inventory", f.receiver)
    f.label = "Updated app"
    f.receiver!!.onReceive(f.app, mock(Intent::class.java))
    assertEquals("Updated app", RemoteApps.listJson(f.caller, "portal").getJSONObject(0).getString("label"))
    RemoteApps.listJson(f.caller, "portal")
    assertEquals(2, f.scans.get())
  }
  @Test fun resourceChangeUsesFreshLabels() = fixture { f ->
    RemoteApps.listJson(f.caller, "portal")
    f.label = "Translated app"
    `when`(f.configuration.toString()).thenReturn("generated-resources-b")
    assertEquals("Translated app", RemoteApps.listJson(f.caller, "portal").getJSONObject(0).getString("label"))
    assertEquals(2, f.scans.get())
  }
  private val key = RemoteAppListKey("owner", "resources", "portal", "en")
  private fun scan(label: String = "Original") = RemoteAppListScan(listOf(RemoteAppListEntry("generated.app", label)))

  @Test fun expiryStartsAfterSuccessfulScanCompletion() {
    var now = 0L; var calls = 0
    val inventory = RemoteAppListInventory<String>({ _, _ -> true }, ttlMillis = 10, nowMillis = { now })
    inventory.entries("ctx", key) { calls++; now = 50; scan() }
    now = 59
    assertEquals("Original", inventory.entries("ctx", key) { calls++; scan("New") }.single().label)
    now = 60
    assertEquals("New", inventory.entries("ctx", key) { calls++; scan("New") }.single().label)
    assertEquals(2, calls)
  }
  @Test fun eightConcurrentRequestsShareOneScan() {
    val inventory = RemoteAppListInventory<String>({ _, _ -> true })
    val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
    val pool = Executors.newFixedThreadPool(8)
    try {
      val tasks = List(8) { pool.submit<List<RemoteAppListEntry>> {
        inventory.entries("ctx", key) { calls.incrementAndGet(); entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); scan() }
      } }
      assertTrue(entered.await(2, TimeUnit.SECONDS)); release.countDown()
      tasks.forEach { assertEquals("Original", it.get(3, TimeUnit.SECONDS).single().label) }
      assertEquals(1, calls.get())
    } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(2, TimeUnit.SECONDS) }
  }
  @Test fun invalidatedInflightScanCannotReplaceNewInventory() {
    val inventory = RemoteAppListInventory<String>({ _, _ -> true })
    val entered = CountDownLatch(1); val release = CountDownLatch(1); val pool = Executors.newSingleThreadExecutor()
    try {
      val old = pool.submit<List<RemoteAppListEntry>> { inventory.entries("ctx", key) {
        entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); scan("Old")
      } }
      assertTrue(entered.await(2, TimeUnit.SECONDS)); inventory.invalidate()
      assertEquals("New", inventory.entries("ctx", key) { scan("New") }.single().label)
      release.countDown(); assertEquals("Old", old.get(3, TimeUnit.SECONDS).single().label)
      assertEquals("New", inventory.entries("ctx", key) { error("New value must remain cached") }.single().label)
    } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(2, TimeUnit.SECONDS) }
  }
  @Test fun failedSubscriptionAndPartialScansStayUncached() {
    var calls = 0; var subscriptions = 0
    val unavailable = RemoteAppListInventory<String>({ _, _ -> subscriptions++; false })
    repeat(2) { unavailable.entries("ctx", key) { calls++; scan() } }
    assertEquals(2, calls); assertEquals(2, subscriptions)
    val partial = RemoteAppListInventory<String>({ _, _ -> true })
    repeat(2) { partial.entries("ctx", key) { calls++; scan().copy(complete = false) } }
    assertEquals(4, calls)
    assertEquals("Recovered", partial.entries("ctx", key) { calls++; scan("Recovered") }.single().label)
    assertEquals("Recovered", partial.entries("ctx", key) { error("Recovered value must remain cached") }.single().label)
  }
  @Test fun oversizedInventoryIsReturnedEntirelyWithoutRetention() {
    var calls = 0
    val inventory = RemoteAppListInventory<String>({ _, _ -> true }, maxRetainedApps = 1)
    repeat(2) { assertEquals(2, inventory.entries("ctx", key) {
      calls++; RemoteAppListScan(listOf(RemoteAppListEntry("one", "One"), RemoteAppListEntry("two", "Two")))
    }.size) }
    assertEquals(2, calls)
  }
  @Test fun contextOwnerHardwareAndLocaleStaySeparateAndResourceRaceDoesNotRetain() {
    var calls = 0
    val inventory = RemoteAppListInventory<String>({ _, _ -> true })
    val keys = listOf(key, key.copy(owner = "another"), key.copy(device = "falcon"), key.copy(locale = "de"))
    repeat(2) { keys.forEach { k -> inventory.entries("ctx", k) { calls++; scan() } } }
    assertEquals(4, calls)
    inventory.invalidate()
    inventory.entries("ctx", key, canRetain = { false }) { calls++; scan("Old resources") }
    assertEquals("Current", inventory.entries("ctx", key) { calls++; scan("Current") }.single().label)
    assertEquals(6, calls)
  }
  @Test fun failedScansRetryAndReturnedPrimitiveListCannotBeMutated() {
    val inventory = RemoteAppListInventory<String>({ _, _ -> true })
    assertTrue(runCatching { inventory.entries("ctx", key) { error("Generated scan failure") } }.isFailure)
    val entries = inventory.entries("ctx", key) { scan() }
    assertTrue(runCatching { (entries as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
    assertEquals("Original", inventory.entries("ctx", key) { error("Cached result missing") }.single().label)
  }
  companion object {
    private val owners = AtomicInteger()
    private val sharedApp = mock(Context::class.java)
    private var sharedReceiver: BroadcastReceiver? = null
    private var sharedSubscribedContext: Context? = null
  }
}

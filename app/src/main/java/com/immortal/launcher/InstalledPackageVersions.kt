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
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.Collections

/** Only copied package/version primitives; never PackageInfo, drawables or Activity contexts. */
internal class InstalledPackageVersionSnapshot(
    versions: Map<String, Long>,
    packageNames: Set<String>,
    val loadedElapsedMillis: Long,
) {
  private val versions = Collections.unmodifiableMap(HashMap(versions))
  private val coverage = Collections.unmodifiableSet(HashSet(packageNames))
  fun versionCode(packageName: String): Long? = versions[packageName]
  fun covers(packageNames: Set<String>): Boolean = coverage.containsAll(packageNames)
}

/** No timer or collector. Call snapshot on the existing HTTP/IO worker, not from composition. */
internal class InstalledVersionInventory(
    private val clock: () -> Long,
    private val subscribe: (Context, () -> Unit) -> Boolean,
    private val query: (Context, Set<String>) -> Map<String, Long>,
    private val maxAgeMillis: Long = 5_000,
) {
  private val cache = HomePresentationInventoryCache<InstalledPackageVersionSnapshot>()
  private val registrationLock = Any()
  private val desiredLock = Any()
  // Catalog identities accumulate until process restart, including known absent packages.
  private val desiredPackages = mutableSetOf<String>()
  @Volatile private var application: Context? = null

  fun register(context: Context): Boolean {
    application?.let { return true }
    val app = context.applicationContext ?: return false
    return synchronized(registrationLock) {
      application?.let { return@synchronized true }
      val registered = runCatching { subscribe(app, ::invalidate) }.getOrDefault(false)
      if (registered) application = app
      registered
    }
  }

  fun snapshot(context: Context, packageNames: Set<String>): InstalledPackageVersionSnapshot? {
    if (!register(context)) return null
    val app = application ?: return null
    // A failed scan does not become an empty/negative cached result. Consumers may use
    // the previous uncached per-package read; the next poll can retry registration/scan.
    return runCatching {
      val requested = packageNames.toSet()
      synchronized(desiredLock) {
        // Adding coverage fences an older in-flight scan before the new caller can load.
        if (desiredPackages.addAll(requested)) cache.invalidate()
      }
      freshSnapshot(app, requested)
    }.getOrNull()
  }

  private fun freshSnapshot(app: Context, packageNames: Set<String>): InstalledPackageVersionSnapshot {
    while (true) {
      val observed = cache.load {
        val desired = synchronized(desiredLock) { desiredPackages.toSet() }
        val versions = query(app, desired)
        listOf(InstalledPackageVersionSnapshot(versions, desired, clock()))
      }
      val value = observed.single()
      // Validate actual returned coverage and age, including a replacement published
      // while this caller waited. Conditional invalidation cannot discard a newer list.
      val age = clock() - value.loadedElapsedMillis
      if (value.covers(packageNames) && age >= 0 && age < maxAgeMillis) return value
      cache.invalidateIfCurrent(observed)
    }
  }

  fun invalidate() { cache.invalidate() }
}

internal object InstalledPackageVersions {
  private val inventory = InstalledVersionInventory(
      clock = SystemClock::elapsedRealtime,
      subscribe = { app, invalidate ->
        val filter = IntentFilter().apply {
          addAction(Intent.ACTION_PACKAGE_ADDED)
          addAction(Intent.ACTION_PACKAGE_REMOVED)
          addAction(Intent.ACTION_PACKAGE_CHANGED)
          addAction(Intent.ACTION_PACKAGE_REPLACED)
          addDataScheme("package")
        }
        val receiver = object : BroadcastReceiver() {
          override fun onReceive(context: Context, intent: Intent) { invalidate() }
        }
        // Package broadcasts are protected system events. Replacement removals also
        // invalidate: there must be no cached old version between remove and add.
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        true
      },
      query = { app, packageNames -> queryInstalledPackageVersions(app, packageNames) },
  )
  fun register(context: Context): Boolean = inventory.register(context)
  fun snapshot(context: Context, packageNames: Set<String>): InstalledPackageVersionSnapshot? = inventory.snapshot(context, packageNames)
  fun invalidate() { inventory.invalidate() }
}

/** Query only catalog identities; unexpected failures propagate for uncached fallback. */
internal fun queryInstalledPackageVersions(
    context: Context,
    packageNames: Set<String>,
    sdkInt: Int = Build.VERSION.SDK_INT,
): Map<String, Long> {
  if (packageNames.isEmpty()) return emptyMap()
  val pm = context.packageManager
  val versions = HashMap<String, Long>(packageNames.size)
  for (packageName in packageNames) {
    try {
      @Suppress("DEPRECATION")
      val info = pm.getPackageInfo(packageName, 0)
      versions[packageName] = if (sdkInt >= 28) info.longVersionCode
          else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (_: PackageManager.NameNotFoundException) {
      // Covered absence is a valid cached result, unlike a failed metadata query.
    }
  }
  return versions
}

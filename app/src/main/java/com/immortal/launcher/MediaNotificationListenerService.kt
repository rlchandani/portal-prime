/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/** Notification access also grants MediaSessionReader access to Android media controllers. */
class MediaNotificationListenerService : NotificationListenerService() {
  private val badges = NotificationBadgeSnapshot()
  private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  @Volatile private var initialRead: Job? = null
  @Volatile private var seedEpoch = 0L
  @Volatile private var retryAfter = 0L
  private val binderReads = AtomicInteger()
  private val snapshotReads = AtomicInteger()
  private val posted = AtomicInteger()
  private val removed = AtomicInteger()
  private val seedFailures = AtomicInteger()

  @Synchronized override fun onListenerConnected() {
    initialRead?.cancel()
    seedEpoch = badges.beginSeed()
    retryAfter = 0L
    instance = this
    revisions.update { it + 1 }
    MediaSessionReader.onListenerConnected(this)
    seedIfNeeded()
  }

  // A failed seed can retry on a later Apps visit without blocking UI or polling while hidden.
  @Synchronized private fun seedIfNeeded() {
    if (instance !== this || badges.seeded || initialRead?.isActive == true || SystemClock.elapsedRealtime() < retryAfter) return
    val epoch = seedEpoch
    initialRead = work.launch {
      binderReads.incrementAndGet()
      val initial = try { activeNotifications.orEmpty().map(::badgeEntry) }
      catch (_: Exception) {
        ensureActive()
        if (seedEpoch == epoch) {
          seedFailures.incrementAndGet()
          retryAfter = SystemClock.elapsedRealtime() + 1_000L
        }
        return@launch
      }
      ensureActive()
      if (instance === this@MediaNotificationListenerService && badges.completeSeed(epoch, initial)) {
        revisions.update { it + 1 }
      }
    }
  }

  override fun onNotificationPosted(sbn: StatusBarNotification) {
    if (BuildConfig.PORTAL_HOME_UI_DIAGNOSTICS) posted.incrementAndGet()
    val before = badges.snapshot()
    badges.upsert(badgeEntry(sbn))
    if (instance === this && before !== badges.snapshot()) revisions.update { it + 1 }
  }

  override fun onNotificationRemoved(sbn: StatusBarNotification) {
    if (BuildConfig.PORTAL_HOME_UI_DIAGNOSTICS) removed.incrementAndGet()
    val before = badges.snapshot()
    badges.remove(sbn.key)
    if (instance === this && before !== badges.snapshot()) revisions.update { it + 1 }
  }

  override fun onListenerDisconnected() { disconnect() }

  override fun onDestroy() {
    disconnect()
    work.cancel()
    super.onDestroy()
  }

  @Synchronized private fun disconnect() {
    initialRead?.cancel()
    badges.clear()
    if (instance === this) {
      instance = null
      revisions.update { it + 1 }
      MediaSessionReader.onListenerDisconnected()
    }
  }

  private fun badgeEntry(sbn: StatusBarNotification) = NotificationBadgeSnapshot.Entry(
      sbn.key, sbn.packageName, notificationBadgeEligible(sbn.isClearable, sbn.notification.flags))

  internal fun badgeCounts(): Map<String, Int> {
    if (!badges.seeded) seedIfNeeded()
    if (BuildConfig.PORTAL_HOME_UI_DIAGNOSTICS) snapshotReads.incrementAndGet()
    return badges.snapshot()
  }

  private fun diagnosticSnapshot(ownPackage: String): JSONObject = JSONObject()
      .put("connected", instance === this).put("seeded", badges.seeded)
      .put("binderSnapshotReads", binderReads.get()).put("cacheSnapshotReads", snapshotReads.get())
      .put("postedEvents", posted.get()).put("removedEvents", removed.get())
      .put("seedFailures", seedFailures.get()).put("ownPackageCount", badges.snapshot()[ownPackage] ?: 0)

  companion object {
    @Volatile private var instance: MediaNotificationListenerService? = null
    private val revisions = MutableStateFlow(0L)
    internal val badgeRevision: StateFlow<Long> get() = revisions

    /** Warm Apps visits reuse an immutable count snapshot; no Binder or notification scan. */
    fun activeCountsByPackage(): Map<String, Int> =
        instance?.let { runCatching { it.badgeCounts() }.getOrNull() } ?: emptyMap()

    internal fun badgeDiagnostics(ownPackage: String): JSONObject =
        instance?.diagnosticSnapshot(ownPackage) ?: JSONObject().put("connected", false).put("seeded", false)
            .put("binderSnapshotReads", 0).put("cacheSnapshotReads", 0).put("postedEvents", 0)
            .put("removedEvents", 0).put("seedFailures", 0).put("ownPackageCount", 0)
  }
}

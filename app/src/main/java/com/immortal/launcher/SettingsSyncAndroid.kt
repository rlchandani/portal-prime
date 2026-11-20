/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.Handler
import android.util.Log
import com.immortal.launcher.settings.SettingsSyncCatalog
import org.json.JSONObject

/** The household catalog over this Portal's registry. */
internal class CatalogSyncSettings(private val c: Context, private val catalog: SettingsSyncCatalog = SettingsSyncCatalog.household) :
    SyncSettings {
  override fun read() = catalog.read(c)
  override fun apply(values: Map<String, Any>) = catalog.apply(c, values)
  override fun defaults() = catalog.defaults()
  override fun wellFormed(key: String, raw: Any?) = catalog.wellFormed(key, raw)
  override fun validate(key: String, raw: Any?) = catalog.validate(key, raw)
  override fun siblingKeys(keys: Collection<String>) = catalog.siblingKeys(keys)
}

internal class AndroidSyncHost(private val c: Context) : SyncHost {
  override fun selfId(): String? = runCatching { RemoteCompanionStore.id(c) }.getOrNull()
  override fun selfName() = HouseholdListSync.selfName(c)
  override fun lanAddress() = HouseholdListSync.lanAddress()
  override fun port() = FleetConfig.port(c)
  override fun householdKey() = HouseholdKey.get(c)
  override fun loadLedger(wellFormed: (String, Any?) -> Any?) = SettingsSyncStore.load(c, wellFormed)
  override fun saveLedger(ledger: SyncLedger) = SettingsSyncStore.save(c, ledger)
  override fun clearLedger() = SettingsSyncStore.clear(c)
  override fun adoptCardDismissed() = SettingsSyncStore.adoptCardDismissed(c)
  override fun setAdoptCardDismissed(on: Boolean) = SettingsSyncStore.setAdoptCardDismissed(c, on)
}

internal class AndroidSyncBus(private val c: Context) : SyncBus {
  override fun send(type: String, body: JSONObject) = HouseholdBus.send(c, type, body)
}

internal class HandlerSyncTimers(private val main: () -> Handler) : SyncTimers {
  override fun postDelayed(delayMs: Long, task: Runnable) { main().postDelayed(task, delayMs) }
  override fun cancel(task: Runnable) = main().removeCallbacks(task)
}

internal class AndroidSyncLog(private val tag: String) : SyncLog {
  override fun i(message: String) { Log.i(tag, message) }
  override fun w(message: String, error: Throwable?) { if (error != null) Log.w(tag, message, error) else Log.w(tag, message) }
}

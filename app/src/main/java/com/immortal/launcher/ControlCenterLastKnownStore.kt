/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log
import java.io.File

/**
 * This Portal's cached last-known Control Center states (`files/control_center_last_known.json`),
 * written atomically (see [CcLastKnownJson]). Mirrors the in-memory cache so a cold open draws at
 * once; Home Assistant's first full answer still confirms or drops every restored entity. The file
 * never holds secrets (entity ids, states and attributes only) and is read/written on the engine
 * thread, never the main thread.
 */
internal object CcLastKnownStore {
  private const val TAG = "HearthpaneControl"
  private const val FILE = "control_center_last_known.json"

  fun load(context: Context): Map<String, CcEntity> = runCatching {
    val f = File(context.filesDir, FILE)
    if (f.exists()) CcLastKnownJson.decode(f.readText(), System.currentTimeMillis()) else emptyMap()
  }.onFailure { Log.w(TAG, "last-known cache unreadable (${it.javaClass.simpleName})") }.getOrDefault(emptyMap())

  fun save(context: Context, entities: Map<String, CcEntity>): Boolean = runCatching {
    val dir = context.filesDir
    val file = File(dir, FILE)
    if (entities.isEmpty()) { file.delete(); return@runCatching }
    val tmp = File(dir, "$FILE.tmp")
    tmp.writeText(CcLastKnownJson.encode(entities, System.currentTimeMillis()).toString())
    check(tmp.renameTo(file)) { "rename failed" }
  }.onFailure { Log.w(TAG, "last-known cache not saved (${it.javaClass.simpleName})") }.isSuccess
}

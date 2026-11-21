/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log
import com.immortal.launcher.youtube.YtKeystoreSecureStore
import com.immortal.launcher.youtube.YtSecureStore
import java.io.File

/**
 * This Portal's [PortalRoster], sealed at rest like [HomeLinkRepository] (own Keystore alias,
 * `no_backup/` [DIR], blocked from the fleet file routes by [FleetSecretPaths]) so nothing but
 * this app can add a pin. Only the in-person pinning step calls [pin]; there is no route or sync
 * that writes it. A roster that can't be read starts empty, which only turns vault sync off.
 */
internal class PortalRosterStore(private val secure: YtSecureStore, private val warn: (String) -> Unit = { Log.w(TAG, it) }) {
  @Volatile var roster: PortalRoster = load()
    private set

  private fun load(): PortalRoster {
    val text = runCatching { secure.load() }.onFailure { warn("portal roster unreadable (key lost?), starting empty") }.getOrNull()
        ?: return PortalRoster()
    return PortalRosterCodec.parse(text) ?: PortalRoster().also { warn("portal roster corrupt, starting empty") }
  }

  /** Keeps [peer] once people confirmed the pairing. False if it was refused or couldn't be stored. */
  @Synchronized fun pin(peer: HomeLinkPeer, selfKey: String, now: Long): Boolean {
    val next = roster.pin(peer, selfKey, now) ?: return false
    return store(next)
  }

  @Synchronized fun unpin(kid: String): Boolean = store(roster.unpin(kid))

  private fun store(next: PortalRoster): Boolean {
    if (next == roster) return true
    val ok = runCatching { secure.save(PortalRosterCodec.encode(next)) }.onFailure { warn("portal roster not stored") }.isSuccess
    if (ok) roster = next
    return ok
  }

  companion object {
    private const val TAG = "HearthpanePortalRoster"
    const val DIR = "hearthpane_portal_roster"
    private const val FILE = "roster.bin"
    private const val ALIAS = "hearthpane.portal.roster"
    @Volatile private var shared: PortalRosterStore? = null

    fun get(context: Context): PortalRosterStore = shared ?: synchronized(this) {
      shared ?: PortalRosterStore(YtKeystoreSecureStore(File(File(context.applicationContext.noBackupFilesDir, DIR), FILE), ALIAS))
          .also { shared = it }
    }
  }
}

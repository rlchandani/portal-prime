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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.SecureRandom

/**
 * This Portal's [HomeLinkVault], sealed at rest: one AES-256-GCM file under a non-exportable
 * Keystore key ([YtKeystoreSecureStore], the same scheme as Grandma mode's [AutoAnswerRepository])
 * in `no_backup/` [DIR], which [FleetSecretPaths] blocks from the fleet file routes and backups.
 * It has its own Keystore alias and folder, so "Delete all messages" ([CallMessageKeys.forget])
 * and message pruning can never take the identity key with them.
 *
 * Every change is stored before it is published; a change that can't be stored doesn't happen.
 * [onChange] fires after a stored change (sync tells the other Portals). Never logs key material.
 */
internal class HomeLinkRepository(
    private val secure: YtSecureStore,
    private val random: SecureRandom,
    private val clock: () -> Long,
    private val warn: (String) -> Unit = { Log.w(TAG, it) },
) {
  private val mutable = MutableStateFlow(load())
  val vault: StateFlow<HomeLinkVault> = mutable.asStateFlow()
  @Volatile var onChange: ((HomeLinkVault) -> Unit)? = null

  private fun load(): HomeLinkVault {
    val text = runCatching { secure.load() }.onFailure { warn("home links unreadable (key lost?), starting empty") }.getOrNull()
    if (text == null) return HomeLinkVault()
    return HomeLinkVaultCodec.parse(text) ?: HomeLinkVault().also { warn("home links corrupt, starting empty") }
  }

  /** This household's identity, made (and stored) the first time a home is linked from here. */
  @Synchronized
  fun identity(): HomeLinkIdentity? {
    mutable.value.identity?.restore()?.let { return it }
    val made = HomeLinkIdentity.generate(random)
    return if (update { it.copy(identity = HomeLinkStoredIdentity.of(made, clock())) }) made else null
  }

  /** People said the pictures match: keep the home, recording which of our identities they checked. */
  fun link(peer: HomeLinkPeer, permissions: HomeLinkPermissions): HomeLink? {
    val link = HomeLink.verified(peer, clock(), permissions, verifiedAs = mutable.value.identity?.kid.orEmpty())
    return if (update { it.put(link) } && mutable.value.entries[link.id]?.link == link) link else null
  }

  /** A permissions edit for a home that is still linked. */
  fun edit(link: HomeLink): Boolean = update { v -> if (v.entries[link.id]?.link == null) v else v.put(link) }

  /** Forgets a home here; sync carries the removal to every Portal in the home. */
  fun remove(id: String): Boolean = update { it.remove(id) }

  /**
   * A Portal was removed from this home ([PortalRemover]): stores its signed marker ([sign] gets the
   * revision) and, if there is an identity, a new one a generation up, made at [at], in one change.
   * The removed Portal's copy of the old key then matches nothing this home uses.
   */
  fun removePortal(at: Long, sign: (rev: Long) -> PortalRemoval): Boolean = update { v ->
    val marked = v.withRemoval(sign(v.clock + 1))
    marked.identity?.let { marked.rotated(next(it, at)) } ?: marked
  }

  /** Another Portal's vault. True if anything here changed. A removal newer than the winning identity rotates it again. */
  fun merge(theirs: HomeLinkVault): Boolean {
    val before = mutable.value
    return update { v ->
      val merged = v.merge(theirs)
      val now = clock()
      merged.identity?.takeIf { merged.identityStale(now) }?.let { merged.rotated(next(it, merged.rotationTime(now))) } ?: merged
    } && mutable.value != before
  }

  private fun next(current: HomeLinkStoredIdentity, at: Long): HomeLinkStoredIdentity =
      HomeLinkStoredIdentity.of(HomeLinkIdentity.generate(random), at, current.generation + 1)

  @Synchronized
  private fun update(edit: (HomeLinkVault) -> HomeLinkVault): Boolean {
    val before = mutable.value
    val after = edit(before)
    if (after == before) return true
    val stored = runCatching { secure.save(HomeLinkVaultCodec.encode(after)) }.onFailure { warn("home links not stored") }.isSuccess
    if (!stored) return false
    mutable.value = after
    onChange?.invoke(after)
    return true
  }

  companion object {
    private const val TAG = "HearthpaneHomeLinks"
    const val DIR = "hearthpane_homelinks"
    private const val FILE = "vault.bin"
    private const val ALIAS = "hearthpane.calling.homelinks"
    @Volatile private var shared: HomeLinkRepository? = null

    fun get(context: Context): HomeLinkRepository = shared ?: synchronized(this) {
      shared ?: HomeLinkRepository(YtKeystoreSecureStore(File(File(context.applicationContext.noBackupFilesDir, DIR), FILE), ALIAS),
          HomeLinkFeature.sharedRandom, System::currentTimeMillis).also { shared = it }
    }
  }
}

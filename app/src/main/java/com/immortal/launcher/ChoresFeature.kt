/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log
import java.security.SecureRandom

/**
 * Chores & stars entry point: `ChoresFeature.install(this)` from [ImmortalApp.onCreate], after the
 * household bus starts. Registers `/remote/chores` (phone), the `chores_rev` bus signal and the
 * change listener (peer sync + goal celebrations), loads the saved replica and announces it so a
 * Portal that changed while this one was off exchanges with it. Home shows [ChoresHomeWidget] for
 * [HomeWidgetStore.KIND_CHORES] and overlays [ChoresCelebrationBanner].
 */
internal object ChoresFeature {
  private const val TAG = "HearthpaneChores"
  private const val ID_BYTES = 6
  private val random = SecureRandom()
  @Volatile private var installed = false

  fun install(context: Context) {
    if (installed) return
    installed = true
    val app = context.applicationContext
    ChoreStore.setListener { ctx, before, after, source ->
      ChoreSync.signal(ctx)
      ChoresCelebration.onChanged(ctx, before, after, source)
    }
    RemoteFeatureRoutes.register(ChoresRoute.PATH, ChoresRoute)
    HouseholdBus.register(ChorePeerAuth.SIGNAL_TYPE) { ctx, message -> ChoreSync.onSignal(ctx, message) }
    runCatching { ChoreStore.ensureLoaded(app) }.onFailure { Log.w(TAG, "could not load chores", it) }
    ChoreSync.signal(app)
  }

  fun selfId(context: Context): String? = runCatching { RemoteCompanionStore.id(context) }.getOrNull()

  fun newId(): String = ByteArray(ID_BYTES).also(random::nextBytes).let(HouseholdKey::toHex)

  /** A kid's tap on a picture: ticks it, or takes a not-yet-approved tick back (a mis-tap). */
  fun toggle(context: Context, kid: String, item: ChoreBoard.Item): ChoreResult {
    val by = selfId(context) ?: return ChoreResult.Refused("companion_storage_unavailable")
    val day = ChoreStore.today()
    return ChoreStore.apply(context, ChoreStore.Source.LOCAL) { s ->
      val held = item.tick?.let { s.ticks[it.id] }
      if (held != null && held.status.holds) ChoreOps.undo(s, held.id, by)
      else ChoreOps.tick(s, item.chore.id, kid, day, newId(), by)
    }
  }
}

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.security.SecureRandom

/**
 * "Use another Portal's Home Assistant connection", wired to this Portal: both ends of
 * `/remote/hapeer` ([HaShareRoute], [HaShareClient]) and the adoption ([HaAdoption]). The token is
 * never logged here; log lines carry device ids and outcomes only.
 */
internal object HaShare {
  private const val TAG = "HearthpaneHaShare"
  private const val NONCE_BYTES = 12
  private const val VALIDATE_CONNECT_MS = 5_000
  private const val VALIDATE_READ_MS = 8_000
  /** Long enough for a re-announced hello to cross the LAN and be stored by the source. */
  private const val HELLO_SETTLE_MS = 1_500L
  private val random = SecureRandom()
  private val replay = HouseholdMessage.ReplayGuard()
  private val limiter = HaShareLimiter()
  private val main = Handler(Looper.getMainLooper())

  /** The source's route (an HTTP worker thread). */
  fun handle(context: Context, req: FleetHttpServer.Request): FleetHttpServer.Response =
      HaShareRoute(
          key = { HouseholdKey.get(context) },
          selfId = { selfId(context) },
          selfName = { DinnerCall.selfName(context) },
          clock = System::currentTimeMillis,
          replay = replay,
          limiter = limiter,
          announcedHost = { id -> CallingStore.peers.value.firstOrNull { it.id == id }?.host },
          connection = { connection(context) },
          random = random,
          log = { Log.i(TAG, it) },
      ).handle(req)

  /** The adoption for this Portal. Its calls block on the network: never call them on the main thread. */
  fun adoption(context: Context): HaAdoption {
    val app = context.applicationContext
    return HaAdoption(
        peers = { HaAdoption.candidates(CallingStore.peers.value, selfId(app)) },
        client = HaShareClient({ HouseholdKey.get(app) }, { selfId(app) }, HttpHaShareTransport, System::currentTimeMillis,
            { ByteArray(NONCE_BYTES).also(random::nextBytes).let(HouseholdKey::toHex) }),
        validate = ::validate,
        current = { connection(app) },
        save = { apply(app, it) },
        announce = { Calling.announce(app, ask = false) },
        settle = { Thread.sleep(HELLO_SETTLE_MS) },
        log = { Log.i(TAG, it) },
    )
  }

  fun connection(context: Context): HaShareWire.Connection? =
      if (HaConfig.isConfigured(context)) HaShareWire.Connection.of(HaConfig.baseUrl(context), HaConfig.token(context)) else null

  /**
   * Saves a connection and brings every surface that watches it to life without a restart: the
   * Control Center (and its alerts and banners), the doorbell listener, the Home Control tile and,
   * through [HaConfig.revision], the header button and the HA widget.
   */
  fun apply(context: Context, connection: HaShareWire.Connection) {
    val app = context.applicationContext
    HaConfig.setConnection(app, connection.url, connection.token)
    main.post {
      runCatching {
        DoorbellListener.restart(app)
        ControlCenter.restart(app)
        invalidateApprovedDashboardInventory()
      }.onFailure { Log.w(TAG, "restart after a connection change failed: ${it.javaClass.simpleName}") }
    }
  }

  /** HA's `/api/` with the bearer token: true only for its own "API running." answer. Blocking. */
  private fun validate(connection: HaShareWire.Connection): Boolean {
    val headers = mapOf("Authorization" to "Bearer ${connection.token}", "Content-Type" to "application/json")
    val body = runCatching {
      Http.getString("${connection.url}/api/", headers = headers, connectTimeoutMs = VALIDATE_CONNECT_MS,
          readTimeoutMs = VALIDATE_READ_MS)
    }.getOrNull()
    return HaAdoption.isApiRunning(body)
  }

  private fun selfId(context: Context): String? = runCatching { RemoteCompanionStore.id(context) }.getOrNull()
}

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Feature-owned `/remote/<feature>` endpoints. [RemoteCompanionRoutes] authenticates the paired
 * session, enforces body limits and parses JSON before delegating here, so feature files only
 * implement behavior. Register once at process start; a path can be owned by one feature.
 */
internal object RemoteFeatureRoutes {
  fun interface Route {
    /** [body] is the parsed POST JSON (empty for GET). */
    fun handle(context: Context, method: String, body: JSONObject): FleetHttpServer.Response
  }

  /**
   * A route that also needs to know which paired phone is calling (rate limits, confirm tickets).
   * [caller] is [callerKey] of the session: stable per pairing, never the token itself.
   */
  interface CallerRoute : Route {
    fun handleFrom(context: Context, method: String, body: JSONObject, caller: String): FleetHttpServer.Response
    /** Without a caller there is nobody to bind limits or confirmations to. */
    override fun handle(context: Context, method: String, body: JSONObject): FleetHttpServer.Response =
        json(401, error("unauthorized"))
  }

  /** An opaque per-session key: the first 64 bits of SHA-256 over the token (safe to keep in memory). */
  fun callerKey(token: String): String = RemoteSessionPolicy.idOf(token)

  private val routes = ConcurrentHashMap<String, Route>()

  fun register(path: String, route: Route) {
    require(path.startsWith("/remote/") && path.length > "/remote/".length) { "feature routes live under /remote/" }
    check(routes.putIfAbsent(path, route) == null || routes[path] === route) { "route $path already owned" }
  }

  fun lookup(path: String): Route? = routes[path]

  fun ok(): JSONObject = JSONObject().put("ok", true)
  fun error(code: String): JSONObject = JSONObject().put("ok", false).put("error", code)
  fun json(status: Int, body: JSONObject) = FleetHttpServer.Response(status, body.toString())

  /**
   * Contract route: the phone provisions the household key; read-back shows only a fingerprint.
   * Reading or replacing the key is allowed only for the session the latest in-person PIN pairing
   * created, within 10 minutes, and only once ([RemotePairing.householdWindowOpen]).
   */
  fun registerHouseholdKey() = register("/remote/household", householdKeyRoute)

  internal val householdKeyRoute = object : CallerRoute {
    override fun handleFrom(context: Context, method: String, body: JSONObject, caller: String): FleetHttpServer.Response =
        when (method) {
          "GET" -> json(200, ok().put("configured", HouseholdKey.get(context) != null)
              .put("fingerprint", HouseholdKey.get(context)?.let(HouseholdKey::fingerprint) ?: JSONObject.NULL))
          "POST" -> RemotePairing.withHouseholdGrant<FleetHttpServer.Response>(caller, succeeded = { it.status == 200 }) { householdPost(context, body) }
              ?: json(403, error("pair_again"))
          else -> json(405, error("method_not_allowed"))
        }
  }

  private fun householdPost(context: Context, body: JSONObject): FleetHttpServer.Response =
      if (body.optBoolean("share")) shareKey(context) else {
        val key = HouseholdKey.fromHex(body.optString("key"))
        if (!HouseholdKey.isValidKey(key) || !HouseholdKey.set(context, key!!)) json(400, error("invalid_key"))
        else {
          android.util.Log.i("ImmortalHousehold", "household key provisioned fp=${HouseholdKey.fingerprint(key)}")
          json(200, ok().put("fingerprint", HouseholdKey.fingerprint(key)))
        }
      }

  /**
   * A newly installed phone adopts the household's existing key instead of minting its own (which
   * would lock the other phones out). Paired phones may already replace the key, so reading it back
   * to one grants nothing new.
   */
  private fun shareKey(context: Context): FleetHttpServer.Response = HouseholdKey.get(context)?.let { k ->
    json(200, ok().put("key", HouseholdKey.toHex(k)).put("fingerprint", HouseholdKey.fingerprint(k)))
  } ?: json(404, error("no_household_key"))
}

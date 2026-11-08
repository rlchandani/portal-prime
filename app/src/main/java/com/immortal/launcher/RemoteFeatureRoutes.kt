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

  private val routes = ConcurrentHashMap<String, Route>()

  fun register(path: String, route: Route) {
    require(path.startsWith("/remote/") && path.length > "/remote/".length) { "feature routes live under /remote/" }
    check(routes.putIfAbsent(path, route) == null || routes[path] === route) { "route $path already owned" }
  }

  fun lookup(path: String): Route? = routes[path]

  fun ok(): JSONObject = JSONObject().put("ok", true)
  fun error(code: String): JSONObject = JSONObject().put("ok", false).put("error", code)
  fun json(status: Int, body: JSONObject) = FleetHttpServer.Response(status, body.toString())

  /** Contract route: the phone provisions the household key; read-back shows only a fingerprint. */
  fun registerHouseholdKey() = register("/remote/household") { context, method, body ->
    when (method) {
      "GET" -> json(200, ok().put("configured", HouseholdKey.get(context) != null)
          .put("fingerprint", HouseholdKey.get(context)?.let(HouseholdKey::fingerprint) ?: JSONObject.NULL))
      "POST" -> if (body.optBoolean("share")) shareKey(context) else {
        val key = HouseholdKey.fromHex(body.optString("key"))
        if (!HouseholdKey.isValidKey(key) || !HouseholdKey.set(context, key!!)) json(400, error("invalid_key"))
        else {
          android.util.Log.i("ImmortalHousehold", "household key provisioned fp=${HouseholdKey.fingerprint(key)}")
          json(200, ok().put("fingerprint", HouseholdKey.fingerprint(key)))
        }
      }
      else -> json(405, error("method_not_allowed"))
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

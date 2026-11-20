/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject

/** One fleet route: a request in, a response out. */
internal typealias FleetHandler = (FleetHttpServer.Request) -> FleetHttpServer.Response

/** The fleet API's JSON envelope and request helpers. Pure (no Context). */
internal object FleetReply {
  fun ok(): JSONObject = JSONObject().put("ok", true)

  fun err(code: String): JSONObject = JSONObject().put("ok", false).put("error", code)

  fun json(status: Int, obj: JSONObject) = FleetHttpServer.Response(status, obj.toString())

  /** A blank body reads as `{}`; anything that isn't a JSON object is null. */
  fun parseJson(s: String): JSONObject? = runCatching { JSONObject(if (s.isBlank()) "{}" else s) }.getOrNull()

  inline fun requireMethod(
      method: String,
      req: FleetHttpServer.Request,
      block: () -> FleetHttpServer.Response,
  ): FleetHttpServer.Response = if (req.method == method) block() else methodNotAllowed()

  fun methodNotAllowed() = json(405, err("method_not_allowed"))
}

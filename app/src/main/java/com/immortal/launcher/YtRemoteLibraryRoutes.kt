/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log
import com.immortal.launcher.youtube.YtAndroid
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * The phone's Music search + history, under `/remote/ytmusic/…` with the same paired-session auth
 * as [YtRemoteRoute] (enforced upstream by [RemoteCompanionRoutes]). Wire format:
 * [YtRemoteLibraryProtocol]. Suggestions are local only; search spends this Portal's daily budget.
 */
internal object YtRemoteLibraryRoutes {
  const val HISTORY = "${YtRemoteRoute.PATH}/history"
  const val SUGGEST = "${YtRemoteRoute.PATH}/suggest"
  const val SEARCH = "${YtRemoteRoute.PATH}/search"
  private const val TAG = "HearthpaneYtRemote"

  // Stable instances: registering the same path twice is only allowed for the same route object.
  private val historyRoute = RemoteFeatureRoutes.Route(::history)
  private val suggestRoute = RemoteFeatureRoutes.Route(::suggest)
  private val searchRoute = RemoteFeatureRoutes.Route(::search)

  fun install() {
    RemoteFeatureRoutes.register(HISTORY, historyRoute)
    RemoteFeatureRoutes.register(SUGGEST, suggestRoute)
    RemoteFeatureRoutes.register(SEARCH, searchRoute)
  }

  private fun history(context: Context, method: String, body: JSONObject): FleetHttpServer.Response = when (method) {
    "GET" -> RemoteFeatureRoutes.json(200, YtRemoteLibraryProtocol.history(MusicHistoryFeature.listing()))
    "POST" -> when (val action = YtRemoteLibraryProtocol.historyAction(body)) {
      YtRemoteLibraryProtocol.HistoryAction.Clear -> done { MusicHistoryFeature.clear(context) }
      is YtRemoteLibraryProtocol.HistoryAction.Remove -> done { MusicHistoryFeature.remove(context, action.videoId) }
      is YtRemoteLibraryProtocol.HistoryAction.Invalid ->
        RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error(action.code))
    }
    else -> notAllowed()
  }

  private fun suggest(context: Context, method: String, body: JSONObject): FleetHttpServer.Response {
    if (method != "POST") return notAllowed()
    val q = YtRemoteLibraryProtocol.query(body, MusicSuggest.MIN_CHARS) ?: return invalidQuery()
    return RemoteFeatureRoutes.json(200, YtRemoteLibraryProtocol.suggestions(MusicLocalSearch.suggest(context, q)))
  }

  /** Runs on the HTTP worker thread; the library does its own IO dispatching. */
  private fun search(context: Context, method: String, body: JSONObject): FleetHttpServer.Response {
    if (method != "POST") return notAllowed()
    val q = YtRemoteLibraryProtocol.query(body, MusicV3Rules.MIN_QUERY) ?: return invalidQuery()
    val library = runCatching { YtAndroid.library(context) }
        .onFailure { Log.w(TAG, "library unavailable: ${it.javaClass.simpleName}") }.getOrNull()
        ?: return RemoteFeatureRoutes.json(503, RemoteFeatureRoutes.error("library_unavailable"))
    val result = runBlocking { library.search(q) }
    val reply = YtRemoteLibraryProtocol.search(result, library.searchesRemainingToday())
    if (reply.status == SUCCESS) MusicLocalSearch.remember(context, q)
    return RemoteFeatureRoutes.json(reply.status, reply.body)
  }

  private inline fun done(block: () -> Unit): FleetHttpServer.Response {
    block()
    return RemoteFeatureRoutes.json(200, RemoteFeatureRoutes.ok())
  }

  private fun invalidQuery() = RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error("invalid_query"))
  private fun notAllowed() = RemoteFeatureRoutes.json(405, RemoteFeatureRoutes.error("method_not_allowed"))

  private const val SUCCESS = 200
}

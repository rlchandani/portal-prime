/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.Build
import com.immortal.launcher.settings.SettingsRegistry
import org.json.JSONArray
import org.json.JSONObject

/** Scoped paired management: never forward caller data to unrestricted Fleet routes. */
internal class RemoteCompanionRoutes(private val context: Context) {
  private val operations get() = RemoteCompanionOperations.get(context)
  @Volatile private var catalog: List<CatalogApp>? = null
  private fun catalog(): List<CatalogApp> {
    catalog?.let { return it }
    return synchronized(this) {
      catalog ?: runCatching { context.assets.open("catalog.json").use { StoreCatalog.parse(it.readBytes().toString(Charsets.UTF_8)) } }.getOrDefault(emptyList()).also {
        catalog = it
        StoreCatalog.loadCatalog(context) { fresh -> catalog = fresh }
      }
    }
  }

  fun handle(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val token = RemoteRoutes.bearer(req.header("authorization"))
    if (token == null || !RemotePairing.isEnabled(context) || !RemotePairing.isValidSession(context, token)) return json(401, error("unauthorized"))
    if (req.path in setOf("/remote/photos", "/remote/photo", "/remote/photo-upload")) return RemoteCompanionPhotos.handle(req)
    val allowed = when (req.path) {
      "/remote/companion", "/remote/catalog", "/remote/operations" -> setOf("GET")
      "/remote/operation", "/remote/scene" -> setOf("POST")
      else -> setOf("GET", "POST")
    }
    if (req.method !in allowed) return json(405, error("method_not_allowed"))
    if (req.contentLength > 256 * 1024) return json(413, error("body_too_large"))
    val body = if (req.method == "POST") {
      val text = req.bodyText(256 * 1024)
      if (text.toByteArray(Charsets.UTF_8).size != req.contentLength) return json(400, error("incomplete_body"))
      runCatching { JSONObject(text) }.getOrNull() ?: return json(400, error("bad_json"))
    } else JSONObject()
    return when (req.path) {
      "/remote/companion" -> companion()
      "/remote/layout" -> layout(req.method, body)
      "/remote/snapshot" -> snapshot(req.method, body)
      "/remote/catalog" -> catalogResponse()
      "/remote/operations" -> { operations.reconcileInstalls(); json(200, ok().put("operations", operations.history.list())) }
      "/remote/operation" -> operation(body)
      "/remote/scenes" -> scenes(req.method, body)
      "/remote/scene" -> scene(body)
      "/remote/jarvis" -> if (req.method == "GET") json(200, ok().put("jarvis", RemoteCompanionJarvis.status(context))) else RemoteCompanionJarvis.configure(context, body).let { json(if (it.optBoolean("ok")) 200 else 409, it) }
      else -> json(404, error("not_found"))
    }
  }

  private fun companion(): FleetHttpServer.Response {
    val presence = PresenceHub.current
    val plan = InstallDaemon.installPlan(context)
    val jarvis = RemoteCompanionJarvis.status(context)
    val pi = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    return json(200, ok().put("protocolVersion", 1).put("deviceId", RemoteCompanionStore.id(context))
        .put("name", FleetConfig.name(context)).put("model", Build.MODEL).put("apiLevel", Build.VERSION.SDK_INT)
        .put("app", JSONObject().put("versionCode", code).put("versionName", pi.versionName))
        .put("capabilities", JSONObject().put("layout", true).put("snapshot", true).put("catalog", catalog().isNotEmpty()).put("operations", true).put("scenes", true).put("sceneRevisions", true).put("photos", RemoteCompanionPhotos.available()).put("photoLibrary", true).put("jarvis", jarvis.optBoolean("supported")))
        .put("presence", JSONObject().put("presence", presence.presence.name).put("screen", presence.screen.name).put("confident", presence.confident))
        .put("install", JSONObject().put("mode", plan.mode).put("daemonAvailable", plan.daemonAvailable).put("legacyInstaller", plan.legacy).put("dialogFixed", plan.dialogFixed).put("paused", plan.paused).put("autoConfirm", SettingsGuard.isInstallConfirmEnabled(context)))
        .put("canWriteSecureSettings", SettingsGuard.canWriteSecureSettings(context)))
  }

  private fun state(): Pair<JSONObject, JSONObject> = RemoteSnapshotPolicy.values(context, SettingsRegistry.domains) to RemoteLayoutStore.read(context)
  private fun revision(): String = state().let { RemoteSnapshotPolicy.revision(it.first, it.second) }

  private fun layout(method: String, body: JSONObject): FleetHttpServer.Response = synchronized(RemoteCompanionStore.mutationLock) {
    if (method == "POST") {
      if (!body.only("layout", "expectedRevision")) return@synchronized json(400, error("unknown_field"))
      if (body.has("expectedRevision") && body.opt("expectedRevision") != revision()) return@synchronized json(409, error("stale_revision"))
      val candidate = body.optJSONObject("layout") ?: return@synchronized json(400, error("layout_required"))
      val validation = RemoteLayoutPolicy.validate(candidate, HomeWidgetStore.load(context))
      if (!validation.valid) return@synchronized invalid(validation)
      RemoteLayoutStore.apply(context, candidate)
    }
    json(200, ok().put("layout", RemoteLayoutStore.read(context)).put("revision", revision()).put("widgetKinds", JSONArray(RemoteLayoutPolicy.kinds.sorted())))
  }
  private fun snapshot(method: String, body: JSONObject): FleetHttpServer.Response = synchronized(RemoteCompanionStore.mutationLock) {
    val id = RemoteCompanionStore.id(context)
    val applied = JSONArray()
    if (method == "POST") {
      val wrapped = body.has("snapshot")
      if (wrapped && !body.only("snapshot", "expectedRevision")) return@synchronized json(400, error("unknown_field"))
      val candidate = if (wrapped) body.optJSONObject("snapshot") ?: return@synchronized json(400, error("snapshot_required")) else body
      val currentRevision = revision()
      // An explicit revision permits a reviewed old export; absent it, stale data is refused.
      val expected = if (wrapped && body.has("expectedRevision")) body.opt("expectedRevision") else candidate.opt("revision")
      if (expected != currentRevision) return@synchronized json(409, error("stale_revision"))
      val validation = RemoteSnapshotPolicy.validateSnapshot(candidate, id, currentRevision, SettingsRegistry.domains, HomeWidgetStore.load(context), expected as String)
      if (!validation.valid) return@synchronized invalid(validation)
      val domains = candidate.getJSONObject("domains")
      for (domainId in domains.fieldKeys().sorted()) {
        val values = domains.getJSONObject(domainId)
        val result = RemoteSnapshotPolicy.applyVerified(context, SettingsRegistry.domain(domainId)!!, values)
        applied.put(result)
        if (!result.getBoolean("ok")) return@synchronized json(if (result.optString("error") == "apply_failed") 500 else 409,
            error(result.getString("error")).put("applied", applied).put("layoutApplied", false))
      }
      if (runCatching { RemoteLayoutStore.apply(context, candidate.getJSONObject("layout")) }.isFailure)
        return@synchronized json(500, error("layout_apply_failed").put("applied", applied).put("layoutApplied", false))
    }
    val (domains, layout) = state()
    json(200, ok().put("snapshot", JSONObject().put("schemaVersion", 1).put("deviceId", id).put("revision", RemoteSnapshotPolicy.revision(domains, layout)).put("domains", domains).put("layout", layout))
        .put("applied", applied).put("excluded", JSONArray(listOf("credentials", "sessionTokens", "connectionStrings", "privateAppData", "photos", "thirdPartyWidgetData"))))
  }

  private fun catalogResponse(): FleetHttpServer.Response {
    val entries = catalog()
    val versions = InstalledPackageVersions.snapshot(context, entries.mapTo(HashSet()) { it.packageName })
    val apps = JSONArray()
    for (app in entries) {
      val installed = if (versions != null) versions.versionCode(app.packageName)
          else StoreCatalog.installedVersionCode(context, app.packageName)
      apps.put(JSONObject().put("packageName", app.packageName).put("name", app.name).put("description", app.description).put("category", app.category)
          .put("minSdk", app.minSdk ?: JSONObject.NULL).put("compatible", RemoteCompanionOperationPolicy.compatible(app, Build.VERSION.SDK_INT, operations.deviceKind()))
          .put("installed", installed != null).put("installedVersionCode", installed ?: JSONObject.NULL).put("versionCode", app.versionCode ?: JSONObject.NULL)
          .put("updateAvailable", if (installed != null && app.versionCode != null) app.versionCode > installed else JSONObject.NULL)
          .put("iconUrl", app.iconUrl ?: JSONObject.NULL).put("devices", JSONArray(app.devices)))
    }
    return json(200, ok().put("apps", apps))
  }
  private fun operation(body: JSONObject): FleetHttpServer.Response {
    val validation = RemoteCompanionOperationPolicy.validate(body, catalog(), Build.VERSION.SDK_INT, operations.deviceKind())
    if (!validation.valid) return invalid(validation)
    var target = body
    if (body.getString("type") in setOf("retry", "cancel")) {
      val old = operations.history.find(body.getString("id")) ?: return json(404, error("unknown_operation"))
      if (body.getString("type") == "cancel") return json(409, error("cancellation_unsupported").put("operation", old).put("cancelSupported", false))
      if (!old.optBoolean("retrySupported")) return json(409, error("retry_unsupported").put("operation", old))
      if (old.getString("type") == "scene") return scene(old.getJSONObject("target"))
      target = old.getJSONObject("target")
      val retry = RemoteCompanionOperationPolicy.validate(target, catalog(), Build.VERSION.SDK_INT, operations.deviceKind())
      if (!retry.valid) return invalid(retry)
    }
    val immutable = JSONObject(target.toString())
    val type = immutable.getString("type")
    val app = catalog().firstOrNull { it.packageName == immutable.optString("packageName") }
    val op = operations.start(type, immutable) { id ->
      JSONArray().put(if (type == "action") operations.action(immutable) else operations.install(id, app!!, type == "update"))
    } ?: return json(409, error(if (type in setOf("install", "update") && operations.history.hasUncertainInstall()) "install_state_uncertain" else "busy"))
    return json(202, ok().put("operation", op))
  }
  private fun scenes(method: String, body: JSONObject): FleetHttpServer.Response = synchronized(RemoteCompanionStore.mutationLock) {
    var saved = RemoteCompanionStore.scenes(context)
    fun sceneRevision(value: JSONArray) = RemoteSnapshotPolicy.revision(JSONObject().put("scenes", value), JSONObject())
    if (method == "POST") {
      if (!body.only("scenes", "expectedRevision")) return@synchronized json(400, error("unknown_field"))
      // Optional for older clients. New editors capture this revision before editing; the
      // comparison and replacement share the same lock, including schedule cancellation.
      if (body.has("expectedRevision") && body.opt("expectedRevision") != sceneRevision(saved))
        return@synchronized json(409, error("stale_revision"))
      val scenes = body.optJSONArray("scenes") ?: return@synchronized json(400, error("scenes_required"))
      val valid = RemoteScenes.validate(scenes)
      if (!valid.valid) return@synchronized invalid(valid)
      RemoteScenes.replace(context, scenes)
      saved = RemoteCompanionStore.scenes(context)
    }
    json(200, ok().put("scenes", saved).put("revision", sceneRevision(saved)).put("scheduleMode", "local_inexact"))
  }
  private fun scene(body: JSONObject): FleetHttpServer.Response {
    if (!body.only("id") || !RemoteScenePolicy.validId(body.opt("id"))) return json(400, error("id_required"))
    val scenes = RemoteCompanionStore.scenes(context)
    val scene = (0 until scenes.length()).mapNotNull { scenes.optJSONObject(it) }.firstOrNull { it.optString("id") == body.getString("id") } ?: return json(404, error("unknown_scene"))
    val op = RemoteScenes.start(context, scene) ?: return json(409, error("busy"))
    return json(202, ok().put("operation", op))
  }
  private fun invalid(v: RemoteCompanionValidation) = json(400, error("validation_failed").put("errors", JSONArray(v.errors)))
  private fun ok() = JSONObject().put("ok", true)
  private fun error(code: String) = JSONObject().put("ok", false).put("error", code)
  private fun json(status: Int, body: JSONObject) = FleetHttpServer.Response(status, body.toString())
}

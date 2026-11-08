/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject

internal enum class OtaPhase(val wire: String) {
  IDLE("idle"),
  CHECKING("checking"),
  UP_TO_DATE("up_to_date"),
  AVAILABLE("available"),
  DOWNLOADING("downloading"),
  INSTALLING("installing"),
  FAILED("failed"),
}

/** Immutable snapshot of the updater, shared by Settings, `/update/ota` and `/remote/ota`. */
internal data class OtaState(
    val installedVersionCode: Long = 0,
    val installedVersionName: String = "",
    val phase: OtaPhase = OtaPhase.IDLE,
    val available: OtaManifest? = null,
    /** Wall-clock time of the last completed check. */
    val lastCheckAtMs: Long? = null,
    val error: OtaError? = null,
    val waitingFor: OtaWait? = null,
    val devMode: Boolean = false,
) {
  val busy: Boolean
    get() = phase == OtaPhase.CHECKING || phase == OtaPhase.DOWNLOADING || phase == OtaPhase.INSTALLING
}

internal enum class OtaAction(val wire: String) { CHECK("check"), INSTALL("install") }

internal data class OtaRequest(val action: OtaAction, val force: Boolean)

/**
 * Wire format shared by fleet `/update/ota` and paired-phone `/remote/ota`:
 * state `{installed:{versionCode,versionName}, status, available:{versionCode,versionName,notes,size}|null,
 * lastCheckAt|null, error:{code,message}|null, waitingFor|null, devMode}`; POST `{action: check|install, force?}`.
 */
internal object OtaWire {
  fun decode(body: JSONObject): OtaRequest? {
    val action = OtaAction.values().firstOrNull { it.wire == body.optString("action") } ?: return null
    return OtaRequest(action, body.optBoolean("force", false))
  }

  fun toJson(s: OtaState): JSONObject = JSONObject()
      .put("installed", JSONObject().put("versionCode", s.installedVersionCode).put("versionName", s.installedVersionName))
      .put("status", s.phase.wire)
      .put("available", s.available?.let(::available) ?: JSONObject.NULL)
      .put("lastCheckAt", s.lastCheckAtMs ?: JSONObject.NULL)
      .put("error", s.error?.let { JSONObject().put("code", it.code).put("message", it.message) } ?: JSONObject.NULL)
      .put("waitingFor", s.waitingFor?.wire ?: JSONObject.NULL)
      .put("devMode", s.devMode)

  private fun available(m: OtaManifest): JSONObject = JSONObject()
      .put("versionCode", m.versionCode)
      .put("versionName", m.versionName)
      .put("notes", m.notes)
      .put("size", m.size)
}

internal data class OtaRowCopy(val title: String, val subtitle: String, val action: String, val enabled: Boolean)

/** Settings row copy: "Hearthpane 1.228" · "Up to date · Checked 3:12 PM" · "Check for updates". */
internal object OtaCopy {
  const val CHECK = "Check for updates"
  const val UPDATE_NOW = "Update now"
  private const val SEPARATOR = " · "

  fun row(s: OtaState, checkedLabel: String?): OtaRowCopy {
    val title = listOf(Brand.NAME, s.installedVersionName).filter { it.isNotBlank() }.joinToString(" ")
    if (s.devMode) return OtaRowCopy(title, OtaError.DEV_MODE.message, CHECK, enabled = false)
    val next = s.available?.let { "${Brand.NAME} ${it.versionName}" } ?: Brand.NAME
    val checked = checkedLabel?.let { "Checked $it" }
    val subtitle = when (s.phase) {
      OtaPhase.IDLE -> "Not checked yet"
      OtaPhase.CHECKING -> "Checking for updates…"
      OtaPhase.UP_TO_DATE -> join("Up to date", checked)
      OtaPhase.AVAILABLE -> join("$next available", s.available?.notes)
      OtaPhase.DOWNLOADING -> "Downloading $next…"
      OtaPhase.INSTALLING -> "Installing $next. ${Brand.NAME} restarts when it's done."
      OtaPhase.FAILED -> join(s.error?.message ?: OtaError.INSTALL.message, checked)
    }
    val action = if (s.available != null && s.phase != OtaPhase.UP_TO_DATE) UPDATE_NOW else CHECK
    return OtaRowCopy(title, subtitle, action, enabled = !s.busy)
  }

  private fun join(vararg parts: String?): String = parts.filterNot { it.isNullOrBlank() }.joinToString(SEPARATOR)
}

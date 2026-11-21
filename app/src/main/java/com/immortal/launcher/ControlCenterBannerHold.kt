/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject

/**
 * The real Control Center alert [ControlCenterBanners] holds through an accessibility unbind
 * ([OverlayHold]): the alert and whether it takes over the screen. Previews are never held.
 */
internal object CcBannerHold {
  const val SLOT = "cc"
  private const val MAX_TEXT = 160
  private const val MAX_ID = 200

  data class Entry(val alert: CcAlert, val takeover: Boolean)

  /** What owns the banner slot for [CcBannerSlot]: a held real alert (showing or parked) first, else what is drawn. */
  fun slot(hold: OverlayHold<Entry>, drawn: CcBannerSlot.Showing?): CcBannerSlot.Showing? =
      hold.held?.let { CcBannerSlot.Showing(it.payload.alert.severity, preview = false) } ?: drawn

  fun encode(e: Entry): JSONObject = JSONObject()
      .put("id", e.alert.id.take(MAX_ID)).put("severity", e.alert.severity.name).put("kind", e.alert.kind.name)
      .put("entity", e.alert.entityId.take(MAX_ID)).put("title", e.alert.title.take(MAX_TEXT))
      .put("body", e.alert.body.take(MAX_TEXT)).put("since", e.alert.since).put("action", e.alert.action.name)
      .put("cls", e.alert.deviceClass ?: JSONObject.NULL).put("takeover", e.takeover)

  /** Null for anything malformed, and for a preview (never held, so never restored). */
  fun decode(o: JSONObject?): Entry? {
    if (o == null) return null
    val id = o.optString("id").takeIf { it.isNotBlank() && it.length <= MAX_ID } ?: return null
    val severity = enumOrNull<CcSeverity>(o.optString("severity")) ?: return null
    val kind = enumOrNull<CcKind>(o.optString("kind")) ?: return null
    val action = enumOrNull<CcAlertAction>(o.optString("action")) ?: return null
    val title = o.optString("title").take(MAX_TEXT).ifBlank { return null }
    val cls = if (o.isNull("cls")) null else o.optString("cls").takeIf { it.isNotBlank() && it.length <= MAX_TEXT }
    val alert = CcAlert(id, severity, kind, o.optString("entity").take(MAX_ID), title, o.optString("body").take(MAX_TEXT),
        o.optLong("since"), action, cls)
    if (CcBannerPreview.isPreview(alert)) return null
    return Entry(alert, o.optBoolean("takeover"))
  }

  private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? = enumValues<E>().firstOrNull { it.name == name }
}

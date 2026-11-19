/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import android.content.Context
import com.immortal.launcher.ImmortalSettings
import com.immortal.launcher.SettingsTree
import com.immortal.launcher.approvedSettingsSearch
import com.immortal.launcher.approvedSettingsSearchIndex
import com.immortal.launcher.settings.SettingsRegistry
import org.json.JSONObject

/**
 * The Settings page in front, for `screen_context`: its title, its path, and the rows it lists (from the same index
 * Settings search uses) with each registry-backed row's current value. Advanced rows only when Advanced is on.
 */
internal object SettingsJarvisContext {
  fun build(context: Context, pageId: String): JSONObject {
    val advanced = ImmortalSettings.advancedSettings(context)
    val title = SettingsTree.page(pageId)?.title ?: pageId
    val path = (listOf("Settings") + SettingsTree.chainOf(pageId).mapNotNull { SettingsTree.page(it)?.title })
        .distinct().joinToString(" › ")
    val values = HashMap<String, JSONObject?>()
    val rows = approvedSettingsSearchIndex()
        .filter { it.page == pageId && it.control != null && !it.alias && (advanced || !it.advanced) }
        .distinctBy { it.control }
        .map { row ->
          val control = row.control.orEmpty()
          val domain = control.substringBefore('.', "")
          val key = control.substringAfter('.', "")
          val flat = values.getOrPut(domain) { runCatching { SettingsRegistry.domain(domain)?.flatJson(context) }.getOrNull() }
          ScreenContext.SettingsRow(control, row.title, flat?.opt(key)?.takeUnless { it == JSONObject.NULL })
        }
    return ScreenContext.settings(title, path, rows)
  }

  /** Rows anywhere in Settings matching [query], ranked like Settings search; each word tried alone if none match. */
  fun search(query: String): List<ScreenContext.Match> {
    val index = approvedSettingsSearchIndex()
    val rows = { q: String -> approvedSettingsSearch(index, q).filter { it.control != null && !it.alias } }
    val hits = rows(query).ifEmpty { query.split(' ').filter { it.length > 2 }.flatMap(rows).distinctBy { it.control } }
    return hits.take(ScreenContext.MAX_MATCHES).map { hit ->
      val page = SettingsTree.page(hit.page)?.title ?: hit.page
      ScreenContext.Match(hit.control.orEmpty(), hit.title, listOf(hit.crumb, page).filter { it.isNotBlank() && it != "Page" }.joinToString(" › "))
    }
  }

  /** The page holding [control], for a highlight on a row of another page. */
  fun pageOf(control: String): String? = approvedSettingsSearchIndex().firstOrNull { it.control == control }?.page
}

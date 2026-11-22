/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/** Shared HA dashboard test entities (placeholder names only). */
internal fun haDetail(id: String, state: String = "on", bri: Int? = null, unit: String = "", cls: String? = null,
    target: Float? = null, current: Float? = null, action: String? = null, name: String = id.substringAfter('.'),
    step: Float? = null) =
    HaDashboardApi.Detail(entityId = id, domain = id.substringBefore('.'), friendlyName = name, state = state,
        brightnessPct = bri, currentTemp = current, targetTemp = target, hvacAction = action, mediaTitle = null,
        mediaArtist = null, unit = unit, deviceClass = cls, tempStep = step)

internal fun haArea(id: String, vararg details: HaDashboardApi.Detail) =
    HaDashboardApi.Area(id, id.replaceFirstChar { it.uppercase() }, details.map { it.entityId }, details.toList())

internal fun haHouse(vararg areas: HaDashboardApi.Area, scenes: List<HaDashboardApi.Scene> = emptyList()) =
    HaDashboardApi.House(areas.toList(), scenes)

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Every floor's [CcPlanModel], built on the engine thread before a snapshot is published
 * ([CcUiState.planModels]), so a floor switch or an HA update never builds one inside a frame.
 * A floor keeps its model instance while nothing it shows changed ([CcModelKey]); equal
 * instances let every plan layer skip.
 */
internal class CcPlanModelCache {
  private val models = HashMap<String, Pair<CcModelKey, CcPlanModel>>()
  private val deps = HashMap<String, Pair<CcFloor, Array<String>>>()

  fun of(s: CcUiState): Map<String, CcPlanModel> {
    val plan = s.plan ?: return emptyMap()
    val out = LinkedHashMap<String, CcPlanModel>(plan.floors.size * 2)
    for (floor in plan.floors) {
      val ids = deps[floor.id]?.takeIf { it.first == floor }?.second ?: CcPlanDeps.ids(floor).also { deps[floor.id] = floor to it }
      val key = CcModelKey(floor, CcPlanDeps.of(ids, s.entities), s.settings.forPlanModel(), s.minute, s.appliancesDone)
      out[floor.id] = models[floor.id]?.takeIf { it.first == key }?.second
          ?: CcPlanModel.build(floor, s.entities, s.settings, s.now, s.appliancesDone).also { models[floor.id] = key to it }
    }
    // Floors removed from the plan don't linger.
    models.keys.retainAll(out.keys)
    deps.keys.retainAll(out.keys)
    return out
  }

  fun clear() { models.clear(); deps.clear() }
}

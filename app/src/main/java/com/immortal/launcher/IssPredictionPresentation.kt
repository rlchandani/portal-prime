/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Shared wording for a failed prediction or a successful search with no qualifying passes. */
internal fun issPredictionMessage(prediction: IssPasses.Prediction): String? =
    when (prediction.problem) {
        IssPasses.PredictionProblem.LOCATION ->
            "Your location isn’t available. Set the location used by Weather, then refresh passes."
        IssPasses.PredictionProblem.ORBIT ->
            "ISS orbit data isn’t available. Check your connection, then refresh passes."
        IssPasses.PredictionProblem.CALCULATION ->
            "ISS passes couldn’t be calculated. Refresh passes to try again."
        null -> if (prediction.passes.isEmpty())
            "No passes above 10° are predicted here in the next 36 hours."
        else null
    }

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context

/**
 * Where this Portal keeps its Grandma mode and auto-answer rules. Deliberately outside the
 * settings registry (so neither the phone remote nor `/remote/settings` can reach it).
 * Placeholder until the Grandma mode slice lands: everything off.
 */
internal object CallAutoAnswerStore {
  fun rules(@Suppress("UNUSED_PARAMETER") context: Context): AutoAnswerRules = AutoAnswerRules()
}

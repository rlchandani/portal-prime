/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.Trace

/**
 * A named section in system traces (`atrace -a com.immortal.launcher.debug`), for measuring the
 * Control Center on a Portal. Costs one native flag check when nobody is tracing.
 */
internal inline fun <T> ccTrace(name: String, block: () -> T): T {
  Trace.beginSection(name)
  try {
    return block()
  } finally {
    Trace.endSection()
  }
}

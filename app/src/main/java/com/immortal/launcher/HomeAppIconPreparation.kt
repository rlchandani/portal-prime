/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Reject hidden/duplicate entries before icon IO; claim a package only after success. */
internal inline fun <T, R : Any> prepareHomeAppIcons(
    candidates: Iterable<T>,
    packageName: (T) -> String,
    rawLabel: (T) -> String,
    hiddenLabel: (String) -> Boolean,
    prepare: (T, String) -> R,
): List<R> {
  val accepted = HashSet<String>()
  val result = ArrayList<R>()
  for (candidate in candidates) {
    val pkg = packageName(candidate)
    if (pkg in accepted) continue
    val entry = runCatching {
      val label = rawLabel(candidate)
      if (hiddenLabel(label)) null else prepare(candidate, label)
    }.getOrNull() ?: continue
    accepted.add(pkg)
    result.add(entry)
  }
  return result
}

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Constants for Hearthpane's wireless updates from GitHub Releases
 * (docs/plans/ota-github-releases-2026-11-08.md). The feed is fixed: there is no runtime override.
 */
internal object OtaConstants {
  /** Release repo path; every manifest and APK must live under it. */
  const val REPO_PATH = "/rlchandani/portal-prime/releases/"
  const val APK_PATH_PREFIX = REPO_PATH + "download/"
  const val FEED_URL = "https://github.com" + REPO_PATH + "latest/download/hearthpane-latest.json"

  /** SHA-256 of the release signing certificate; every OTA APK must be signed with exactly it. */
  const val PINNED_SIGNER_SHA256 = "4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6"

  const val SCHEMA = 1
  const val SHA256_HEX_LENGTH = 64
  const val MAX_MANIFEST_BYTES = 16 * 1024
  const val MAX_APK_BYTES = 200L * 1024 * 1024
  const val MAX_NOTES = 280
  const val MAX_VERSION_NAME = 40

  const val APK_HOST = "github.com"
  /** github.com answers release downloads with a redirect to one of these asset hosts. */
  val HOP_HOSTS = setOf(APK_HOST, "objects.githubusercontent.com", "release-assets.githubusercontent.com")
  const val MAX_REDIRECTS = 5
  const val CONNECT_TIMEOUT_MS = 15_000
  const val READ_TIMEOUT_MS = 30_000
  const val USER_AGENT = "Hearthpane-OTA"

  private const val MINUTE_MS = 60_000L
  private const val HOUR_MS = 60 * MINUTE_MS
  /** First check this long after process start, off the cold-start path. */
  const val STARTUP_DELAY_MS = 90_000L
  /** The scheduler wakes this often; it fetches only when [CHECK_INTERVAL_MS] has passed. */
  const val TICK_MS = 15 * MINUTE_MS
  const val CHECK_INTERVAL_MS = 6 * HOUR_MS
  /** A failed fetch (Wi-Fi not up yet, GitHub hiccup) is retried sooner than the normal interval. */
  const val FAILED_CHECK_RETRY_MS = 1 * HOUR_MS
  /** No touch for this long before an automatic install. */
  const val TOUCH_IDLE_MS = 10 * MINUTE_MS
  /** Preferred automatic install window, minutes of the local day: 02:00 to 05:00. */
  const val WINDOW_START_MIN = 2 * 60
  const val WINDOW_END_MIN = 5 * 60
  /** After this long pending, an idle Portal installs outside the preferred window too. */
  const val WINDOW_GRACE_MS = 72 * HOUR_MS
  /** How long a fleet/phone "check" waits for the result before answering 202. */
  const val CHECK_WAIT_MS = 40_000L
}

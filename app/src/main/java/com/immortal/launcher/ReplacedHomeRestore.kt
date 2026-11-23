/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** What the post-replace restore needs from Android; [ReplacedHomeReceiver] is the real one. */
internal interface ReplacedHomeHost {
  /** HOME resolves to this package (Hearthpane is still the default launcher). */
  fun isDefaultHome(): Boolean
  /** A call or the Meta Calls handoff owns the screen ([RoutineScheduler.busy]). */
  fun inCall(): Boolean
  /** One of this process's activities is resumed (Home, the frame, anything of ours). */
  fun ownActivityResumed(): Boolean
  /** Ask for Home like the Home button does; false when Android refused the start. */
  fun startHome(): Boolean
  fun schedule(delayMs: Long, block: () -> Unit)
  fun log(message: String)
}

internal enum class ReplacedHomeOutcome {
  /** Android had already brought one of our activities back; nothing started. */
  ALREADY_ON_TOP,
  /** We started Home and one of our activities is now resumed. */
  RESTORED,
  NOT_DEFAULT_HOME,
  IN_CALL,
  /** [ReplacedHomeRestore.MAX_STARTS] starts never resumed anything of ours. */
  GAVE_UP,
}

/**
 * Brings Hearthpane's Home back after our own package was replaced (OTA, fleet or adb install).
 *
 * Why: the replace force-stops this package ("installPackageLI") and Android removes every one of
 * our activities that has no saved state, then resumes the HOME activity left in the stack —
 * Meta's launcher. Whether our HomeActivity survives depends on timing: in the 1.331 -> 1.332 OTA
 * the photo frame was up, finished itself for the installer dialog, and Home had been resumed for
 * 64 ms when the kill landed ("Force removing ... HomeActivity: app died, no saved state"), so
 * Meta's Home stayed on top until someone pressed Home. Nothing here relied on that luck before.
 *
 * The restore never interrupts a call, never acts when another launcher is the default, never
 * force-stops anything, and retries a start that didn't land at most [MAX_STARTS] times inside a
 * receiver's goAsync() window. Once Home is resumed, the usual idle path brings the frame back.
 */
internal class ReplacedHomeRestore(private val host: ReplacedHomeHost) {
  fun run(done: (ReplacedHomeOutcome) -> Unit) = step(0, done)

  private fun step(starts: Int, done: (ReplacedHomeOutcome) -> Unit) {
    val outcome = when {
      !host.isDefaultHome() -> ReplacedHomeOutcome.NOT_DEFAULT_HOME
      host.inCall() -> ReplacedHomeOutcome.IN_CALL
      host.ownActivityResumed() ->
          if (starts == 0) ReplacedHomeOutcome.ALREADY_ON_TOP else ReplacedHomeOutcome.RESTORED
      starts >= MAX_STARTS -> ReplacedHomeOutcome.GAVE_UP
      else -> null
    }
    if (outcome != null) {
      host.log("package replaced: $outcome after $starts Home start(s)")
      done(outcome)
      return
    }
    val started = host.startHome()
    host.log("package replaced: restoring Home (start ${starts + 1}, accepted=$started)")
    host.schedule(VERIFY_DELAYS_MS[starts]) { step(starts + 1, done) }
  }

  companion object {
    const val MAX_STARTS = 3
    /** Wait after each start before checking it landed; the sum stays under goAsync()'s ~10 s. */
    private val VERIFY_DELAYS_MS = longArrayOf(1_500L, 2_500L, 3_500L)

    fun handles(action: String?): Boolean = action == ACTION_MY_PACKAGE_REPLACED

    /** [android.content.Intent.ACTION_MY_PACKAGE_REPLACED], spelled out so this file stays pure JVM. */
    private const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"
  }
}

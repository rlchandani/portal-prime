/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

/**
 * Whose memory a moment belongs to, if anyone's (plan §2.2), decided once when a conversation opens and kept for
 * the whole conversation, so it doesn't flip when the personal view times out.
 *
 * - The feature switch off → [Off] ([Reason.DISABLED]): nothing learned, nothing injected.
 * - Guest mode, Kids mode, or a recognised child anywhere in view → [Off]: kids are never profiled (§4.5).
 * - A recognised teen → [Off] ([Reason.TEEN]), owner decision D2's default.
 * - A recognised adult → their own scope; [On.othersNear] trims what Jarvis is told.
 * - Nobody recognised, or several adults → the household scope (D1's default), low-sensitivity kinds only.
 * Pure.
 */
internal sealed interface MemoryAccess {
  data class On(val scope: MemoryScope, val firstName: String?, val othersNear: Boolean) : MemoryAccess

  data class Off(val reason: Reason) : MemoryAccess

  enum class Reason { DISABLED, GUEST, CHILD, TEEN }

  /** Who's there, reduced to what memory needs (mirrors [com.immortal.launcher.WhosThereView]). */
  sealed interface Seen {
    data object Nobody : Seen

    data class One(val personId: String, val name: String?, val role: Role, val othersNear: Boolean) : Seen

    data class Several(val roles: List<Role>) : Seen
  }

  enum class Role { ADULT, TEEN, KID }

  /** The facts a decision is made from, read once. */
  data class Inputs(
      val enabled: Boolean,
      val guestMode: Boolean,
      val kidsMode: Boolean,
      val seen: Seen,
      /** D2: profile teens. Off in v1. */
      val teensLearn: Boolean = false,
  )

  companion object {
    fun decide(inputs: Inputs): MemoryAccess {
      if (!inputs.enabled) return Off(Reason.DISABLED)
      if (inputs.guestMode) return Off(Reason.GUEST)
      if (inputs.kidsMode) return Off(Reason.CHILD)
      return when (val seen = inputs.seen) {
        Seen.Nobody -> On(MemoryScope.Household, null, othersNear = false)
        is Seen.Several -> when {
          Role.KID in seen.roles -> Off(Reason.CHILD)
          Role.TEEN in seen.roles && !inputs.teensLearn -> Off(Reason.TEEN)
          else -> On(MemoryScope.Household, null, othersNear = true)
        }
        is Seen.One -> when {
          seen.role == Role.KID -> Off(Reason.CHILD)
          seen.role == Role.TEEN && !inputs.teensLearn -> Off(Reason.TEEN)
          !PERSON_ID.matches(seen.personId) -> On(MemoryScope.Household, null, seen.othersNear)
          else -> On(MemoryScope.Person(seen.personId), firstName(seen.name), seen.othersNear)
        }
      }
    }

    private val PERSON_ID = Regex("[A-Za-z0-9_-]{1,64}")

    /** Only the first name ever reaches Jarvis's prompt. */
    fun firstName(name: String?): String? =
        name?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.take(MAX_NAME)?.filter { it.isLetter() || it == '-' }
            ?.ifEmpty { null }

    private const val MAX_NAME = 24
  }
}

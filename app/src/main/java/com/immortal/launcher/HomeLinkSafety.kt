/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/** One safety picture: an emoji with the word people say out loud. */
@Immutable
internal data class HomeLinkPicture(val emoji: String, val word: String)

/** What both homes compare (mock `verifyScr`: four pictures and `4821 0937 6612`). */
@Immutable
internal data class HomeLinkSafetyNumber(val pictures: List<HomeLinkPicture>, val digits: String) {
  val displayDigits: String get() = digits.chunked(DIGIT_GROUP).joinToString(" ")

  /** For screen readers and for reading out on a phone call. */
  val spoken: String get() = pictures.joinToString(", ") { it.word } + ". " + displayDigits

  private companion object { const val DIGIT_GROUP = 4 }
}

/**
 * The short authentication string for linking two homes, like Signal's safety number: four
 * pictures (24 bits) and twelve digits (≈ 40 bits) from both homes' public keys and the joiner's
 * nonce. The commit-then-reveal exchange in [HomeLinkInviter] fixes every input before anyone can
 * see the other's, so a relay in the middle gets one guess, not a search. Pure.
 */
internal object HomeLinkSafety {
  const val PICTURE_COUNT = 4
  private const val DIGIT_COUNT = 12
  private const val NONCE_BYTES = 32
  private const val BITS_PER_PICTURE = 6
  /** The pictures use the first 3 bytes (4 × 6 bits); the digits the next 8 (bias < 1e-7). */
  private const val PICTURE_BYTES = 3
  private const val DIGIT_BYTES = 8
  private const val DIGIT_MODULUS = 1_000_000_000_000L
  private val DOMAIN = HomeLinkHash.utf8("hearthpane-homelink-sas-v1")

  /** 64 pictures with plain words. Single-codepoint emoji only (Android 9's font, no ZWJ). */
  val PICTURES: List<HomeLinkPicture> = listOf(
      "🦊" to "Fox", "🌙" to "Moon", "🍐" to "Pear", "⛵" to "Boat", "🐶" to "Dog", "🐱" to "Cat",
      "🐰" to "Rabbit", "🐻" to "Bear", "🐼" to "Panda", "🐯" to "Tiger", "🦁" to "Lion", "🐮" to "Cow",
      "🐷" to "Pig", "🐸" to "Frog", "🐵" to "Monkey", "🐔" to "Chicken", "🐧" to "Penguin", "🦉" to "Owl",
      "🐴" to "Horse", "🦄" to "Unicorn", "🐝" to "Bee", "🐢" to "Turtle", "🐙" to "Octopus", "🐟" to "Fish",
      "🐳" to "Whale", "🦋" to "Butterfly", "🐌" to "Snail", "🐘" to "Elephant", "🍎" to "Apple", "🍌" to "Banana",
      "🍇" to "Grapes", "🍓" to "Strawberry", "🍒" to "Cherries", "🍋" to "Lemon", "🍉" to "Watermelon",
      "🥕" to "Carrot", "🌽" to "Corn", "🍄" to "Mushroom", "🌵" to "Cactus", "🌻" to "Sunflower", "🌲" to "Tree",
      "🍁" to "Leaf", "⭐" to "Star", "🌈" to "Rainbow", "⚡" to "Lightning", "⛄" to "Snowman", "🔥" to "Fire",
      "🔑" to "Key", "🔔" to "Bell", "🎈" to "Balloon", "🎁" to "Gift", "⚽" to "Ball", "🚲" to "Bicycle",
      "🚗" to "Car", "🚂" to "Train", "🚀" to "Rocket", "🏠" to "House", "⏰" to "Clock", "🎸" to "Guitar",
      "🎩" to "Hat", "🌂" to "Umbrella", "📚" to "Books", "⚓" to "Anchor", "💡" to "Light bulb",
  ).map { (e, w) -> HomeLinkPicture(e, w) }

  /** Null when a key isn't a P-256 key or the nonce isn't 32 bytes of Base64. */
  fun derive(inviterKey: String, joinerKey: String, joinerNonce: String): HomeLinkSafetyNumber? {
    if (HomeLinkIdentity.decodePublic(inviterKey) == null || HomeLinkIdentity.decodePublic(joinerKey) == null) return null
    val nonce = HomeLinkHash.unb64(joinerNonce)?.takeIf { it.size == NONCE_BYTES } ?: return null
    val h = HomeLinkHash.hmac(nonce, DOMAIN, HomeLinkHash.utf8(inviterKey), HomeLinkHash.utf8(joinerKey))
    val bits = (h[0].toInt() and 0xFF shl 16) or (h[1].toInt() and 0xFF shl 8) or (h[2].toInt() and 0xFF)
    val mask = (1 shl BITS_PER_PICTURE) - 1
    val pictures = (0 until PICTURE_COUNT).map { i ->
      PICTURES[(bits ushr (BITS_PER_PICTURE * (PICTURE_COUNT - 1 - i))) and mask]
    }
    var wide = 0L
    for (i in PICTURE_BYTES until PICTURE_BYTES + DIGIT_BYTES) wide = (wide shl 8) or (h[i].toLong() and 0xFF)
    val digits = java.lang.Long.remainderUnsigned(wide, DIGIT_MODULUS).toString().padStart(DIGIT_COUNT, '0')
    return HomeLinkSafetyNumber(pictures, digits)
  }
}

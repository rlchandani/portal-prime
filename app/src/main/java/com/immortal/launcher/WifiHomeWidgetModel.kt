/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * What the Home Wi-Fi widget shows, projected from the Guest card's single source of truth
 * ([GuestConfig] + [GuestPasswordStore]) through the shared [GuestWifi.payload] builder. Pure JVM.
 */
internal sealed interface WifiWidgetState {
  /** No network saved yet: the friendly "Add your Wi-Fi" state. */
  data object NotSetUp : WifiWidgetState

  /** A secured network is saved but its password isn't, so no join QR can be built. */
  data object NeedsPassword : WifiWidgetState

  /** A joinable network. [toString] never includes the password. */
  class Ready(
      val ssid: String,
      val security: GuestWifi.Security,
      val payload: String,
      /** "" for open networks; shown only through [WifiPasswordReveal]. */
      val password: String,
      val hidePassword: Boolean,
  ) : WifiWidgetState {
    val hasPasswordRow: Boolean get() = security.needsPassword
    val securityLabel: String get() = security.label
    val qrDescription: String get() = WifiWidgetState.qrDescription(ssid)
    override fun toString(): String = "Ready(security=${security.wire}, hidePassword=$hidePassword)"
  }

  companion object {
    /** Spoken for the QR image; never includes the password. */
    fun qrDescription(ssid: String): String = "Wi-Fi QR code for $ssid. Scan with a phone camera to join."

    fun from(settings: GuestConfig.Settings, password: String): WifiWidgetState {
      if (settings.ssid.isBlank()) return NotSetUp
      val security = settings.wifiSecurity
      val secret = if (security.needsPassword) password else ""
      val payload = GuestWifi.payload(GuestWifi.Network(settings.ssid, secret, security, settings.hiddenNetwork))
          ?: return NeedsPassword
      return Ready(settings.ssid, security, payload, secret, settings.hidePassword)
    }
  }
}

/** How much the widget shows at its Home size: one slot keeps to the QR and the network name. */
internal enum class WifiWidgetLayout(val showsDetails: Boolean) {
  COMPACT(showsDetails = false),
  FULL(showsDetails = true);

  companion object {
    /** [spanX]/[spanY] are the stored half-row units ([HomePresentation.slotSpan]). */
    fun of(spanX: Int, spanY: Int): WifiWidgetLayout {
      val slot = HomePresentation.slotSpan(spanX, spanY)
      return if (slot.columns == 1 && slot.rows == 2) COMPACT else FULL
    }
  }
}

/**
 * The widget's "Show password" state: hidden until tapped, then visible for [REVEAL_MS]. When the
 * Guest card setting doesn't hide the password, it is simply shown (the card does the same).
 */
internal data class WifiPasswordReveal(val untilMs: Long? = null) {
  fun visible(nowMs: Long, hidePassword: Boolean): Boolean = !hidePassword || (untilMs != null && nowMs < untilMs)

  fun reveal(nowMs: Long): WifiPasswordReveal = WifiPasswordReveal(nowMs + REVEAL_MS)

  fun hide(): WifiPasswordReveal = WifiPasswordReveal()

  fun remainingMs(nowMs: Long): Long = untilMs?.let { (it - nowMs).coerceAtLeast(0L) } ?: 0L

  companion object {
    const val REVEAL_MS = 30_000L
  }
}

/** The enlarged, full-screen QR and the QR rendering rules shared with the widget. */
internal object WifiQrOverlay {
  /** Closes itself after two minutes so the screen can sleep again. */
  const val AUTO_CLOSE_MS = 120_000L
  /** The QR spec's minimum quiet zone; phone cameras misread tighter margins. */
  const val QUIET_ZONE_MODULES = 4
  /** Rendered QR bitmap size; scaled down crisply (no filtering) on screen. */
  const val QR_PX = 720
}

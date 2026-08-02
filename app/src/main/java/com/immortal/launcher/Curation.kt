/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/**
 * Which stock apps to hide, stash in folders, or relabel in the launcher grid.
 * Editable here (and a natural candidate for remote config later, like the
 * store catalog).
 *
 * The hidden set is mostly Meta's trusted-caller-gated apps (Contacts, Camera,
 * Photos, Help, …) — they reject our untrusted launch and can only be opened
 * from the stock launcher, so we hide them from the grid and route users to the
 * Portal Home bridge instead. Plus dead/internal entries.
 */
object Curation {

  /** Hidden by package id. */
  val hiddenPackages =
      setOf(
          "com.facebook.alohaapps.contacts", // gated → Portal Home bridge
          "com.facebook.alohaapps.launcher", // stock launcher — we have a clean bridge tile
          "com.facebook.alohaapps.superframe", // old slideshow
          "com.facebook.alohaservices.abilitymanager", // internal, no icon
          "com.facebook.aloha.app.cameraeditor", // avatar/debug camera, dead/gated
          "com.android.camera2", // stock camera crashes from our launch → bridge
          "com.facebook.alohasdk.ctsintentabsorber", // Android CTS test app, not user-facing
          "com.facebook.aloha.system.ripleyhome", // Portal TV stock home — Calls tile bridges to it
          "com.facebook.aloha.system.rcbootflow", // Portal TV boot/setup flow, not user-facing
          // Aloha Smart-Camera AI backend. It's a privileged background service, but its
          // LAUNCHER activity is an internal codec/AI-pipeline DEBUG console (Disable AI pipeline,
          // H264/H265 codec mode, pose-tracker fps…) — never meant for users. Hiding the tile
          // does not disable the service; it just removes a confusing/harmful entry point.
          "com.facebook.portal.aiservice",
          // Stock Android Settings — retired from the grid: our Immortal Settings now covers the
          // native controls AND surfaces the OS-only screens (Wi-Fi, Bluetooth, date/time, language,
          // accessibility, storage, all-settings) as link-out rows under its "System" group. So
          // there's no functionality lost and no redundant tile. (Portal Settings stays for the
          // Meta-specific device controls we don't replicate.)
          "com.android.settings",
      )

  /** Hidden by visible label (catches gated apps that share a package). */
  val hiddenLabels = setOf("Help", "Photos", "Debug Camera", "Avatar Editor")

  /**
   * Packages hidden only on specific devices, keyed by ro.product.device. Chrome
   * ships on every Portal under the same package id, but on the TV (device
   * "ripley") it renders blank and can't be driven by the remote, so we hide that
   * one tile there while leaving it on the touch models where it works fine.
   */
  val deviceHiddenPackages =
      mapOf(
          "ripley" to setOf("org.chromium.chrome"), // Portal TV: Chrome unusable
      )

  /** Package → folder name. Anything not listed shows on the main grid. */
  val folders =
      mapOf(
          "com.android.settings" to "Settings",
          "com.facebook.alohaapps.settings" to "Settings",
          "com.facebook.aloha.chargecontrol" to "Settings",
          "com.android.inputmethod.latin" to "Settings",
          "com.android.quicksearchbox" to "Settings",
          "com.android.tv.quicksettings" to "Settings", // Portal TV "Picture Mode" — useful, fold in
          // falcon's Amazon-link app: needed once to register, then useless. Provisioning auto-opens
          // it for the initial sign-in, so fold it into Settings rather than cluttering the grid.
          "com.amazon.alexa.multimodal.falcon" to "Settings",
      )

  /** Friendlier display names for a few stock apps. */
  val labelOverrides =
      mapOf(
          "com.android.settings" to "System Settings",
          "me.zhanghai.android.files" to "Material Files",
          "com.facebook.alohaapps.settings" to "Portal Settings",
          "com.android.quicksearchbox" to "Google Search",
          "com.amazon.alexa.multimodal.falcon" to "Alexa setup", // it's the account-link app, not the assistant
      )

  fun isHidden(
      packageName: String,
      label: String,
      deviceId: String = android.os.Build.DEVICE,
  ): Boolean =
      packageName in hiddenPackages ||
          label in hiddenLabels ||
          deviceHiddenPackages[deviceId]?.contains(packageName) == true

  fun folderFor(packageName: String): String? = folders[packageName]

  fun displayLabel(packageName: String, label: String): String =
      labelOverrides[packageName] ?: label

  /**
   * Per-package icon override map (drawable resource). Bundled premium tiles use
   * real brand marks. The full-color marks (Google, Chrome, Gmail, Spotify,
   * WhatsApp) are the official multicolor logos from github.com/gilbarbara/logos
   * ("logos" set, permissive — no attribution required), drawn centered on a
   * white squircle tile. The remaining single-glyph brand tiles (VLC, NewPipe,
   * AntennaPod, Firefox, Home Assistant) are a full-bleed square in the brand
   * color with a centered white glyph, which matches those apps' own icon look.
   * The uniform squircle mask is applied downstream by IconRenderer.
   */
  private val ICON_OVERRIDES: Map<String, Int> =
      mapOf(
          // Dusk Aurora icon system (mock 06): a two-stop diagonal gradient squircle with a white
          // line glyph, per app accent. Every stock app on the grid gets one so the whole grid
          // reads as one designed set (the mock's approved look) instead of a mixed bag.
          "org.chromium.chrome" to R.drawable.icon_browser_tile,
          "org.videolan.vlc" to R.drawable.icon_vlc_tile,
          "com.aurora.store" to R.drawable.icon_aurora,
          "org.fdroid.fdroid" to R.drawable.icon_fdroid_tile,
          "com.android.quicksearchbox" to R.drawable.icon_google_tile,
          "com.google.android.googlequicksearchbox" to R.drawable.icon_google_tile,
          "org.breezyweather" to R.drawable.icon_weather_tile,
          "com.portal.assistant" to R.drawable.icon_jarvis_tile,
          // com.portal.calendar is the app labelled "PortalHub" on this device (orange radio in
          // the mock); the red calendar tile stays available for a real calendar app.
          "com.portal.calendar" to R.drawable.icon_portalhub_tile,
          "com.facebook.alohaapps.settings" to R.drawable.icon_portal_settings_tile,
          "de.danoeh.antennapod" to R.drawable.icon_antennapod,
          "org.schabi.newpipe" to R.drawable.icon_newpipe,
          "me.zhanghai.android.files" to R.drawable.icon_folder,
          "com.facebook.aloha.app.whatsapp" to R.drawable.icon_whatsapp,
          "com.android.settings" to R.drawable.icon_settings,
          "com.immortal.launcher.debug" to R.drawable.icon_apps,
          // Not installed on current device, but ready if they land later:
          "org.mozilla.firefox" to R.drawable.icon_firefox,
          "com.spotify.music" to R.drawable.icon_spotify,
          "com.google.android.gm" to R.drawable.icon_gmail,
          "io.homeassistant.companion.android" to R.drawable.icon_homeassistant,
          "io.homeassistant.companion.android.minimal" to R.drawable.icon_homeassistant,
      )

  /**
   * Returns a drawable resource ID to use as an icon override for the given package,
   * or null to use the app's own icon. Allows bundling hero icons for key apps.
   */
  fun iconOverride(pkg: String): Int? = ICON_OVERRIDES[pkg]
}

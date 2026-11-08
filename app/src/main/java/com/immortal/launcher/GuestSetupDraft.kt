/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.GuestWifi.Security

internal enum class GuestSetupStep(val title: String) {
  NETWORK("Wi-Fi network"),
  PASSWORD("Password"),
  BASICS("House basics"),
  WELCOME("Welcome message"),
  FINISH("Preview & finish"),
}

/** What "Looks good" writes; a null secret is left as it is, "" clears it. */
internal class GuestSetupSave(val settings: GuestConfig.Settings, val password: String?, val doorCode: String?) {
  override fun toString() = "GuestSetupSave(password=${password != null}, doorCode=${doorCode != null})"
}

/**
 * The guest setup flow's immutable draft: every edit returns a new draft, nothing is written
 * until [save]. Holds the secrets in memory only while the flow is open; [toString] hides them.
 */
internal data class GuestSetupDraft(
    val settings: GuestConfig.Settings,
    val password: String,
    val doorCodeOn: Boolean,
    val doorCode: String,
    val step: GuestSetupStep,
    private val savedPassword: String,
    private val savedDoorCode: String,
) {
  val steps: List<GuestSetupStep>
    get() = GuestSetupStep.entries.filter { it != GuestSetupStep.PASSWORD || settings.wifiSecurity.needsPassword }

  val canFinish: Boolean get() = steps.all { problem(it) == null }

  /** An older free-text code ("4821#") stays valid until someone types a new one. */
  private val keepsSavedDoorCode: Boolean get() = savedDoorCode.isNotEmpty() && doorCode == savedDoorCode

  /** Why [step] can't be left yet, or null. */
  fun problem(step: GuestSetupStep): String? = when (step) {
    GuestSetupStep.NETWORK -> "Choose your Wi-Fi network.".takeIf { settings.ssid.isBlank() }
    GuestSetupStep.PASSWORD -> passwordProblem()
    GuestSetupStep.BASICS -> "Door codes are 4 to 8 digits.".takeIf { doorCodeOn && !keepsSavedDoorCode && !GuestDoorCode.isValid(doorCode) }
    GuestSetupStep.WELCOME, GuestSetupStep.FINISH -> null
  }

  private fun passwordProblem(): String? {
    val security = settings.wifiSecurity
    return when {
      !security.needsPassword -> null
      password.isEmpty() -> "Add the Wi-Fi password."
      !GuestPasswordStore.isValid(password) -> "That password is too long."
      security != Security.WEP && password.length < MIN_WPA -> "Wi-Fi passwords have at least $MIN_WPA characters."
      else -> null
    }
  }

  fun next(): GuestSetupDraft {
    if (problem(step) != null) return this
    return copy(step = steps.getOrElse(steps.indexOf(step) + 1) { step })
  }

  fun back(): GuestSetupDraft = copy(step = steps.getOrElse(steps.indexOf(step) - 1) { step })

  /** Jumping is allowed backwards, or forwards over completed steps. */
  fun goTo(target: GuestSetupStep): GuestSetupDraft {
    val before = steps.takeWhile { it != target }
    return if (target in steps && before.all { problem(it) == null }) copy(step = target) else this
  }

  fun pick(network: GuestNetworks.Nearby) = manual(network.ssid, network.security, hidden = false)

  fun manual(ssid: String, security: Security, hidden: Boolean) =
      copy(settings = settings.copy(ssid = ssid.trim().take(GuestConfig.MAX_TEXT), security = security.wire, hiddenNetwork = hidden))

  fun withPassword(value: String) = copy(password = value.filterNot { it.isISOControl() }.take(GuestPasswordStore.MAX_LENGTH))

  fun withHidePassword(on: Boolean) = copy(settings = settings.copy(hidePassword = on))

  fun withCheckout(value: String) = if (GuestBasics.isValidCheckout(value)) copy(settings = settings.copy(checkout = value)) else this

  fun togglePreset(id: String): GuestSetupDraft {
    val chosen = GuestBasics.decodePresets(settings.goodToKnow)
    val updated = if (id in chosen) chosen - id else chosen + id
    return copy(settings = settings.copy(goodToKnow = GuestBasics.encodePresets(updated)))
  }

  fun addNote(raw: String): GuestSetupDraft {
    val note = GuestBasics.cleanNote(raw)
    if (note.isEmpty()) return this
    return copy(settings = settings.copy(customNotes = GuestBasics.encodeNotes(settings.notes + note)))
  }

  fun removeNote(note: String) = copy(settings = settings.copy(customNotes = GuestBasics.encodeNotes(settings.notes - note)))

  fun withDoorCode(on: Boolean, code: String) =
      copy(doorCodeOn = on, doorCode = if (code == savedDoorCode) code else GuestDoorCode.clean(code))

  fun withContact(id: String) = if (GuestBasics.isValidContact(id)) copy(settings = settings.copy(contact = id)) else this

  fun clearContactName() = copy(settings = settings.copy(contactName = ""))

  fun withWelcome(value: String) = copy(settings = settings.copy(welcome = GuestBasics.cleanWelcome(value)))

  fun save(): GuestSetupSave {
    val needsPassword = settings.wifiSecurity.needsPassword
    val password = when {
      !needsPassword -> "".takeIf { savedPassword.isNotEmpty() }
      this.password != savedPassword -> this.password
      else -> null
    }
    val door = if (doorCodeOn) doorCode.takeIf { it != savedDoorCode } else "".takeIf { savedDoorCode.isNotEmpty() }
    return GuestSetupSave(settings.copy(welcome = settings.welcome.trim()), password, door)
  }

  override fun toString() = "GuestSetupDraft(step=$step, ssidSet=${settings.ssid.isNotBlank()})"

  companion object {
    private const val MIN_WPA = 8

    fun from(settings: GuestConfig.Settings, password: String, doorCode: String) = GuestSetupDraft(
        settings = settings, password = password, doorCodeOn = doorCode.isNotEmpty(), doorCode = doorCode,
        step = GuestSetupStep.NETWORK, savedPassword = password, savedDoorCode = doorCode)
  }
}

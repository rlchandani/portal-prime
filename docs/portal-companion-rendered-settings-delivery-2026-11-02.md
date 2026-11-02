# Companion rendered settings and roadmap update

The current companion candidate, **1.7.1-music-preview / code 13**, fixes the
unpaired category picker exposing the internal name `immortal`. It now says
“Connect to load categories”. This changes presentation only, preserving the
saved selection and loaded categories.

The bounded 09:27:30–09:57:30 UTC unit passed fresh **564 JVM tests / 74 suites**
and actual **33/33 rendered Android tests in 40.425 seconds**. The latter
cover settings/menu layout, selectors, joined controls, enlarged text and
Portal viewing/destination separation. Three new integration tests exercise
the actual Compose/ViewModel/repository/group pipeline with a strict two-room
in-memory transport: captured destinations, hidden-parent validation and
acknowledgements, master re-enabling and invalid input admission.

The numeric editor fixture uses `asText`; it does not establish the real
Portal's control metadata. Fixture passes do not establish live-room writes.
An optional manual five-tab smoke check produced no completed receipt and
is not counted. Actual unpaired before/after captures verify the label fix.
Only the category-control crops were copied into the roadmap.

App APK: **14,722,589 bytes**, SHA-256
`b5a0feb0b0486c53553e3b8126417afa0fd45eae340efd116ab539283ddc10f4`.
Installed metadata, signer and full APK readback matched in owned API 34
emulator-5558 at 360 × 800 dp. Signer SHA-256:
`4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6`.

The read-only/no-snapshot-save emulator overlay was stopped at **09:50:04 UTC**.
Source AVD backing-file modification times were unchanged. Original Hearth
emulators and Family USB/Wi-Fi connections remained present. Neither Portal
was changed; both retain the exact accepted **1.70 / code 64**.

The separate Toolkit receipt is
`docs/companion-rendered-settings-validation-2026-11-02.md` on
`codex/companion-settings-ui-20261102`. Private terminal/artifact/UI/cleanup
receipts are in `/private/tmp/portal-companion-native-ui-20261102/`.

The roadmap separates locally rendered acceptance from the next live paired
unit. Physical Pixel installation, folded/unfolded layout, real touch/input,
audio and performance remain open. No GitHub authentication/publication,
Home Assistant service or parked-issue reopening occurred.

Companion implementation/test/receipt commit: `3ae098044a0eaf4f42dadf2fff8184f3aa5a5646` (local only).

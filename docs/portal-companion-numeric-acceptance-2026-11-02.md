# Native companion numeric settings — 2 November 2026

The previously unrun numeric-editor case now passes against the actual Family
Area and Living Area APIs. This is acceptance of the existing companion,
not a new app release or a cold-reconnect fix.

Companion **1.7.1-music-preview / code 13** was installed into an owned Android
14 / API 34 emulator at a 360 × 800 dp phone viewport. Full installed-APK
readback matched **14,722,589 bytes** and SHA-256
`b5a0feb0b0486c53553e3b8126417afa0fd45eae340efd116ab539283ddc10f4`.
The unchanged production source is `3ae098044a0eaf4f42dadf2fff8184f3aa5a5646`.
Both actual room identities reported **1.72 / code 66**.

## Actual checks

The app used existing authorized sessions in its normal private no-backup store,
mode 600. Family was selected for viewing, with both rooms selected as
destinations. No PIN was redeemed or token created.

| Native check | Result |
|---|---|
| Actual interval metadata, both rooms | `intervalSec`, min 5, max 600, step 5, `asText:true` |
| Drafts 4 and 601 | Save disabled; actual disabled-button taps left complete room profiles unchanged |
| Overflow `2147483648`, decimal `5.5`, blank | Save disabled |
| Boundaries 5 and 600 | Save enabled; neither boundary was submitted |
| One native Save of 60 seconds | **2 confirmed / 0 failed / 0 cancelled** |
| Independent complete-profile readbacks | Both intervals 60; only the intended control changed |
| Disabled-parent preservation | Living's screensaver remained disabled |
| Restoration | Single-room validated API restores; both complete profiles and screensaver configurations exactly original |

The first probe incorrectly inspected the Save label's XML enabled attribute.
Its enclosing clickable button was disabled. The probe was corrected to inspect
the button; the original XML and correction receipt are retained. This was a
test-inspection error, not a product defect or production patch.

The app's result ledger and independent real profile readbacks establish the
positive write. Outgoing request bodies were not traced. The earlier fixture
tests and full JVM/build gates retain their original scope; no fresh build was
needed because production source and the accepted APK were unchanged.

## Cleanup and limits

The owned emulator stopped and both original Family ADB transports remained.
Source AVD backing disks, snapshots and configuration retained their sizes and
modification times. Seven generated boot/modem sidecars changed: `initrd`,
`bootcompleted.ini`, `emulator-user.ini`, and four modem-simulator files.
The entire AVD directory therefore was **not unchanged**. A future isolated
runner should keep generated sidecars in its disposable data directory too.

The successful initial warm route does not establish cold reconnect. The
earlier failed cold check, physical Pixel installation/folded layouts,
fresh pairing, audible playback, frame rate and general performance remain
unverified. No Portal APK was installed and no Portal foreground action was used.

Unit started **11:26:14 UTC**, with a **11:56:14 UTC** hard stop including cleanup.
Private XML, screenshots, complete baselines and restoration receipts:
`/private/tmp/portal-companion-numeric-acceptance-20261102/`.
The broader product goal remains active.

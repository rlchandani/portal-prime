# Native companion live settings — scoped acceptance

Bounded unit: **09:53:31–10:23:31 UTC**, including cleanup. The previous
rendered-settings delivery remains separately scoped. Companion source is
`3ae098044a0eaf4f42dadf2fff8184f3aa5a5646`, **1.7.1-music-preview / code 13**.
Its exact installed APK readback matched
`b5a0feb0b0486c53553e3b8126417afa0fd45eae340efd116ab539283ddc10f4`
(14,722,589 bytes) in owned Android 14 / API 34 emulator-5558.

Valid existing sessions were retrieved through the authenticated room rosters
and loaded into the app's normal private no-backup store, mode 600, in a
read-only emulator overlay. No PIN was redeemed, no new token minted, and no
fresh onboarding acceptance is claimed. Credentials and complete baselines
remain private in `/private/tmp/portal-companion-live-settings-20261102/`.

| Check family | Actual result |
| --- | --- |
| Real hidden-parent metadata | Passed: Living disabled ordinary schema has only `enabled`; inactive schema has 33 controls. Interval metadata is min 5/max 600/step 5/`asText:true`; reading it did not enable the master. |
| Native grouped Shuffle | Passed: viewing Family with both destinations selected, one visible Shuffle tap produced **2 confirmed / 0 failed / 0 cancelled**. Independent complete profile reads showed only the intended changes; Family stayed enabled and Living stayed disabled. This phase used direct real LAN requests, before the later transport failure. |
| Native basic/Advanced visibility | Passed: viewing disabled Living shows the master and hides Shuffle/Photo interval in both phone tiers. The phone Advanced toggle was returned to off. |
| Real invalid API value | Passed: interval 601 returned HTTP 200/`ok:true`/`applied:[]`; the complete profile stayed unchanged. This is an API test, not native group rejection-display acceptance. |
| Viewing/destinations and persistence | Passed: viewing Living while targeting only Family kept the correct independent labels and menu state. After app restart, both saved room rows returned, the one-target file persisted, and existing credentials/IDs remained unchanged. This does not prove successful cold reconnect. |
| Native cold reconnect | **Not accepted.** The app showed Offline; direct probes from emulator shell/app UID reported “No route to host”, while host sessions returned HTTP 200. A temporary loopback ADB relay also failed in native OkHttp before response headers with unexpected end-of-stream/EOF, although host relay requests returned 200. A product defect/root cause was not established. |
| Native numeric 601 editor | **Not run.** Later reconnect failure prevented the planned actual-editor check. Prior rendered fixture coverage retains its original scope. |

The five passed check families do not establish whole-companion acceptance.
No startup latency, FPS, audible playback, physical Pixel installation,
folded/unfolded layout, direct cold LAN reliability or fresh pairing claim.
Outgoing request bodies were not traced during the successful direct-LAN phase;
the observed UI ledger and independent complete-profile readbacks are its proof.

Both rooms' complete registry profiles and screensaver configurations were
compared against their own pre-unit baselines after restoration and again at
cleanup: **exactly original**. Both Portal APKs remain **1.70 / code 64**;
no Portal or physical-phone application was installed.

Owned emulator cleanup completed at **10:16:56 UTC**. Its source AVD backing-file
modification times were unchanged. The owned relay process exited and ports
8902/8903 and their reverse forwards were closed. The temporary diagnostic test
source was removed and preserved privately with its failing result; it is not
shipped or counted as passing. The original Family USB/Wi-Fi transports remained
present. Earlier Hearth emulator transports were absent at the final snapshot;
no commands targeted them and no cause is attributed.

The [independent source review](portal-companion-live-contract-review-2026-11-02.md)
documents wire acknowledgements and partial-rejection semantics. No speculative
production patch was retained. Cold/input acceptance stays open; move to an
independent product item rather than extending this investigation.

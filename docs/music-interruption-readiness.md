# Music interruption readiness

This is source feasibility evidence, not a passed Calls/Jarvis playback test.

Family's installed `de.badaix.snapcast` reports version **0.29.0.2 / code 2902 /
minSdk 21 / targetSdk 34**. The matching published
[release build configuration](https://github.com/snapcast/snapdroid/blob/v0.29.0.2/Snapcast/build.gradle)
has the same metadata. Matching metadata does not replace live behavior verification.

## Receiver limitation

The published
[broadcast receiver](https://github.com/snapcast/snapdroid/blob/v0.29.0.2/Snapcast/src/main/java/de/badaix/snapcast/BroadcastReceiver.java)
accepts start/stop commands. The
[service](https://github.com/snapcast/snapdroid/blob/v0.29.0.2/Snapcast/src/main/java/de/badaix/snapcast/SnapclientService.java)
uses a same-process LocalBinder and service callbacks; it has no cross-process focus-state
contract, MediaSession or audio-focus listener. Its ongoing notification proves service
presence, not permission to produce audio.

On Portal API 29, Android does not enforce audio-focus cooperation for an uncooperative
renderer. A reliable receiver must request media focus, handle loss, report actual output
state through a supported interface, and avoid replaying buffered or user-paused music.
See [Android audio focus](https://developer.android.com/media/optimize/audio-focus).
Changing `MusicPlayerSnapshot.audioOwnedByOther` alone neither silences that receiver nor
proves another app owns audio. The existing flag also blocks pause, room selection and
volume, and freezes the displayed position, so blindly populating it is insufficient.

## Limited signals available to the launcher

- [AudioManager.getMode](https://developer.android.com/reference/android/media/AudioManager#getMode())
  can distinguish communication modes without GMS/root. It does not identify the focus
  owner or stop existing receiver audio. Mode listeners require API 31; an API-29 observer
  needs bounded polling and a fresh command-admission read.
- Public [Jarvis AssistantService source](https://github.com/rudysev/portal-assistant/blob/main/app/src/main/java/com/portal/assistant/service/AssistantService.kt)
  distinguishes live conversation and standby in notification 1001 on `assistant_listening`.
  The installed Jarvis producer must be verified before using this as an adapter. Generic
  foreground presence, service presence and wake listening do not establish a conversation.
- Android 10 public recording configurations are
  [anonymized](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-10.0.0_r47/media/java/android/media/AudioRecordingConfiguration.java);
  they cannot reliably identify Jarvis or distinguish wake listening from conversation.
  See [audio input sharing](https://developer.android.com/media/platform/sharing-audio-input).

If a limited local admission guard is added, it must bind the local Snapcast client to its
verified MA player, apply only to commands whose resolved target/group includes that
receiver, retain pause/stop and room selection, and leave unrelated speakers usable.
Detach must clear observations and cancel polling. An interruption ending must never
send PLAY, replay or resume automatically.

## Open acceptance work

Reliable interruption suppression/reporting requires a receiver change and actual
Family playback testing. Verify Calls and Jarvis independently, user-paused recovery,
group targeting and cleanup. No root changes, recording callbacks, guessed foreground
state, phone calls or Google credentials were used in this source audit.

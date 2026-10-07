# Portal+ Jarvis shortcut and model research

October 7, 2026. Branch: `codex/portal-plus-approved-ux`.

## One tap to listen

Previously, Home favorites and the Apps inventory used an ordinary Android
launch intent for Jarvis. Jarvis's own microphone button was still needed.
The installed assistant is `com.portal.assistant`, version 2.3 / versionCode 5.
Its public source exposes an exported `wake.WakeHandoffReceiver` for
`com.portal.wake.action.WAKE`.

`JarvisHandoff` now opens Jarvis and sends that explicit wake command once the
launch succeeds and the launching Activity stops. Android resumes a covering
Activity before stopping the previous one, giving Jarvis foreground microphone
access. The observer removes itself after dispatch, launch failure or
destruction. No timer, polling, new preference or Jarvis APK modification is
needed. Rapid taps share one pending handoff per launching Activity. Home
favorites, Apps (including folders/recents) and Jarvis quick controls
share the handoff. A non-Activity caller opens the app without automatically
starting a background microphone.

The unminified benchmark APK SHA-256 is
`add734097f1b0bd9fcca4d6a5106d7b918f54bf37e2c5174b941c46ec34212e1`;
the installed APK matches. Debug and benchmark builds succeed and **603 JVM
tests pass**, including four foreground-ordering, cancellation and duplicate
trigger regressions. On the connected Portal+, a single Home favorite tap and a
single Apps tap after a cold Jarvis process start both reach “Listening…”.
The rapid-tap check also reaches the same listening screen.
Private screenshots remain outside the repository because a live conversation
can contain household speech. The Portal is returned to Home after the checks.
These are functional handoff checks, not comparative model latency measurements.

## Model recommendation

The configured model is `gemini-2.5-flash-native-audio-latest`. Jarvis 2.3's
`GeminiModel.AVAILABLE` contains only that model; `AppPrefs` rejects any other ID.
Changing its launcher cannot add a model to Jarvis's own picker.

Google's latest stable conversational candidate is
[`gemini-3.8-live`](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-live),
released September 15, 2026 and recommended for low-latency voice agents.
It supports the same bidirectional Live API transport used by Jarvis. Migration
also changes tool-execution defaults and proactive-audio behavior; compatibility
needs testing alongside the model picker and existing conversation reducer.
Extended Thinking adds interaction-state requirements and is not the first
candidate for immediate voice replies.

After explicit user approval, a read-only Google `models.list` request using the
configured key returns HTTP 200 and includes `models/gemini-3.8-live` with
`bidiGenerateContent` support. This confirms authenticated model discovery;
generation quota, live-session compatibility and comparative response speed
have not been tested. The credential stays in memory and is neither printed
nor saved.

Keep the working 2.5 selection for now. A Jarvis update should add 3.8 Live as an
option and compare time to first spoken response, recognition quality, tool
behavior and reconnect reliability on this Portal before switching the default.
Google lists an earliest November 17, 2026 shutdown for the specific 2.5
December-2025 audio preview in its
[deprecation schedule](https://ai.google.dev/gemini-api/docs/deprecations).
The exact target behind the installed `latest` alias was not established by the
model-list request.

Source reviewed:
[Jarvis 2.3 source](https://github.com/rudysev/portal-assistant/tree/4b50bfddb064577c694f865b8ae4045eac4d1469).

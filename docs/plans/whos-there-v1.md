# Who's there? v1 — implementation plan (branch whos-there)

Spec: portal/mocks/big-ideas-20261117/who-is-there (Decisions accepted), camera spike final verdicts.

## Models / runtime
- Detection: MediaPipe BlazeFace short-range (blaze_face_short_range.tflite, float16, 230 KB, Apache-2.0).
- Embedding: OpenCV Zoo SFace 2021dec (MobileFaceNet, Apache-2.0). Shipped ONNX; rebuilt layer-by-layer
  in Keras from the ONNX weights and converted to TFLite with dynamic-range int8 weights (9.9 MB).
  cos(onnx, tflite-int8) 0.988–0.992 on synthetic inputs; f32 rebuild = 1.000000.
- Runtime: org.tensorflow:tensorflow-lite 2.16.1 (arm64 .so 3.4 MB) — smaller than ORT (17.6 MB).
  armeabi-v7a .so excluded → 32-bit process degrades to "off".

## Architecture (files WhosThere*)
Pure (JVM tests): Model, GlancePolicy, Matcher (cosine, accept/ask bands, top-2 margin),
ViewPolicy (shared / personal / family / kids), Privacy (blur rules), Audit (counts+times, 30 d),
BlazeFaceDecoder (anchors/decode/NMS), FaceAlign (similarity transform + YUV→BGR warp),
Enrol policy (4 looks + quality gate), CalendarFilter, crypto envelope format.
Android: Models loader (bg thread, catch Throwable→off), Camera2 glance (cam 0, TextureView preview
+ ImageReader YUV 640x480, close immediately), Store (AES-GCM Keystore file, embeddings only),
Controller (PresenceHub arrival → policy → glance → StateFlow), Debug override (fleet action
`whos_there_debug`), UI: indicator chip, Hi chip + tint, personal cards, Not-you sheet, Settings
People page (block), Enrol activity (consent → ready → 4-look capture), Privacy centre + audit.

## Data honesty
- Calendar: per-person ICS URL if set; else shared ICS filtered by name/#tag in the title.
- Reminders: no per-person store exists → household-list items tagged with the person's name.
- Music favourites: no model exists → omitted in v1.
- Kids: ChoreBoard + RoutineEngine matched by kid name.

## Status (2026-11-19 night)
Built, JVM-tested, night-verified on Family Portal+ and the Mini with nobody in front of the camera
(operator test person with a synthetic template; no images saved). Real enrolment/recognition
needs the household in daylight — steps below.

## Owner's daytime steps (per Portal; templates are per Portal)
1. Install the whos-there build. Settings › People & Recognition › Add person: name, Adult/Teen/Kid,
   colour › Next › read the consent, tick it › Continue › Start face set-up › Allow (first time only).
2. Follow the ring: Front, Left, Right, Chin up (~20 s, arm's length, room lit). Done › Turn on recognition.
3. Repeat for each person (kids: role Kid, a parent ticks consent). Then on the other Portal.
4. Walk away ≥1 min, walk back (or tap the eye chip). Expect: chip shows live preview + green dot +
   "Looking…" (~1.5 s, Meta LED on), then "Hi <name>", warm tint, their cards. Note any wrong/missed match.
5. Thresholds: `adb logcat -s HearthpaneWhosThere` shows `glance done … best=0.xxx rot=… glance→greeting=…`
   (cosine number only). Accept ≥0.45, ask 0.36–0.45 (MatchThresholds). Report best values for
   right person vs others so they can be tuned.
6. Two people together → Family view; a second person walking in → reminders redact; tap the name chip →
   "Who's here?" → pick/Someone else/1 hour pause. Shutter closed → "Camera covered"; dim room → "Too dark".
7. Mini upright: confirm faces are found (`rot=` in the log is 90 or 270), then turned.
8. Person › Delete → they vanish from People; Privacy & Camera › Delete all → recognition off, list empty.

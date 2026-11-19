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

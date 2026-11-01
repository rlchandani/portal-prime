# Synthetic video fixture

`synthetic-clip.mp4` contains only ffmpeg's generated `testsrc` color pattern.
It contains no people, user media, captions, audio or external image input.
Created for SlideshowDeviceChecks on 2026-10-07.

Format: 320 × 240, 15 frames/second, two seconds, H.264 Baseline, YUV420P.
SHA-256: `e598ec6c2246b4566187338dbe85dd3082de13a9c11c2881293bed3ba22e9bee`.

Generation command:

```sh
ffmpeg -f lavfi -i 'testsrc=size=320x240:rate=15:duration=2' \
  -an -c:v libx264 -profile:v baseline -pix_fmt yuv420p \
  -movflags +faststart synthetic-clip.mp4
```

This generated fixture is part of the project's MIT-licensed test code.
The test copies it only into its owned private cache library. No screenshot or
video capture is exported by the sidecar.

## Night-retention fixture

`night-retention-clip.mp4` uses ffmpeg's generated `testsrc2` pattern, with no
audio or external media. Its 30-second duration allows a real night-clock hold
and return before the clip ends.

Format: 160 × 90, 12 frames/second, 30 seconds, H.264, YUV420P.
SHA-256: `a6100cb86ffe2065fd88180502b6b79cf8aa2bf3bf5d5647de220edbe3859a4e`.

```sh
ffmpeg -f lavfi -i testsrc2=size=160x90:rate=12 -t 30 \
  -c:v libx264 -preset fast -crf 32 -pix_fmt yuv420p -an \
  -movflags +faststart night-retention-clip.mp4
```

This is a playback/lifecycle fixture, not a full-resolution decoder or GPU
performance benchmark.

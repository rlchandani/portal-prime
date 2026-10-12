# Family Area: bounded Home/screensaver stability observation

The verified Sounds build remained installed for a 15-minute observation. Home was left idle;
Immortal's photo screensaver started naturally. No navigation, settings, source or voice edit
was performed. One existing expiring FrameMetrics collector was started with Main-stack and
Settings-work sampling disabled; 16 read-only samples were taken at one-minute intervals.
The capture expired automatically and left no collector thread or window listener active.

| Check | Observed result |
| --- | --- |
| Duration including final APK verification | 902.585 seconds |
| Read-only samples | 16 |
| PSS at start / minimum / peak / end | 75.3 / 74.17 / 224.71 / 192.0 MiB |
| Two sequential GETs, median / maximum | 214.684 / 541.732 ms |
| PhotoDreamService window samples | 20112 |
| Invalid frame samples / dropped callbacks | 0 / 0 |
| Photo window p95 / p99 | 35.75 / 59.25 ms |
| Settings / navigation edits | 0 / 0 |
| Final installed APK | Exact initial SHA unchanged |
| Collector / listeners after expiry | Inactive / 0 |

Memory rose during later photo loads and fell before the final sample. This is a bounded
observation, not proof of leak freedom over hours or days. The response timing covers both
GET /perf and GET /info plus network transport; it is not UI tap or website-load latency.

Photo window timing still has outliers: 11575 of 20109
steady samples exceeded the 16.67-ms window budget. The overall maximum was
1804.583 ms; the summary does not isolate startup from every steady-frame tail.
This report does not establish universal 60 FPS or delivered display FPS. The broad
performance goal remains incomplete. Only three idle Home frames were collected, so no
Home navigation performance conclusion is drawn from this observation.

The /info presence proxy initially reported OFF while Home window frames were observed;
that proxy label is not a direct physical-display power measurement. It later reported
DREAMING with actual PhotoDreamService frame samples. No other Portal or phone was touched.

Tested APK SHA-256: `8bea72ec84d7974a810045401a5648e2ca00afa0dee000d08df6d59db7938a27`.
Postflight SHA-256: `8e4f2a97c76c562943dae0cbb4b1713cd03911d942bd23e567d572cd5e26ec7d`.
Evidence: `~/portal-prime/build/fleet/diagnostics/family-stability-2026-10-11` (run.py, three projection/identity tests, samples.json, summary.json,
execution-started.json and postflight.json). Raw credentials, URLs, images and page contents
are excluded from the saved projection.

# Family Area performance implementation window

**Goal:** Address the known Filmstrip and Home/Apps rendering outliers by 2026-10-11 03:01 UTC. User delegated implementation decisions; healthy features and duplicate Living testing are frozen.

**Architecture:** Keep the existing source discovery, EXIF-correct bounded decoder, overlays, gesture controller and fleet deployment channel. A build-gated native Filmstrip retains up to 12 fixed bitmap cards, prepares small blurred backdrops off Main, and animates View properties with existing CSS timing/geometry. Page navigation uses retained native drawing boundaries around existing Compose page content to avoid per-frame Compose semantics layout invalidation.

**Constraints:** Existing branch, no GitHub hooks/auth/push; one build process and one hardware owner; Family only for profiling. No user setting or credential migration. Opt-in renderer until measured. Do not claim delivered FPS from frame callbacks alone. Preserve full last-to-first travel, pause, interruption, host covering, resize, teardown, source/auth and overlays. Bound prepared pixels and queued delivery; no worker may attach a View.

**Review focus:** Decoder scratch and bitmap ownership; close between worker readiness and Main delivery; track/card geometry; input and accessibility of retained pages; repeatable unsampled before/after measurements and functional regressions. Shadow/blur visual fidelity needs generated fixture inspection on device.

1. Geometry worker adds pure layout/easing/wrap tests. Navigation worker patches one known source cause. Main implements delivery ownership and native preparation/rendering.
2. Run full native gate plus debug/benchmark builds. Opt-in Family candidate, exact APK hash, preserve configuration and source credentials in RAM.
3. Validate startup, actual photo changes, full wrap, rapid next/previous, host pause/recovery, tap exit, app launch, theme/search/long press and page input gates. Profile existing comparable protocols without Main sampling. Inspect memory and view/resource cleanup.
4. Accept only demonstrated improvements with no functional regression; otherwise withdraw the candidate. Commit explicit paths and report verified outcomes/remaining defects. Deploy accepted final build to Living only after Family verification.

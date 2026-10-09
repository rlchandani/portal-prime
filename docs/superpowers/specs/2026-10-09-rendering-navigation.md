# Size-aware legacy images and consistent phone navigation

Improve measured Portal performance while preserving layouts, effects, settings and sources.
The legacy engine requests per-slot image sizes, but photos.js drops that argument and the
native bridge serves only 2400px copies. Honor the requested size through a new bridge while
keeping the original method compatible. Small tiles use prepared smaller textures; full-screen
images retain their larger tier. Never choose a prepared tier smaller than requested.

Use existing disk-cache identity/eviction conventions. Resolution tiers are 600/1200/1800/2400,
rounded up and capped by the configured target. Decode/compress is off the load/UI path with
one shared low-priority worker, a bounded queue and per-frame cancellation. Preserve original
fallbacks while copies prepare. A hit returns a complete file; edits/eviction cannot leave a
persistent stale JavaScript URL. Bound intermediate bitmap allocation and reject stale commits.

In parallel, /remote/key Apps and preset Apps must use the existing retained Home Apps tab,
matching the companion API and dock. Keep genuine Recents available through its existing entry.

Verify real JS bridge resolution/eviction behavior, JVM sizing/key/cache cancellation, Android
retained-host/theme/preview navigation, full unit/build gates and real Portal loop/stability
captures. Measure comparable render conditions; do not infer delivered FPS from host timings.
Remaining OS-Dream/Settings/browser/website/phone performance and UX stays in the full goal.

Actual Android navigation testing also exposed a cold-Home task boundary: the preview may occupy
another task and survive CLEAR_TOP. Extend the existing weak, main-thread owned-frame bridge
to close Immortal's preview deliberately, with replacement-safe detach and intentional-exit
markers set before either Dream or preview finishes. Keep the Meta/system Dream boundary.

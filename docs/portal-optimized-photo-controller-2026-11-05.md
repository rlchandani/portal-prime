# Optimized photo controller acceptance

The exact optimized **1.207/code201** APK passes three generated static photo scenes
on Family/API29: portrait whole fit, panorama whole fit and Gallery Spread.
Each passes attached hardware Window PixelCopy and software Canvas rendering.
The frozen geometry, bitmap identity, sixteen corners and sixteen thin edges per
path, quiet doodle matte and opacity predicates remain unchanged. Two committed
frames precede every hardware capture. Actual controller presentation and normal
stop replace the Stage entrypoints removed by optimization.

The controller is normally constructed with photos-only mode and generated views.
Its actual optimized viewer callbacks and existing settings object select the
literal scene; full startup is not called. Preference, resolver and service access
are rejected and all counters remain zero. The surviving budget total is observed
under its original lock. Normal controller stop releases budget to zero while
inputs remain live; Stage detach and a committed frame precede recycling. All
three scenes retire normally; fixture package, process and external directory
are absent. The two excluded setup attempts reached zero scenes and are retained
privately: Android requested value-only `isRestricted` and
`canLoadUnsafeResources` queries. Returning false for those queries preserves
all containment guards and pixel assertions.

Both rooms retain 1.207, all **43 Family / 30 Living** preference files and four
saved maps/revisions byte-exact. Family font/accessibility, original Chromium,
resumed frame and awake state remain. Owner registry bytes remain exact and
copied records were removed. No production source, build, app installation,
source selection, account, phone or publication change occurred.

Artifact: 25,887,623 bytes, SHA256
`6dec140dcebe9ffc9bed73b60dca80a0b52e283a1513844f838b1e8798b95288`.
This is isolated **800×480**, static generated-scene acceptance. Full controller
startup, Dream/source/clock integration, motion, resize, hardware performance,
API28, Living visuals and currently selected room style remain separate.

Human-authorized follow-up clock: **15:25:00 UTC**, discovery cap **15:30:00**,
target **15:40:00**, hard stop **15:55:00** on 5 November. The candidate was
selected at 15:29:45; native and restoration checks finished by 15:34:31.
The earlier 06:44:25 optimized preflight and all historical photo clocks remain
unchanged. Evidence: `mocks/portal-optimized-photo-controller-20261105/`.

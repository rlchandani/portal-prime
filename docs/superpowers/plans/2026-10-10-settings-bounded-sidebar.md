# Bounded Settings sidebar candidate

Baseline `2135d88`, benchmark SHA `dab673789a6a944ff2066d28c3947341041ac2760533ee1075f1fb1e75edc6b7`, measured sidebar layout at 94.471/82.075 ms first, and 58.912/51.284 ms later per-entry medians. The source defines seven normal categories and eight with Advanced enabled. Earlier 8/9 wording was inaccurate; the measured baseline recorded eight item-composition invocations per entry. Android guidance prefers regular containers for roughly one to ten items: https://developer.android.com/agents/skills/jetpack-compose/jetpack-compose-performance/references/lazy-layouts .

Replace only the sidebar LazyColumn with Column plus verticalScroll. Retain stable category keys, advanced gating/order, 76dp rows, 8dp gaps, 28dp top padding, focus/click actions, fonts/colors and precise collection/item accessibility metadata. Keep the existing actual-region diagnostic unchanged. Search behavior, settings storage, navigation and build optimization flags stay the same. For this reversible UI container change, run the existing full gate, both builds, independent source review and actual device assertions instead of adding implementation-mirroring tests.

Compare baseline-a, candidate-a, baseline-b and candidate-b on both rooms with verified installed hashes, 20 warmup fresh Settings entries then ten measured entries per room/block. Use the same actual gear input, explicit 15-second captures, no stack sampling, at least one second before metadata polling, exact capture ownership/cleanup and configuration/schema equality. No cold-process or exact touch/FPS claim; disclose unknown ART/thermal state. Parent/child spans overlap. Improvements require reduced overall composition/layout/frame work, not simply moving lazy-item composition earlier. Reject or refine the candidate if results are unconvincing or functional constraints fail. Preserve original evidence and candidate failures.

After matched cohorts, verify category navigation, advanced-mode/scroll access, synthetic search and pin/browser/slideshow regressions without exporting household content. The broad performance goal, including first-use search, slideshow tails, website readiness, long-uptime memory, gestures and phone/web UX, remains open.

## Execution status

- Full gate (1,300 passing tests), both builds and independent source review completed.
- Both rooms completed the four matched blocks: 160 warmups, 80 timed entries and 240 verified stops.
- Corrected functional checks and five-cycle navigation/pinned-window regressions passed both rooms. Original failed cardinality assertion remains preserved.
- The root-phase sum is an incomplete proxy: the candidate adds a second row-composition round outside its bounds. Family-a maximum also worsened. Retain for continuing evaluation without claiming overall work, universal tail improvement or final performance sign-off.
- Results and limits: [validation](../../design/portal-settings-bounded-sidebar-validation.md).

# Portal widget gallery refinement — design and product specification

Status: review prototype; native implementation pending design approval.
Baseline: `61b870cd`; branch `codex/widget-gallery-20261102`.
Scope follows the revised goal of 2026-11-02: existing Immortal widgets. No new charging, parking, or Home Assistant server installation.

## Product outcome

Choose widgets by seeing what they contain at each useful size, including setup, empty, loading, recovery and content states. A resize should change information density and hierarchy rather than merely stretch a scrollable card.

The gallery covers all fourteen built-in kinds and one category for installed Android providers. Anonymous fixtures illustrate states; they do not read personal accounts, operate appliances, or represent device measurements. Android and iOS columns are design interpretations, not screenshots or working native ports. The Portal column uses Immortal's approved palette.

## Research and decisions

| Evidence | Decision |
| --- | --- |
| [Apple widget guidance](https://developer.apple.com/design/human-interface-guidelines/widgets) favors glanceable content and focused interactions. | One leading value or next event; meaningful actions; details only when space permits. |
| [Android widget design](https://developer.android.com/design/ui/widget) recommends responsive layouts and usable content within bounds. | Three candidate presentations: small summary, medium useful action/summary, large useful detail. Do not invent variants where they offer no value. |
| [Android flexible layouts](https://developer.android.com/develop/ui/views/appwidgets/layouts) includes capabilities added after Portal's Android 9/10 versions. | Compose custom tiles can adapt directly; provider widgets must respect provider metadata and API 28/29 compatibility. |
| Source inventory shows most built-in widgets have one layout across spans. | Prototype information hierarchy for each size before touching packing or saved layout persistence. |
| Calendar and To-do already expose real recovery states. | Preserve their behavior; distinguish proposed network/error states from source-backed states elsewhere. |
| Existing Meta-style palette is approved by the user. | Dark background `#14161B`, card `#2B2B2B`, secondary surface `#363636`; no costly backdrop blur. |

## Review workspace

A searchable widget sidebar, shared size/state/theme/font controls, and three synchronized preview columns. A Portal-only view makes the selected design easier to inspect. State labels say whether the current app has that state. The native source symbol and size rationale accompany each preview.

Light mode, keyboard focus, increased text size, small phone widths and reduced motion must work. Motion is limited to incidental feedback; no decorative autoplay, polling, remote fonts or image downloads. Photo Memory uses a clearly illustrative local landscape.

## Priorities and acceptance

| Priority | Deliverable | Success criterion |
| --- | --- | --- |
| P0 | Complete source inventory | Every declared custom kind appears exactly once, plus installed providers; existing and proposed states distinguishable. |
| P0 | Functional review gallery | Every catalog state selectable; only valid sizes enabled; URL retains selections; actions demonstrate behavior without claiming real service calls. |
| P1 | Responsive presentations | No unintended page overflow at 390, 1440 and 2160 CSS pixels, including enlarged text; controls at least 48 px. Preview dimensions are not claimed as physical Portal dp. |
| P1 | Accurate feed state contracts | Configured-but-failed differs from unconfigured and genuine empty; saved content shows age only when the source supplies a reliable timestamp. |
| P1 | Settings ownership | Heading and edit actions point to the widget's owning editor, verified against source; shared scalar fields remain registry-driven. |
| P1 | Native pilot after approval | Build and relevant tests pass; Family Area verifies chosen variants and retained layout; no regression in action routing. |
| P2 | Remaining native variants | Each independent issue gets one 30-minute investigation/fix/verification slot. Unresolved issues are recorded and parked. |
| Release | Accepted iteration | Version bump, signed build, tests and Family Area verification before deployment; Living Area receives the final accepted build. |

## Performance and quality gates

The browser review is not a hardware performance result. Record local interaction-to-render measurements with sample count and environment; report failures without hiding them. Native acceptance requires a baseline and final trace on Family Area (Android 10, QCS605, Adreno 615, approximately 2.7 GiB RAM, 2160 × 1440).

Native targets: no new work on the UI thread for network/storage reads; refresh work stops when Home is inactive; stable widget keys survive resize; no accumulating observers/jobs after repeated gallery entry. Compare frame deadlines, warm interaction latency and memory before/after on identical scenarios. Targets are provisional until the actual baseline establishes feasibility, not claims of achieved performance.

## Settings structure

Keep existing two-pane settings navigation and Back/Forward history. Separate Home placement/customization from a widget's data-source editor. Generic persisted scalar settings belong in a `SettingSpec`, with visibility/validation on the spec and side effects on the domain. Rich editors retain their existing navigation boundary.

Do not rewrite the registry or silently migrate accounts for visual work. Quick Controls currently has conflicting Display/Quick Buttons destinations, neither of which configures its discovered app shortcuts; propose an owning page or clear app-management destination during the native pilot.

## Metrics and limits

Engagement, retention and NPS require real opt-in user observations; no invented scores or telemetry collection. Initial success is observable task completion, clear states, readable layouts, reliable actions and measured device performance. This document and browser gallery do not constitute release approval.

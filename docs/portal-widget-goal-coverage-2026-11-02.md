# Widget refinement goal — requirement and evidence ledger

Authoritative objective: `~/.codex/attachments/e84e082e-eaf4-40fb-b0da-7db93d939182/goal-objective.md`.
Status: historical objective, superseded by the whole-product goal at
`~/.codex/attachments/4832b00e-0492-44a6-a521-a970bf2ea968/goal-objective.md`.
The checkpoints below describe that earlier objective at its review handoff; they do not
declare the unimplemented redesign complete. Resume from
[the product operating state](portal-product-operating-state.md) for current priorities.

The roles in the goal are working responsibilities. The deliverable is improved Immortal software, not a claim that a staffed organization, recurring meetings or a telemetry product has been built. Android/iOS gallery columns are design interpretations, as documented in the review; the implemented product target is the existing Android Portal app. The current objective removed the earlier charging/parking additions. No Home Assistant music-server deployment belongs to this iteration.

## Deliverable requirements

| Objective requirement | Current evidence | Status / next required proof |
| --- | --- | --- |
| Research and competitive patterns | Linked Apple/Android widget guidance in the design specification and `research.html`; source inventory for all declared kinds. | Research checkpoint complete; user-specific usage/feedback remains observational, not fabricated market statistics. |
| PRD, prioritization and clear success metrics | `docs/superpowers/specs/2026-11-02-portal-widget-gallery-design.md`, including priorities and release gates. | Written; design acceptance pending. Native latency/frame/memory gates require the actual baseline. |
| Render gallery with all platform variants | Browser studio contains Android/iOS interpretations and proposed Portal layouts. | Review artifact complete; no native phone/iOS ports claimed. |
| High-fidelity useful widget sizes | Catalog has size policy per kind; alarm single size, Options medium/large, provider illustrative with provider-owned sizing. | Browser presentations complete; native variants remain to implement and verify. |
| All widget states and functioning navigation | 78 catalog states, 70 source-backed branches and 8 proposed states; controls and local demo interactions exercised. | Source-backed means code inspection, not native runtime coverage. Device/service combinations and proposed recovery handling remain. |
| Premium design and rationale | Shared palette/type/layout rules, source links, size rationale and research page. | Design review pending; native component consistency still required. |
| Settings architecture audit | Widget ownership map; full navigation audit records 8 categories and 54 pages; registry audit is a separate artifact. | Audit preparation underway/completing; restructure is not yet implemented. Preserve registry ownership, constraints, hooks and gates. |
| Review/reorganize all settings pages | Full 54-page ledger with categories, Activity boundaries, Advanced gates and proposed clarity changes. | Source structure inspected; full native control/visibility/visual walkthrough and accepted changes remain. |
| Widget settings ownership and scalable structure | Timer-store split, Quick controls route disagreement, Photo Memory enable dependency and missing setup entries recorded. | Resolve data ownership and implement true owner links; no duplicated preference stores. |
| Approved native implementation | No native source or installed software changed by the gallery checkpoint. | Pending design approval, native implementation and relevant tests. |
| Accurate current status and real-time behavior | Existing producer/store paths documented; proposed loading/empty/error distinctions marked honestly. | Verify actual source outcomes, stale content/time boundaries and refresh cancellation on Family Area. No browser fixture counts as real data. |
| UI consistency, alignment, font/accessibility | 10,272 browser combinations without horizontal overflow; sampled 456 contrast checks; 48px controls and enlarged text. | Browser checks are limited to their assertions. Native overflow/overlap, readability, focus and dialogs need hardware verification. |
| Functional tests and regressions | Browser action checks and existing test inventory. | Run relevant native JVM tests after changes, signed build, device matrix and action-routing regression checks. |
| Rendering/load/stress/performance | Family hardware identified; browser scheduling estimate explicitly Mac-only. | Establish native baseline and final traces for identical scenarios; profile refresh/observer work, resize and repeated entry. |
| Memory/energy/battery impact | Family is an externally powered Portal+; physical memory verified. | Measure memory and background work, CPU/wake/thermal behavior as relevant; battery-only settings remain capability-gated on devices with batteries. |
| No performance regression | No native change yet; no improvement claimed. | Before/after native comparisons and regressions resolved within per-issue 30-minute slots. |
| Version bump/build/release | Design checkpoint committed on isolated branch. App version unchanged. | Bump only a completed accepted iteration; signed build, required tests, release notes and verified Family/Living deployment. |
| Post-release feedback and iterations | Review request is pending. | Real feedback after deployed iteration; incorporate accepted findings. |
| Engagement, retention, satisfaction/NPS | No real study or telemetry dataset exists for this iteration. | User observations after release; do not invent scores or add unsolicited tracking. |

## Roles and collaboration evidence

| Responsibility | Evidence / remaining work |
| --- | --- |
| Product | Source-grounded scope, prioritization, ownership and acceptance specification exist; pending user acceptance and post-release feedback. |
| UX | Functional review layouts/states/size policies exist; native visual validation remains. |
| Coder | Isolated branch and durable review artifacts exist; native code work remains behind the design gate. |
| QA | Browser scope and limitations recorded; 98 existing settings JVM tests passed across 9 suites, and source structural assertions passed. These are additional evidence, not whole-device completion. |
| Performance | Hardware capability recorded; native baseline/final measurement and CPU/memory/refresh-work optimization remain. |
| Collaboration | Main and bounded parallel agent tasks produce disjoint inventory/settings artifacts with integration review. Weekly/daily cadence language is a process expectation, not a reason to wait days or claim nonexistent meetings. |

## Preserve the user's constraints

- One left sidebar and one right detail pane; Back/Forward history.
- Advanced options remain behind the existing global toggle, with saved values preserved.
- Existing approved Meta palette and functional home/fleet behavior stay consistent.
- Family Area is the primary performance/functional test device; deploy the final verified build to Living Area.
- Each independent issue gets at most one 30-minute investigation/fix/verification slot; park it with exact evidence if unresolved. Do not reopen exhausted slideshow/black-flash investigations as widget work.
- No GitHub authentication prompts or hook execution; local version control remains available.

Native implementation, release and observed post-release outcomes are not waived by a completed prototype. The goal is not complete.

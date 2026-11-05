# Portal product operating state

Snapshot: **5 November 2026 UTC**. Read this current handoff first. Use
`mocks/portal-widget-gallery-20261102/roadmap/roadmap.json` for a task's
status, original clock and evidence; open its dated receipt when working on it.
The [historical snapshot](portal-product-history-through-2026-11-05.md) preserves
the entire previous handoff byte-for-byte, including the initial inventory,
closed investigations, approvals and release history. Its dated versions and
“current” statements are historical. Read relevant sections, not the whole log.

The whole-product objective remains active:
`~/.codex/attachments/e1ac08b5-a8c8-4f3e-9d81-6508e323ce04/pasted-text-1.txt`.
This document is a handoff, not whole-product acceptance.

## Current delivery and boundaries

Both rooms last verified at exact optimized **1.204/code198**, SHA256
`09c938a52734e91134d17b189e040655ef345468c872af63514e68c3243da0b9`, 25,887,275 bytes.
[Latest receipt](portal-photo-decode-feedback-2026-11-05.md): browser photo decode
failures hide broken images and expose the existing retry. Four controller and two
actual packaged-asset Chrome cases pass, with generated valid PNG recovery.
All 4,739 JVM/493 suites, debug/optimized/R8/vital and JavaScript gates pass.
Both rooms serve the exact controller. Final 43/43 Family and 30/30 Living
preference files, four maps/revisions and scoped Family runtime are preserved.
An initial WebView cache comparison was unsettled; fresh complete reads matched.
Current backend thumbnails are validated JPEGs; no new backend corruption defect
is claimed. Other browsers,
phones/native screensaver and full visual acceptance remain separate.
Original 05:48:09–06:18:09 UTC clock remains. These are recorded results,
not fresh checks from reading this file.

The public manifest remains **1.83**. Local delivery and public publication are
separate. The roadmap has **210 tasks: 150 delivered, 49 scoped verified,
8 parked, 2 awaiting approval, 1 queued**. Documentation maintenance changes no
task statuses, historical acceptance or platform delivery.

- Use `~/.codex/worktrees/portal-music-player/portal-prime`, branch
  `codex/product-audit-20261103-144114`, for this work. The user checkout at
  `~/portal-prime` retains the owner-private fleet registry.
- **No Google phone/Pixel inspection, control or installation** until explicit
  human reauthorization. Companion 1.7.8/code20 is a historical delivery;
  physical full-app/source-write acceptance remains open.
- **No GitHub authentication, push or publication; no HA music deployment.**
  Do not change household accounts, providers, media or selected photo sources
  through autonomous discovery. Reuse generated isolated fixtures.
- Portal ADB must explicitly target the authorized Portal; never use unscoped
  commands or inventory. Family: `FAMILY_PORTAL_SERIAL`, `192.168.0.70:5555`, API29.
  Living: `LIVING_PORTAL_SERIAL`, fleet selector `Living Area`.
- Never print tokens or commit fleet records/signing keys. Preserve the existing
  untracked `scripts/__pycache__/`; run Python with `-B`.

## Next work and scope

Prioritize reported screensaver and visible UX problems. Photo sizing, thin
frames, doodle margins and selected clock-space fixes have delivered receipts;
[static hardware acceptance](portal-native-photo-hardware-2026-11-04.md)
and [dissolve pixel checks](portal-native-dissolve-pixels-2026-11-04.md) cover
specific scenarios. Complete on-device visual
acceptance remains open. Installed support and the currently displayed Shuffle
style are separate facts. Check the existing task and receipt before opening any
new visual unit; do not repeat delivered or exhausted investigations.

Select one distinct, reproducible, authorized problem with useful small scope.
Cap discovery at five minutes; target 10–15 minutes including checks, delivery,
restoration and commit. Hard limit: 30 minutes per issue. Retain the original clock
across turns and aliases. Split larger work into independently useful changes.
When a slice stalls, park it and continue unrelated safe work. Do not manufacture
bugs or add features merely to increase the task count.

Recent closed units include browser photo decode feedback, step-action naming, Settings choice-label indexing, import status/count
wording, calendar date/recurrence parsing, photo-source identity/fallback,
caption date/timezone parsing, and supplied-APK inspection. Historical bounded
negative findings also remain closed: do not turn an unchanged hypothesis into a
new issue. Consult the historical snapshot and task IDs before repeating work.

## Work requiring a decision or external change

| Roadmap task ID | Current boundary / next action |
| --- | --- |
| `web-page-zoom-native-rejection` | Parked after native rejection and recovery; original clock retained. A new implementation requires an explicit human choice. |
| `toolkit-apk-read` | Parked candidate under its original expired clock; no automatic retry/reset. |
| `platform-video-redirects` | Parked source-qualified risk; full transport/hardware work requires separately authorized scope. |
| `weather-native-layout` | Weather design is approved; daily geometry and native acceptance remain parked under the original slot. |
| `settings-native-source-drafts` | Masking/Cancel scoped acceptance retained; SMB fullscreen keyboard fix failed. Fresh explicit scope needed. |
| `black-handoff` | Exhausted; rejected latency tradeoff, no retained candidate. |
| `legacy-night` | Exhausted; rejected candidates and native failures remain. |
| `performance-experiments` | Cold Shuffle, rejected Home/Apps/settings, typography and gesture cases retain their original boundaries. |
| `quick-controls` | Functional mock awaits destination/design approval before native implementation. |
| `widget-gallery` | Whole gallery remains unapproved; Weather-only approval does not approve it. |
| `music-browser-playback` | Queued pending normal human Google sign-in; no login automation or cookie extraction. |

These boundaries and each original clock are authoritative in the task records
and linked receipts. A documentation split grants no new scope or extra time.
The existing roadmap default is a review selection, not permission to resume a
parked task. Natural fade acceptance does not close legacy motion, optical panel
FPS or whole-product performance. Live providers/audio/groups, Calls/Jarvis
handoff and final Living music acceptance retain the [music ledger](music-player-goal.md).

## Components and checks

| Component | Current route to evidence and validation |
| --- | --- |
| Portal Android | Single Kotlin/Compose `:app`, API28/29 hardware, no Google services/root. Run relevant JVM/debug checks from [AGENTS](https://github.com/starbrightlab/immortal/blob/main/AGENTS.md); optimized/signing/native acceptance and complete installed APK readback are separate gates. |
| Browser remote/PWA | Bundled assets ship with the Portal APK. Existing Node and generated rendered-browser fixtures; retain pairing, acknowledgment and retry rules. Browser proof does not establish physical phone/native UI. |
| Fleet/provisioning | Existing Rust CLI, Bash/PowerShell kit and Fleet HTTP API. Use [fleet skill](https://github.com/starbrightlab/immortal/blob/main/skills/immortal-fleet/SKILL.md); preserve signing/configuration, target explicitly, confirm identity and outcomes without replaying uncertain writes. |
| Android companion/Toolkit | Separate source and receipts in historical inventory; physical phone access remains prohibited. Local source/host checks do not establish phone delivery or platform parity. No shipped native iOS app identified. |
| Jarvis/external services | External source/provider/cloud availability and current end-to-end acceptance remain unestablished; preserve [bridge scope](design/portal-jarvis-bridge-write-validation.md). |
| Documentation | Strict MkDocs build and link checks for documentation changes; inspect dated evidence before reusing historical counts. |

Existing local tools: `scripts/portal_release_readiness.py` (full gates, JS-only
mode, or supplied-APK inspection) and `scripts/portal_delivery_summary.py`
(offline saved-evidence summaries). `--inspect-apk` returns checked artifact
identity/assets, not fresh build/test readiness. Summary formatting does not
contact devices or prove current health. Hosted CI execution/publication remain
unobserved unless separately verified.

Keep completed detail in dated receipts and roadmap task records. Update this
file's current delivery, priorities and boundaries when they change; keep old
release narratives in history. Whole-product progress continues across releases.

The 5 November handoff split retained all 277,802 historical bytes, SHA256
`2236d2efb2d017e57d937f84e2a6bf4e9d0a91a6803f0ca585ffa00af01640e5`.
At that split, the complete roadmap, its default selection and all task
objects/clocks were byte-exact. Subsequent deliveries are recorded in dated
receipts. The split itself changed no app, device, permission, deployment or
acceptance result.

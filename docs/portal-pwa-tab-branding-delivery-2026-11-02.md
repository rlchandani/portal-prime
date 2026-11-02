# Browser tab branding — 2026-11-02

## Scope and bounded unit

Fresh slot **09:13:42–09:43:42 UTC**, including room restoration. Branch `codex/pwa-tab-branding-20261102`, based on accepted `f3ca2c91`. Local unpublished **1.70 / code 64**. Family installed-browser acceptance and both-room exact APK/configuration readbacks passed below; local readiness alone is separate evidence.

On actual installed 1.69, the HTML had zero explicit favicon links. A fresh unpaired browser reported `/favicon.ico` 401, while `/remote/app-icon?size=64` returned a **64 × 64 PNG, HTTP 200**. The manifest and 180-pixel touch icon already returned 200. Unpaired `/remote/companion` returned 401 as intended.

The production fix is one explicit `rel=icon` link to the existing public PNG. There is no route, authorization, polling, persistence, layout or manifest change. This is browser-tab polish, not a claim that PWA installation was broken. No new mirrored string test was added for this low-impact HTML edit.

## Actual local gates

Readiness exited **0**, status **ready**, in **227,704 ms**. All **15** subprocesses exited 0. Fresh JVM results: **3,800 tests / 399 suites**, zero failures/errors/skips; **40 slideshow JS + 676 bundled JS** and **161 Python** passed. The optimized performance APK, R8, vital lint, version sync, SDK metadata and signing checks passed. Prior 399 JVM report files were archived first.

- Package: `com.immortal.launcher.debug`
- Artifact bytes: **25,860,686** (same byte count as accepted 1.69)
- APK SHA-256: `6157a89035dea1c459dc6b692b854bb11174e565866c074cedde4396d0fa51f7`
- Signer SHA-256: `4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6` (matches the accepted baseline)
- Selected source/build-input SHA-256: `efa1100d6c9c4b1567d17e94401880bdc3dd42608d21dc337e0a10b613d411fa` / 1347 files
- Execution head: `f3ca2c916561e6125f293115c0bc170f6d20c148`; dirty source state was honestly recorded before the later commit

Private receipt: `/private/tmp/portal-pwa-tab-branding-20261102/readiness/receipt.json`. The checker itself records publication/deployment false; device receipts remain separate. No new debug/release variant acceptance or general performance improvement is claimed.

## Acceptance and delivery

Family installation reported Success and the full installed APK matched the candidate hash/byte count. A fresh unpaired browser context at 390 × 844 naturally requested `/remote/app-icon?size=64` (200, 64 × 64 PNG) with **no Authorization header** and **zero `/favicon.ico` requests**. Unpaired management still returned 401.

The existing paired context likewise naturally requested that public icon with no Authorization header and no default favicon request. Its original pairing remained Connected, Settings was usable, and public manifest/touch icon requests remained 200. Both mobile views had zero horizontal overflow. No HTML/asset interception or token generation was used. Browser observations establish these scoped Chromium sessions, not every browser or PWA installation.

Living used **one dispatch** with uncertain acknowledgement, followed by **three read-only observations** reaching ready and an independent exact full-APK readback. Both rooms have the exact **1.70 / code 64** artifact above; there was no blind duplicate install.

Complete per-room settings profile, screensaver configuration and layout XML matched their own original private baselines. Family's original `PhotoDreamService` was observed running again after restoration through the existing SystemUI dream entrypoint. No permission, Meta firmware configuration or dream-selection change was made.

Private before/after browser and deployment/preservation receipts are in `/private/tmp/portal-pwa-tab-branding-20261102/`. No photo/token-bearing captures were copied into the repository.

## Roadmap and parallel preparation

The saved roadmap now shows this bounded unit as delivered, corrects its Browser platform label, and distinguishes locally verified companion code from current native acceptance. The initial 15 and final 16 task detail views passed desktop 1600 × 1000 and mobile 390 × 844 horizontal-overflow checks, with zero page errors. This is a saved priority queue, not live telemetry or whole-product acceptance.

A disjoint agent wrote only [the companion emulator QA plan](portal-companion-emulator-qa-plan-2026-11-02.md). Source/APK identity and existing emulators were inspected read-only; neither emulator has the current companion. Four checks target disabled-parent multi-room settings, target/view separation, range admission and dropdown/overlap regression smoke. No emulator install, physical Pixel acceptance or live multi-room UI pass is claimed.

The broad product goal remains active. Exhausted screensaver/performance units remain parked. No GitHub authentication/hooks, push/publication or Home Assistant music service setup was performed.

Final snapshot: 16 tasks (8 delivered/implemented, 2 locally verified, 1 queued, 2 awaiting approval, 3 parked). Both desktop/mobile 16-detail checks passed with no overflow/missing platform labels/page errors; the final desktop queue screenshot was visually reviewed.

Delivery and room restoration were verified before **09:26:24 UTC**, inside the original30-minute cap. The final local commit follows after diff validation; no source change followed the accepted build.

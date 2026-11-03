# Native pairing browser CI — local preparation, 3 November 2026

The existing CI workflow never selected `scripts/tests/remote_pairing_keyboard_browser.test.mjs`. That opt-in fixture exercises native Enter submission in the shipped remote page, while the existing bundled-JavaScript gate uses its own synthetic DOM. Its hardcoded macOS Chrome path also prevented the unchanged fixture from launching Playwright's managed Chromium on Ubuntu.

Local Immortal commit `a29609fb4383060b438746bf016a4f5a2b27d3b0` adds `.github/workflows/remote-browser.yml` for push to main and pull requests. The ten-minute Ubuntu job configures Node 22, installs exactly Playwright 1.62.1 in runner-owned temporary storage with scripts/audit/fund/optional dependencies disabled, installs managed Chromium and its system dependencies, and explicitly enables the existing six-case fixture. It writes console and JUnit output, with report upload on `always()` and normal test failure propagation. This is a prepared local workflow; no hosted run or upload has been observed.

The sole fixture change selects an explicit `IMMORTAL_REMOTE_BROWSER_PATH`, the existing system Chrome default on macOS, or Playwright's managed browser on other platforms. All six UI cases, assertions, generated request routes, WebSocket tripwires, fresh contexts and cleanup remain byte-exact. All five existing workflows and all 17 protected files match the original bindings. App HTML/controller/assets and Android configuration remain unchanged.

## Actual local verification

| Check | Observed result |
|---|---|
| Public npm metadata and isolated temporary install | Playwright/core 1.62.1; Node 22.13.1 / npm 11.7.0; install exit0 |
| Node 22 default macOS Chrome path | Six JUnit leaf cases pass; seven console records including the parent |
| Node 22 explicit macOS Chrome path | Same six cases pass; no failure/error/skip |
| Existing bundled-JavaScript gate | 724 unique named Node results pass, no failures/skips |
| Final actionlint / embedded Bash syntax | Exit0 / four scripts pass |
| Preparation shell text | Exactly two expected owned paths; Actions environment ingestion unobserved |
| Independent source / execution review | 10/10 and 14/14; root fully read and rehashed before source commit |

Both browser runs retain POST counts 1/1/2/0/2/0 and zero fixture-observed page errors, outside/unexpected requests or WebSockets. These are generated bad_pin replies, not successful live pairing. The original Node 24/launcher baseline already passed all six cases; this unit closes an automation gap and makes no new product RED claim.

Initial inspection used guessed asset names and stopped before runtime. An initial npm attempt rejected a shared empty config path before fetching; separate empty user/global files enabled the successful install. Initial actionlint rejected `runner.temp` in job-level env. The retained rejected workflow was corrected to compute paths in a preparation step through `$GITHUB_ENV`, and final actionlint passed. Those failures remain in the private evidence.

## Limits and retained clock

Ubuntu managed-browser launch, system dependency installation, GitHub triggers/runner behavior and report upload remain unobserved. Both actual local browser paths use macOS headless system Chrome. Package metadata/lock integrity agreement is recorded; package signatures and every installed package file were not independently verified. No live Portal/provider request, token/roster acceptance, phone, Android WebView, new APK, JVM, install, signer or release acceptance is claimed.

The automation clock is **20:32:25–21:02:25 UTC**, including discovery, without reset. The earlier pairing correction retains **19:53:38–20:23:38 UTC**. Publication/GitHub, phone/provider and all older approval/parked holds remain. The whole-product goal is active.

`mocks/portal-widget-gallery-20261102/evidence/remote-pairing-browser-ci-verification.json` records actual run and source bindings with private artifacts labeled as local receipts. The single saved queue adds one delivered tooling entry: 116 tasks, 70 delivered, 35 verified, 3 approval, 7 parked, 1 queued. All 115 earlier objects/raw suffix and the Weather default remain intact.

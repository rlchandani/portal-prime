# Keep a startup app visible

Version **1.181/code175** adds **Settings → Advanced → Start on boot → Stay in startup app**, addressing [upstream request267](https://github.com/starbrightlab/immortal/issues/267). The shared registry also exposes it to the browser remote and native settings search.

The option defaults off. Existing startup apps still launch after five seconds, with the final decision at nine seconds. When enabled, Immortal skips its Home launch if at least one app launch request completed successfully. Otherwise it returns Home. The last successful dispatch remains selected; missing or failed apps do not block later entries. Pending receiver work finishes even if Home cannot open. The startup selection file and other boot schedulers are unchanged.

All **4,616 JVM tests across481 suites**, debug build, optimized build, R8 and vital lint passed. Nine focused tests cover registry defaults, round trips, invalid numeric input, mixed launch results, Home fallback and receiver finalization. Baseline tests assert missing new capability; they are not evidence of an existing bug. An initial test incorrectly rejected a compatibility boolean string and was corrected to numeric1 without changing production validation. Independent source review accepted nine bound files, rehashed before installation.

Both Portals contain the same fully hashed optimized APK, with one install invocation each. Living's uncertain acknowledgment resolved through seven read-only observations. Family's new control was inspected on the installed native screen without toggling it. Both live schemas report false/default false. All43 Family and30 Living preference files remain byte-identical; existing profile fields, screensaver/source maps and layout content match. Both layout revision hashes were independently reconstructed; their change is exactly the added default-false setting. Family font/accessibility, Chromium activity and normal frame were preserved. Captured fatal/ANR markers are zero. Owned UI scratch and copied credentials were retired.

Real reboot and third-party foreground retention remain unverified; no reboot, external app launch or setting toggle was performed. Living native presentation, browser rendering and held screensaver issues remain separate. Public release metadata remains1.83.

Original discovery22:28:54UTC; target22:43:54 missed; hard limit22:58:54 retained. Structured verification: `mocks/portal-boot-foreground-20261104/verification.json`.

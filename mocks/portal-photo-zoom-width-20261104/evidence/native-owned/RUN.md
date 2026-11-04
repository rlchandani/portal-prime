# Photo zoom width fixture — root compilation/execution only

Original discovery 2026-11-04T13:42:16Z; target13:57:16Z, hard14:12:16Z. This source preparation does not reset the clock or claim native acceptance.

Package `com.immortal.photozoomwidthfixture`, Activity `.PhotoZoomWidthActivity`. Zero permissions. Compile Java against the existing Android API36 SDK and D8 min API29 using the admitted root scaffolding; do not change the Java/manifest/case gates after baseline execution. Embed the exact retained before debug APK as `assets/source.apk`. Use the SAME frozen classes.dex for candidate; replace only source.apk and resign. Root owns SDK commands, packaging, install, run, report collection and full package/process/external-files retirement.

Report external-files `report.json`, 30-second owned watchdog. Expected baseline:3 cases,2 bounds/width failures and1 wide control, setupFailures0, callbacks0, unexpectedContextOperations0, retired3, cleanuptrue, actualEmbeddedOrigintrue. Expected candidate:all3 pass with identical Java/DEX/gates.

Cases EXACT root width320/480/800dp and UNSPECIFIED height. Production dp truncation is retained. Editor is explicitly made VISIBLE and normal row GONE only on fixture-owned detached views; no action callbacks or photo admission. Require the actual slider within editor [0,width], slider width min(620dp, root width minus real32dp padding), height64dp/max200/progress0/accessibility label unchanged, original editor button labels unchanged, hide/reveal retained width, zero bitmap budget. Wide800 retains620. No button-fit policy or mounted physical touch claim. Context delegates only fixture resources/theme; preference/resolver/services/app launch fail closed and count. Loaded actual Stage is inactive; controls.stop and stage.stop release owned callbacks and retain budget0.

Root must full-read Java, manifest, this RUN and source-admission BEFORE any SDK execution. No before/candidate build supplied by fixture author. Root should preserve setup errors separately from behavioral RED and may not treat constructor/ABI/service failures as the supported slider clipping witness.

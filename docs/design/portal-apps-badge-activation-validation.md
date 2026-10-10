# Apps badge activation and repeated device comparison — 10 October 2026

The Apps notification read now runs off Main and independently of app preparation. Six focused regression tests and the full 1,463-test suite pass. Both rooms completed the repeated navigation protocol and retain the tested candidate. Overall Home/Apps smoothness remains unaccepted: p95 decreases are small, p99 and mean results are mixed, and frames still exceed the 16.7 ms display budget.

## Concrete change and verification

`AppSwitcherActivity` previously retrieved active notification counts synchronously before its package/resume inventory guard. Warm entry already reused its app/icon inventory, but this notification getter could still perform Binder work on Main. Commit `66144e7` puts retrieval on `Dispatchers.IO` and in an independent lifecycle-keyed effect. Previous badges remain visible during retrieval; cancellation checks prevent a cancelled read from publishing. Inventory loading/freshness, ordering, folders, recents and notification filtering are otherwise unchanged.

The baseline tests initially failed the three intended contracts: retrieval off the caller thread, caller availability during a blocked getter, and suppression of cancelled publication. An unrelated Mockito fixture stubbing error was corrected before accepting the RED result. All six tests subsequently passed. They call the real retrieval helper and real aggregation, with doubles at Android's notification getter boundary; they do not exercise physical Binder IPC or Compose hide/reactivate/disposal. Cancellation cannot interrupt the blocking Binder call itself.

The full native suite passed 1,463 tests with zero failures, errors or skips; debug and benchmark builds completed. Independent read-only review found no production blocker. The exact retained build hashes are:

| Build | SHA-256 | Bytes |
| --- | --- | ---: |
| Baseline benchmark | `5916315cc56e9682a161eba24acc29625a95879ce82f40c6316a0c89ee52b788` | 37,064,080 |
| Candidate benchmark | `08be8c74d15c585cd4d423baff6b6305615e6e8493671e0953ac70202cfff063` | 37,064,080 |
| Candidate debug | `6927e8a86f1bfe134175cf5ae8dae9c5d6f775534488f32712c42b7ec43ef938` | 48,630,198 |

The only app-tree changes versus the preceding diagnostic-state build are `AppSwitcherActivity.kt`, the new `ApprovedAppBadges.kt` and its test. All seven screensaver assets remain byte-identical. Version 58 / 1.64 and application ID `com.immortal.launcher.debug` are unchanged. The benchmark remains non-debuggable, inherits release rendering settings, and keeps experimental R8 and background WebView startup preparation disabled.

## Physical comparison

The sequence is A1 → B1 → A2 → B2, with both rooms tested in parallel within each cohort. Each cohort explicitly reinstalls the preserved APK, verifies complete configuration/schema preservation in memory, performs the same three-action Home → Apps → Home warmup, and runs thirty four-second Home → Apps → Home cycles. Main stack sampling is disabled. Lightweight capture ownership checks precede commands, with full metrics and window verification at six checkpoints and closure.

The protocol's 22 virtual capture/response-loss/restoration checks and four added warmup checks pass. All eight physical captures complete: 240 cycles, 736 accepted action requests including setup/cleanup, plus 24 warmup requests. Every final capture identity/metric check passes, collectors stop with zero active listeners, the known Home window returns and configuration/schema remain equal. All captures record zero first-draw frames, callback drops, invalid or overflow samples.

| Build / round | Room | Frames | Mean ms | p95 ms | p99 ms | Largest ms | Over budget |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Baseline 1 | Family Area | 784 | 15.90 | 60.50 | 85.00 | 95.68 | 131 |
| Baseline 1 | Living Area | 780 | 16.28 | 60.25 | 79.00 | 104.21 | 126 |
| Candidate 1 | Family Area | 782 | 16.19 | 57.50 | 84.25 | 104.88 | 126 |
| Candidate 1 | Living Area | 781 | 15.97 | 57.00 | 75.50 | 100.05 | 125 |
| Baseline 2 | Family Area | 782 | 15.82 | 59.75 | 75.25 | 130.66 | 124 |
| Baseline 2 | Living Area | 782 | 16.33 | 58.00 | 75.50 | 102.84 | 125 |
| Candidate 2 | Family Area | 782 | 16.01 | 59.50 | 73.25 | 99.76 | 130 |
| Candidate 2 | Living Area | 782 | 15.54 | 57.00 | 77.00 | 88.18 | 124 |

Within the sequential comparisons, candidate p95 is lower by 3.00 / 3.25 ms in round one and 0.25 / 1.00 ms in round two for Family / Living respectively. Candidate p99 changes by −0.75 / −3.50 ms and −2.00 / +1.50 ms. Family's candidate mean is higher in both rounds; Living's is lower. The first Family candidate records a larger maximum. These are descriptive differences from two rounds, not a causal estimate, confidence interval or complete performance acceptance.

The narrow change is retained for its verified nonblocking/cancellation contract and completed device protocol. It does not demonstrate that the complete transition is smooth. Background load, notification state, JIT/profile history and request scheduling are not controlled or attested. The captures measure Window frame durations; command receipt and known-window checks do not prove tab content readiness, physical touch latency, delivered FPS or first usable content. Full reports still sample memory, and app PSS excludes a separate Chromium renderer. Frame component durations overlap and must not be summed as CPU time.

## Further evidence and next experiments

After all captures stopped, bounded read-only reads inspected the existing ProfileVerifier cache on both rooms. Local ProfileInstaller 1.4.0 bytecode verifies its 24-byte big-endian schema: both caches report schema 1, result 2 (enqueued for compilation), and current-profile size 12,535 bytes. This is cached status, not a fresh verifier invocation or proof of current compilation. Listing each app's reference/current profile directory returns `not_a_directory`; it does not establish absence of a compiled profile. No compilation, profile reset, broadcast or app restart was attempted. Investigate a verified APK/DexMetadata installation path before claiming a profile benefit. Official references: [profile verification](https://developer.android.com/topic/performance/baselineprofiles/debug-baseline-profiles) and [sideloaded DexMetadata](https://developer.android.com/topic/performance/baselineprofiles/manually-create-measure).

Independent source review identified concrete stable Compose improvements worth a separate experiment: [one semantics child traversal instead of two](https://android.googlesource.com/platform/frameworks/support/+/43663c68dfb1b6120b0cc51484f40ff002e52320) and [skip unchanged rectangle offset work / batch placement](https://android.googlesource.com/platform/frameworks/support/+/8c5084201aaae9a414a54e84cefa1acc1ebff73c) in 1.11.4; [validated cached rectangle indices](https://android.googlesource.com/platform/frameworks/support/+/1369cfd254d682c18df65f074ef39022e1fd6990) in 1.12.1. No upgrade or Portal benefit is verified. BOM 2026.06.01 maps UI 1.11.4; BOM 2026.09.00 maps 1.12.1 and requires compileSdk 37. Full compiler/dependency compatibility and retained-page lifecycle, reversal input, focus, hidden semantics and clipped grid behavior still require build/device tests. Keep the measured `absoluteOffset` tab placement; the removed cache flag was already enabled in 1.10.4.

Filmstrip candidate `a270fb1` with probe/control evidence at `cc8a206` has separately passed the frozen six-case generated-image matrix: 324 exact baseline checks and 324 exact candidate comparisons, with initial preparation calls reduced from 13 to 4. Independent review verified all 28 manifest-listed hashes and the matrix records. Only the Filmstrip hunk may be integrated: its branch also inherits the rejected Ken Burns retirement. Neither production Filmstrip changes nor a Compose upgrade are included in the deployed Apps comparison candidate. Filmstrip's physical performance and native cache behavior remain unverified; sampled software pixel equality is not continuous or device visual acceptance. The probe should also gain exclusive output-file creation before new frozen evidence is generated.

The broader goal remains active. Settings/swipes, actual tab and touch readiness, first usable photo/document, browser and actual website readiness, pinned-app lifetime, cold behavior, long uptime/memory, and complete phone/web validation need their own evidence. The latest read-only Pixel check still shows a secure lock, so its generated physical UI rerun remains pending.

## Evidence boundary

Private immutable results are under ignored `build/fleet/diagnostics/apps-badge-activation-2026-10-10/`, `apps-badge-comparison-2026-10-10/` and `runtime-profile-state-2026-10-10/`. Both rooms finish on the tested candidate with collectors inactive and known Home windows restored. Protocol source snapshots/hashes, corrected RED/GREEN evidence, native build hashes, deployment results and final numeric analysis are preserved. Exported device data is bounded numeric verification, fixed labels and package/window ownership. Household photos, screenshots, visible UI text, DOM, credentials and complete configurations were not exported. No GitHub connection, authentication, push or release is performed.

# Settings App Profile Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve Settings opening by compiling its own exercised entry/rendering code, retaining the approved UI.

**Architecture:** Add narrow HP rules to the AGP baseline profile. Validate actual generated DEX coverage, then compare SDK-only compiled accepted APK versus the candidate using matched freshly compiled blocks on Family Area. Existing target-pinned installers provide APK/DM staging and rollback.

**Tech Stack:** Kotlin, Compose, AGP baseline profiles, Android 10 ART, cached AndroidX ProfileTranscoder, Fleet HTTP API.

**Spec:** `docs/design/portal-settings-install-profile-validation.md`, its next-target section; private preregistration `settings-app-profile-2026-10-11/acceptance-criteria.json`.

## Global Constraints

- Family Area profiling only; Living Area and phone frozen. Home tuning closed.
- Android minSdk 24 / targetSdk 36; device API 29, no Google services/root.
- Native column implementation is outside this experiment; the browser mock is complete.
- No GitHub/authentication/hooks/push. Local commits disable hooks and signing.
- Same signing certificate/package; no data wipe, preference changes, or new HTTP routes.
- 20 warmups + 10 timed entries per block, sampleMain=false; APK/hash/collector/configuration guards.

## Review Focus

- Generated Kotlin/Compose names must resolve to real profiled methods; validate APK index/CRC mappings.
- App rules must not contain S flags or cover Home; audit actual method flags and namespaces.
- SDK dependencies must remain covered; compare namespace counts and checksums.
- Android may retain profiles across replacement; compile every condition explicitly and verify fresh cache.
- Rejected/failed candidate must restore accepted APK; clean owned collectors before installing or navigating.

---

### Task 1: Build and validate the Settings profile candidate

**Files:**
- Create: `app/src/main/baseline-prof.txt`.
- Update: `docs/design/portal-settings-profile-verifier-diagnosis.md` with the diagnosis and final measured result.
- Private tests/runner: primary ignored `build/fleet/diagnostics/settings-app-profile-2026-10-11/` and `/private/tmp`.
- No UI Kotlin modifications.

**Interfaces:**
- Consumes: target-pinned HeadlessInstaller, FamilyRuntime, existing 20/10 Settings cohort.
- Produces: generated APK profile with H/P Settings coverage, frozen candidate/bootstrap artifacts and measured acceptance evidence.

- [x] **Step 1: Observe coverage RED.** Run the private strict APK coverage validator on accepted APK SHA bc6af47b4184de27f81fc59c6502871bb8a796505c59c1e047ae859dd3f28298. Expected: valid decoder/checksums, candidate coverage gate fails because own Settings methods are absent.
- [x] **Step 2: Add these exact profile rules.** This data-file change introduces no settings or runtime business logic.

```text
HPLcom/immortal/launcher/ImmortalSettingsActivity;->onCreate(Landroid/os/Bundle;)V
HPLcom/immortal/launcher/ImmortalSettingsActivity$onCreate*;->**(**)**
HPLcom/immortal/launcher/ApprovedSettingsShellKt;->**(**)**
HPLcom/immortal/launcher/ApprovedSettingsShellKt$*;->**(**)**
HPLcom/immortal/launcher/ImmortalSettingsActivityKt;->ImmortalSettingsScreen(**)**
HPLcom/immortal/launcher/ImmortalSettingsActivityKt;->access$ImmortalSettingsScreen(**)**
HPLcom/immortal/launcher/ImmortalSettingsActivityKt;->ApprovedSettingsRouteContent(**)**
HPLcom/immortal/launcher/ImmortalSettingsActivityKt;->access$ApprovedSettingsRouteContent(**)**
HPLcom/immortal/launcher/ImmortalSettingsActivityKt;->ApprovedNativeSettingsPage(**)**
HPLcom/immortal/launcher/ImmortalSettingsActivityKt;->access$ApprovedNativeSettingsPage(**)**
HPLcom/immortal/launcher/ImmortalSettingsActivityKt$ImmortalSettingsScreen*;->**(**)**
HPLcom/immortal/launcher/ImmortalSettingsActivityKt$ApprovedSettingsRouteContent*;->**(**)**
HPLcom/immortal/launcher/ImmortalSettingsActivityKt$ApprovedNativeSettingsPage*;->**(**)**
HPLcom/immortal/launcher/ApprovedSettingsSearchKt;->approvedSettingsSearchIndex(**)**
HPLcom/immortal/launcher/ApprovedSettingsSearchKt$approvedSettingsSearchIndex*;->**(**)**
```

- [x] **Step 3: Build frozen candidate.** Run `:app:testDebugUnitTest :app:assembleDebug :app:assembleBenchmark --offline` with profile metadata flag false through the serialized Gradle helper. Expected: 1636 tests, no failures/errors/skips, debug and non-debuggable candidate builds pass.
- [x] **Step 4: Validate coverage GREEN.** Strict decoder must verify all DEX CRCs/method counts, H coverage for Activity onCreate, both Shell overloads, ImmortalSettingsScreen, RouteContent and NativeSettingsPage, generated Shell methods; no app S/Home coverage. Verify signing matches accepted. Expected: all gates pass.
- [x] **Step 5: Build pinned candidate bootstrap.** Run assembleBenchmark with metadata flag true and candidate SHA target. Preserve the earlier SDK-only accepted bootstrap and ordinary rollback APK. Expected: correct flags, unchanged frozen source digest, matching signature.
- [ ] **Step 6: Run baseline-before/candidate/baseline-after.** Install each bootstrap then its target APK/DM; verify hashes, cache code 1, unchanged config, Home and idle collectors. Import only run() from the existing Settings cohort, not its two-room main(). Expected: 90 verified captures; timings judged against both controls (largest median >=10% lower, root regression <=5%, zero invalid/drop/overflow). Report PSS without claiming leak freedom from a short sample. **Closed as failed:** the original candidate stopped at fresh code 3 before any candidate capture; that trial has not been retroactively passed.

- [x] **Step 6b: Complete the separate runtime-verified protocol.** All three fresh blocks and unconditional accepted SDK-profile restoration completed. Direct APK/DM/reference/OAT admission passed; the candidate failed the largest-frame timing gate and was rejected. The memory screen passed. See the final ledger below.
- [x] **Step 7: Review, revert, document and commit.** The candidate is rejected, accepted SDK-profile restoration is verified, the experiment HRF is removed, and the report/ledger are retained in local Git history. No new UI source was retained; no further native smoke is required on the unchanged reverted app for this rejected candidate. The broader performance goal remains open.

## Execution ledger

Pre-flight: no shared implementation interfaces across tasks (one task). Existing worktree branch codex/portal-filmstrip-family-performance verified at a562cd7, clean.
Ruling: proceed inline under the user's explicit delegated judgment and ongoing goal; no extra plan-approval pause. Cost if wrong: candidate is reverted after measured acceptance gates.
Ruling: profile configuration is verified against real APK content and native measurements rather than adding implementation-mirroring JVM tests; full repository suite still runs. Cost if wrong: malformed or unmatched rules are caught before device use.


2026-10-11 runtime inspection update:
- Steps 1–5 verified: accepted own-method coverage RED; candidate full checks GREEN; frozen
  source 50ac69d96f540797c8eaa13a3cab10cf826855dbee7803d858db542649ba1d28; 1,636 app tests;
  candidate d2720b06b88577e87c99887cc60f97912f3fd52ebd04f2b86cf884d66578ef89 and pinned bootstrap built.
- Step 6 original protocol stopped at candidate cache 3 before candidate captures; it remains
  failed. Inspection identified the prior queued 10,844-byte versus new 10,843-byte size test.
- Full live candidate reference mapping matches every bundled method/class/flag entry. A new
  runtime-verified before/candidate/after protocol was preregistered with fresh per-block
  installed DM/reference/OAT gates and the original timing thresholds. Its protocol SHA is
  eb8b464a8497e61c9cea1ee70a11c4b101d9edaab95e6ec046f5d40bc174d61a.
- Review found and corrected capture ownership/cleanup and memory enforcement issues in the
  private test harness. All 61 focused tests passed; final focused review checked all 27
  frozen hashes and found no remaining concrete blocker. The new native sequence is complete;
  its performance gate failed and the candidate is rejected. Accepted restoration, source HRF
  cleanup and final result documentation are complete. Step 7 is closed with the local evidence commit.
  See the verifier diagnosis report.
- No native column UI, Living Area or phone work; no GitHub operations.


2026-10-11 final runtime-verified ledger:

- Sequence session 99950 exited 0: baseline-before, candidate and baseline-after each completed
  20 warmups, 10 timed entries and 30 verified collector stops. Total: 90 captures / 30 timed
  entries; 30 Settings callbacks and 20 FIRST_DRAW flags per timed block, zero drops/invalid/
  overflow samples.
- The existing offline analyzer exited 0, verified protocol/trial/capture binding and emitted
  `matched-analysis.json`. Completion is distinct from acceptance: `directCompilationGatePassed`
  is true, `timingGatePassed` is false, `memoryGatePassed` is true and `performanceGatePassed`
  is false. The original code-1-only trial remains failed with no candidate captures imported.

| Metric (medians across 10 timed entries) | Baseline before | Candidate | Baseline after |
| --- | ---: | ---: | ---: |
| Largest Settings frame, ms | 89.23 | 94.97 | 95.80 |
| Mean recorded Settings frame, ms | 61.43 | 65.61 | 67.11 |
| Root composition, ms | 10.94 | 8.92 | 11.22 |
| Search composition, ms | 4.00 | 3.38 | 4.04 |
| Root measure, ms | 9.42 | 12.40 | 10.75 |
| Median PSS, MiB | 114.02 | 108.00 | 108.92 |

- Largest-frame change: candidate 6.44% higher than baseline-before and only 0.86% lower than
  baseline-after; both comparisons miss the required 10% improvement. Root composition was
  18.43% / 20.45% lower, passing the composition gate. Root measure was 31.54% / 15.33% higher.
  Reject the profile candidate; no APK promotion or individual-method machine-code conclusion.
- Memory disposition: accept the short-cohort screen. Candidate median 108.002 MiB versus
  larger control 114.021 MiB gives -6.018 MiB overhead (limit +20 MiB). First-three median
  107.335 MiB to last-three 110.364 MiB gives +3.029 MiB ordered growth (limit +5 MiB).
  PSS ranges before/candidate/after: 108.686-117.392 / 105.722-110.433 / 105.069-110.662 MiB.
  This screen does not prove long-run leak freedom.
- Direct admission: accepted SDK-only 34,922 methods / 5,389 classes; candidate 35,137 methods /
  5,389 classes. Exact bundled DM, complete reference inventory/checksum equality and own
  structural ARM64 OAT170 `speed-profile` / `install-dm` labels passed before every block and
  at final restoration. The candidate code-3 10,844-to-10,843-byte heuristic warning remained
  recorded; no cache-file write or retroactive change to the original trial was used.
- Final SDK-bootstrap -> accepted restore was unconditional. Accepted APK SHA is
  bc6af47b4184de27f81fc59c6502871bb8a796505c59c1e047ae859dd3f28298; final reference SHA is
  83484e493451704762ac461723eabdb2ed44e3738136ae9c77a73df7efe0d350 (11,414 bytes).
  `acceptedApkRestored`, `exactCompiledSdkProfileVerified`, `collectorInactive`, `homeRestored`
  and `configurationSchemaAndSourcesPreserved` are all true. OAT parsing establishes header
  policy labels; reference mapping establishes membership, not per-method machine-code coverage.
- Source cleanup completed after rejection/restoration: only the untracked 15-rule
  `app/src/main/baseline-prof.txt` was removed. Exact rejected contents are preserved privately
  in `settings-app-profile-2026-10-11/rejected-app-baseline-prof.txt`, SHA
  ee0f1a21c66a50448f3d32db53dcf8f67b36959d90231139d4f50485be7aba34. The restored 781-file
  app-source digest 1b89eb349152c1a43995a77a2272cc6d510f8d14e6e3d351fec2036cded2386e matches
  the committed helper base; candidate-build source 50ac69d96f540797c8eaa13a3cab10cf826855dbee7803d858db542649ba1d28
  remains historical evidence. No other code changes; the final report and plan are retained locally.
- Offline final review checked all 27 frozen hashes and reference/OAT-prefix evidence bindings;
  no concrete binding/restoration concern found. Protocol SHA remains
  eb8b464a8497e61c9cea1ee70a11c4b101d9edaab95e6ec046f5d40bc174d61a; analysis SHA is
  7dddb0b9ab180aff94fcfde09fe78abe407b2f9fdfbf707a1d1db5e64fb1a2ee. Native GET/stop remains
  non-atomic and the recorded exclusive-controller requirement remains necessary.
- Queued component diagnosis: `UNKNOWN_DELAY_DURATION` (`queued`) was the largest observed
  frame contributor in `runtime-verified/two-block-work-span-preview.json`. Medians of
  per-entry component means: 31.46 ms first control / 36.04 ms candidate; corresponding
  maximum medians: 64.44 / 69.44 ms. That preview compares only baseline-before and candidate,
  excluding the completed final control. It provides no causal proof. Nested work spans
  overlap; do not sum their medians or add them to frame-component medians to attribute frame
  time. Queued delay and main-thread measure/layout remain diagnostic leads on accepted SDK
  baseline. The profile experiment is closed as rejected; broader diagnosis remains queued.
  The browser-column mock is complete.

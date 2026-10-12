# Native Home app icon filtering validation

The cold Home app inventory rendered an icon and constructed a tile before rejecting hidden labels and duplicate launcher activities. The loader now rejects hidden labels before icon IO/bitmap preparation, and skips later activities only after the package has a successfully prepared visible tile. Failed label/icon preparation still permits a later activity for the same package. Sorting, curation overrides, folders, uninstall eligibility, tile metadata, synthetic tiles and the shared warm inventory cache remain unchanged. The helper is inline and keeps one accepted-package set and output list.

## Clock and scope

Original start 2026-10-12T10:08:46Z, deadline 10:38:46Z; no reset. The clock includes initial Settings/app inspection, the reference failure proof, implementation, tests/review, builds, native trial, cleanup and local commit. Family Area only. Parked issues not reopened. No Living Area, phone, GitHub, settings, note or audio operations.

## Focused and full checks

Nine pure JVM tests cover duplicate activities, hidden-label rejection, a hidden first alias with visible sibling, failed first icon fallback, unreadable-label fallback, output order, empty/broken inventories, and a 120-entry workload. A faithful reference of the previous render-before-filter sequence produces five expected test failures: 120 icon preparations and 120 labels, then 30 retained tiles. The optimized helper passes all nine with the same 30 retained tiles, 30 icon preparations (-75%) and 60 label loads (-50%). This is controlled work-count evidence, not a measured Portal cold-launch latency.

All 118 browser tests pass. Independent read-only review found no actionable semantic regression. The full HomeActivity file outside the private loader prefix is byte-identical to the accepted version; native selectors/UI unchanged. App source frozen before full builds.

## Device and full Android gates

Full Android gate: 1894 tests / 222 suites, no failures/errors/skips. The final 69 offline helper guards pass. Candidate/build/source admission passed before the single bounded Family Area trial.

The trial passed and retained the candidate. Two signed APK installs, bootstrap then candidate; Home -> Apps -> Home passes with three destination checks. Installed candidate SHA-256 f88ac4194f8f60dd0ccf2e3934a871357d950897ad4009dcff99b44bf590b0b6; bootstrap 89fae41cdf0838b9932deb98cf798d0366f55a0f2999d793680b33a74b3d84f5. Both APKs are 25447090 bytes. Exact SDK reference and bundled profiles match the complete candidate inventory: 8667 methods, 2576 classes, 285 app methods and 75 app classes. Individual app-method compilation is not proven.

Exact served remote assets remain unchanged: JS 152124 bytes, core 19089 bytes, CSS 22706 bytes. Configuration/source snapshots are unchanged, final Home ready, collector inactive, no rollback. Frozen app source f3e4a1f04e54df26a9afcf01ef5ff6f55a334d2e38f487028e94cda3b77dd6b8 / 860 files. Only HomeActivity.kt, HomeAppIconPreparation.kt and HomeAppIconPreparationTest.kt change in app source; HomeActivity outside the loader prefix is byte-identical. No settings/note/audio/Living/phone/GitHub operations.

Device cold timing and the exact work reduction for the installed app inventory have not been measured. The claimed 75%/50% reductions are the controlled 120-entry icon/label workload. Native checks verify functional navigation and deployment/configuration/profile preservation. Validation, commit and record cleanup remain inside the original clock.

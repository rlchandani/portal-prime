# Fleet bulk-update implementation plan

1. Main: preserve the existing outcome composition in a directly testable helper,
   record the original observation clock and prepare rollback/config snapshots.
2. Parallel JVM worker: write ordered batch and completion regression cases in
   `FleetUpdateResultsTest.kt`; main captures baseline failures before the fix.
3. Main: correct aggregate status/counters, catalog/request admission and
   discovery completion. Keep the installer and one-attempt batch unchanged.
4. Parallel native worker: build an isolated generated-result shell fixture for
   the actual optimized helper, with no production keeps or fallback helper.
5. Main: run required JVM/debug/optimized gates, inspect artifact identity, and
   perform Family scoped runtime/API acceptance. Request bounded static review.
6. Main: deploy final accepted APK to both rooms, verify complete artifact hashes
   and preserved configuration, remove owned scratch/registries/processes, commit
   source and update the shared roadmap/receipt before the original deadline.

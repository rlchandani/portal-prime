# Calendar preparation reuse — issue 51

Original unreset clock: 2026-10-12T14:57:13Z–15:27:13Z. Family Area only;
Living Area and the phone are frozen; parked work remains closed; no GitHub.

CalendarFeedReader already shares raw downloads. Every reader still unfolded the
same text and parsed every event/date again before its caller-specific filtering.
The candidate retains private immutable definitions keyed by exact source text,
private timezone rules and FORMAT locale. Occurrence expansion, now/horizon filtering,
sorting and the500-result cap remain fresh on each read. Output lists are independent.

Completed retention is bounded to four documents /30 seconds /262144 source characters
and2048 valid definitions per retained document. Oversized feeds stay supported without
retention. Existing SingleFlightValueCache owns concurrency/expiry/failure handling.
Raw downloads, recurrence rules, display windowing, settings, assets and UI remain unchanged.

Baseline20 tests:10 intended failures and10 controls. Final21 focused cases and full
1973 JVM tests /228 suites pass with zero failures/errors/skips;138 browser tests pass.
Independent review CLEAR; interrupted-waiter test alone does not prove the waiter reached
the cache, while existing SingleFlightValueCache interruption coverage is retained.

Source01792035e3bb04142e8820ba975eeb24fe06ca1e7811e755ecc70b168f43f3c7/872files.
Debug and signed R8 benchmark assembly completed successfully. The 253 offline guards
passed, preserving all 219 inherited guards. Full mapping/admission preserved 8667 profile
methods, 2576 classes, 285 app methods and 75 app classes.

One admitted Family Area trial completed with two signed APK installations, exact installed
candidate/profile/assets verification and native Home → Apps → Home within the same owned
HomeActivity. Configuration and source remained unchanged. One owned Dream normalization
was verified before installation; no rollback was needed. Home was verified at native
completion, not continuously during the later idle monitor.

The 180-request /180.007-second public asset monitor completed with zero failures and no
credentials, UI inputs or setting edits. Observed median11.969ms, p9517.023ms, p9922.781ms,
maximum35.952ms are uncontrolled Wi-Fi observations, not a causal latency comparison.

Candidate APK:49933f9ecebfa7d83e12ae97f54d756b503898aceb0c88671a6c2326707dd00b
/25448482bytes. Bootstrap:70efbc4591ba12b9cd9afad6121200455ab03f460c1e8772836118b81b792c9f.
Certificate:4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6.

All build/native/monitor processes are terminal. The ordinary local benchmark APK was
restored from the accepted candidate after the bootstrap build. Commit identity and full
original-clock closure time are recorded in issue-ledger.json. Living Area, phone, GitHub,
settings, notes, audio and collector operations were zero.
Controlled preparation count checks are not device latency, heap, FPS or website claims.


Controlled results:20 warm readers→one preparation and eight concurrent readers→one
preparation. These counts establish avoided repeated parser work in those workloads.
Calendar dataset CPU time, Android allocation/heap, frame-time and whole-app speed were
not measured. The broad performance/quality goal remains incomplete.

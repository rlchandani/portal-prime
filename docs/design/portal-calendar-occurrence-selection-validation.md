# Bounded calendar occurrence selection — issue52

Original unreset clock:2026-10-12T15:22:12Z–15:52:12Z. Family Area only.
Accepted51 commit:af32db5c18e26eeca0269cad997fb53f6119b0ec.
Living Area/phone frozen; parked work stays closed; no GitHub.

Source evidence: CalendarFeed.evaluateDocument builds every eligible recurrence Event,
stably sorts the entire list by startMillis and then takes500. The intended optimization
keeps the same earliest500 with stable encounter-order ties. Event construction is deferred
until a candidate can enter the bounded result. Time/horizon/recurrence logic stays fresh;
new selection instances belong to each evaluation, with no new shared cache or settings.

Hardware context: Family Portal+ API29/QCS605, Adreno615, approximately2782MiB usable RAM.
Avoiding discarded objects is relevant to this memory budget; it does not establish that
calendar processing is the cause of the parked slideshow frame spikes.

17 focused cases cover construction avoidance, tie preservation/eviction, arbitrary and
reverse order, failed replacements, independent results/readers, extreme timestamps,
random stable-sort equivalence and real CalendarFeed cap/recurrence/filtering/warm reads.
Initial test compilation stopped for incorrect newline escaping; log preserved as
baseline-test-compilation-error.log; correction uses the original clock. Corrected baseline17 cases:3 intended failures,14 controls,0 errors/skips. Final1990 JVM
cases /229 suites,17 focused cases and138 browser cases passed with zero failure/error/skip.
Debug plus signed optimized assembly completed successfully in2m56s. Independent review
CLEAR verifies exactthreefilehashes and unchanged calendar methods outside the3replacements.

Controlled sorted workload:20,000 offers construct20,000 values before the change and500
afterward (97.5% fewer). Reverse and random inputs preserve earliest stable results;
equal-time entries retain first encounter order. Source queue size remains at most500;
actual Android peak heap/GC is not measured. Compiled debug bytecode independently shows
accepts() before direct Event construction, retain() afterward, and no capturing factory
allocation in evaluateDocument. Full signatures and profile admission passed:8667 methods /2576 classes /285 app methods
/75 app classes. One Family-only native trial completed with2signed installs, exact
installed candidate/profile/assets/source verification and Home→Apps→Home in the same
owned HomeActivity. Configuration unchanged; one verified ownedDream normalization;
no rollback, settings/notes/audio/collector/Living/phone/GitHub operations.
Final Home is proven at native completion, not continuously during idle monitoring.
The three-minute public-asset monitor completed:180/180 exact responses,zero failures,
180.010seconds,median11.924ms,p9516.264ms,p9921.086ms,max23.579ms. No credentials/UI/
settings inputs; these are uncontrolled Wi-Fi observations, not a latency comparison.

Final appsource512c74664cfdb867dafdf4fec218b327e7ab90a1c1b128824cd7ffcacd47661f/874files.
Actual signed candidate outputf7727d56bb5cad07c3e5f7b8b81934c3e3e943290a4db744d83d7fe634b3bcad
/25448482bytes. Candidate and bootstrap are archived. BootstrapSHAece5c6fb7594ba66ad9fff89491ccee8d8a23cf80f269a1a55c19a17ce7ded57
/25448482bytes; certificate4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6.
All290 offline guards passed, preserving253 inherited guards. Final source874 files and exact canonical hash verified. All build/native/monitor
processes terminal, lease available, ordinary candidate APK restored, no rollback.
The accepted change is committed on codex/portal-calendar-occurrence-selection;
commit identity and full original-clock closure are recorded in issue-ledger.json.

Controlled construction counts are not Android heap, GC, frame-rate, website or phone
latency measurements. Overall goal remains incomplete.


Tradeoff: small feeds below500 do not avoid Event constructions; heap and ordering
bookkeeping has a cost. No small-feed latency or Android heap/GC speedup is claimed.
The established benefit is bounded retained occurrence data and rejection of late/equal
values without Event or factory closure construction. Descending input may construct
every replacement, while retained queue values remain bounded. Prepared definitions and
per-series recurrence lists are outside this bound. Overall goal remains active/incomplete.

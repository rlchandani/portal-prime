# Screensaver presentation parity

Fresh bounded unit: **07:51:21–08:21:21 UTC**, including cleanup.
Previous turn was verified progress: timer fix `1ca6a54b`, exact 1.67 in both
rooms, native Family acceptance and restored test state.
Branch: `codex/settings-visibility-20261102`.

Coordinator's implementation choice: preserve native disabled-parent behavior
in the generic screensaver domain. Only `enabled` remains visible when off;
Advanced does not expose inactive children. Registry predicates govern the
remote schema. The separate calendar domain remains available for Home's shared
feed; bespoke sleep/source editors retain their owners. No change to valid
inactive writes, stored choices, inactive validation metadata or side effects.
No new setting or broad settings redesign.

- [x] Watch actual registered-domain schema/native parity assertions fail.
- [x] Add missing enabled predicates to screensaver specs, preserving child gates.
- [x] Verify the real registry × enabled/disabled × Advanced × source/style matrix.
- [x] Parallel browser worker verifies real controller refresh against fixtures.
- [x] Full JVM/debug/optimized gates; source review; preserve signer and state.
- [x] Scoped Family verification and exact final artifact deployment to Living.
- [x] Restore temporary state; update receipt/roadmap and commit locally by cap.

Main owns Kotlin, builds and devices. Browser worker owns one JS regression test
and review document. No GitHub hooks/auth/publication, service or migration.

Portal/PWA acceptance and both-room exact1.68 deployment passed. Companion group
preflight correction has separate local gates/deployment status in the consumer
review; no physical phone acceptance is inferred.

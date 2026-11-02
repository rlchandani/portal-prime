# JSON request body integrity plan

**Unit:** 10:56:13–11:26:13 UTC, including cleanup. Branch
`codex/fleet-json-body-integrity-20261102`, base `a55b47b8`.

**Reproduction:** Actual Family1.71 accepted a100-byte declaration containing
only the two-byte complete JSON prefix `{}` with HTTP200/ok:true on `/dev`.
The body contains no mutating fields; complete room baselines are saved.

**Design:** Require the full remaining declared text body. Reject bodies beyond
the configured byte cap before allocation/read rather than return a prefix.
Track each read immediately so failure/recovery/drain never crosses the body.
Map request-specific incomplete/oversize errors to HTTP400/413; other handler
errors retain their existing server-error path. Keep complete UTF-8, exact byte
limits, deliberate empty requests, binary uploads and authentication compatible.

The only old prefix-reader callers are tests; production callers use this method
for complete JSON command bodies. Update those tests to check rejection without
consumption, retaining streaming/accounting coverage.

- [x] Inspect readers/callers and capture nonmutating real-device reproduction.
- [x] Observe pure reader and actual server regression failures.
- [x] Implement reader/mapping and independently review.
- [x] Run full relevant gates and optimized same-signer1.72 candidate.
- [x] Verify incomplete/oversize/complete requests on Family without preference mutation.
- [x] Deliver exact accepted APK to Living; verify both installed artifacts/configuration.
- [x] Update roadmap/evidence, cleanup and commit within30minutes.

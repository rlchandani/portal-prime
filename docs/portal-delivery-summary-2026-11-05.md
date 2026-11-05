# Offline delivery summaries

The release checker already gathers build evidence, and the install observer
already reconciles uncertain acknowledgments. The remaining repeated work was
formatting each saved delivery receipt with custom Python. Two recent snippets
failed before writing, and one receipt needed an invalid documentation link
repaired. This slice replaces the core summary formatting with one offline CLI.

```bash
python3 -B scripts/portal_delivery_summary.py \
  mocks/portal-catalog-null-numeric-20261105/verification.json \
  --output /tmp/portal-delivery-summary.md
```

Use a public `verification.json` with the current schema-v1 two-room delivery
structure, an optimized `performance` artifact, and nonempty limitations.
The output path must be new. Exit 0 means the summary was written; exit 2 means
the supplied evidence or output was rejected. Diagnostics omit input contents
and paths. The helper uses Python's standard library.
It writes and closes a temporary file in the destination directory, then creates
an exclusive hard link to publish complete bytes. Filesystems must support hard
links; an unsupported operation is rejected without replacing the destination.

Before rendering, the helper checks the recorded successful build, nonempty JVM
test/suite counts with zero failures/errors/skips, accepted review, and both
rooms' installed bytes, SHA-256, version and service readiness against the
recorded candidate. It checks timezone-aware issue clock ordering and copies
the original clocks, recorded timestamp, scope and limitations. It does not
infer completion time or whether the target was met from the receipt timestamp.

Only selected fields appear. Unknown fields, nested logs, acknowledgment details
and credentials are excluded; scope and limitation prose is normalized and
escaped for explicit Markdown links, HTML, fences and lists. Markdown viewers
may still automatically link plain URLs. **This is not a secret scrubber:** supply public
evidence, and keep private data out of the prose itself.

The generated [1.193 summary](portal-delivery-summary-1.193.md) is the first
adoption. The full [catalog receipt](portal-catalog-null-numeric-2026-11-05.md)
remains authoritative for native acceptance, preference preservation, cleanup
and source bindings.

## Verification and limits

Ten new test methods use completed 1.192/1.193 evidence, malformed JSON,
duplicate keys, wrong types, missing/failed gates, both rooms' mismatches, invalid
clocks, Markdown/HTML injection, deterministic output and existing-output
preservation. Before implementation, seven methods failed because the helper
was missing; the existing-output control passed. Review then reproduced a real
Markdown fence failure and added a real encoding-write failure check. The
encoding check establishes failed-write publication and cleanup behavior; it
does not independently inject disk-full or close failures. The CI workflow already discovers
`test_portal_*.py`, so no workflow change was needed.

These are consistency checks on supplied records, not new builds, fresh health
checks, cryptographic verification of files, or fresh device acceptance.
Detailed native/regression/settings/cleanup evidence remains in the input JSON.
Write/close failures do not publish a partial destination. A failed scratch
cleanup may retain the temporary public bytes. Existing outputs are never
overwritten. Crash durability and concurrent source-file modification remain
outside this formatting slice.

Original issue start **2026-11-05 02:47:12 UTC**, discovery cap **02:52:12**,
target **03:02:12**, hard limit **03:17:12**. This is an offline tooling change;
the rooms retain **1.193/code187**. Previous issue clocks and acceptance limits
remain in force. No app version bump or device operation was needed.

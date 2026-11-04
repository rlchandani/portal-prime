# Browser Group Photo Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to execute this approved plan in the current session.

**Goal:** Import selected photos to one or both paired Portals with separate folders and truthful per-file results.

**Architecture:** A `PhotoImportBatch` in the existing browser core captures destinations and owns upload outcomes. The shipped import sheet and results panel use it through existing companion and photo-upload APIs. Viewing another Portal does not change captured destinations.

**Tech Stack:** JavaScript, Node browser harness and Chromium; Android assets bundled by Gradle.

**Spec:** `docs/design/portal-browser-group-photo-import-review-2026-11-03.md` and its contract. Human approved local implementation on November 3; live transfers and publication remain separately gated.

## Global constraints

Use generated fixtures only. Preserve pairing and stable device identity before every POST. At most two Portal lanes, sequential files within each lane. No overwrite, silent retry, filename-based confirmation, or replacement of unresolved results. Keep separate folder choices in memory. Paginate files and results eight rows at a time. Reuse the isolated checkout and keep publication disabled.

## Review focus

- A view change must not retarget an import or publish a peer gallery.
- Missing or changed identity and replaced pairing must block writes.
- Malformed acknowledgements and lost responses must remain unconfirmed.
- Retry must select only definite failures and preserve the captured destination.
- Stopping must cancel queued files while preserving an honest in-flight result.

## Tasks

1. Add behavioral controller tests and observe the missing feature fail. Implement captured destinations, bounded lanes, preflight, exact acknowledgement, stop, retire, retry and collision rename.
2. Connect the recipient picker, per-Portal folder fields, bounded preview and results ledger. Add real shipped-controller harness tests and update the three earlier view-cancellation contracts to the approved continuation behavior.
3. Run all browser tests, isolated real-browser layout checks, JVM tests and debug assembly. Review the complete change independently, update approval and evidence records, and make a verified local commit. Native transfer acceptance remains pending.

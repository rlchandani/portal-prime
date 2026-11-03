# Fleet preference backup integrity

The host backup command now publishes only a validated preference capture.
The original command exited zero, claimed one device backed up and advanced
`latest` after a zero-exit listing returned `ok:false`; it captured **zero XML
files**. That failure is reproduced with a synthetic CLI, without owner data.

## What changed

- Validate literal JSON success, app identity and installed-app markers.
- Require the exact requested directory and a nonempty safe readable XML list;
  pull and validate every listed preference map.
- Use private staging, unique completed folders and atomic `latest` replacement.
- Retain previous snapshot bytes and clean partial data on failure.
- Require owned backup directories and reject symlink/obstructed parents.
- Continue other rooms on preparation or capture failure; return nonzero if
  any room fails.
- Keep progress/error reporting best-effort when output pipes close.
- Support an explicit debug-package backup path and Python runtime selection.

The existing provisioning CI job now selects the backup script/helper/tests and
runs the offline integrity suite. Hosted CI was not run or published.

Final local gate: **23/23 end-to-end methods**, zero failures/errors, **14.136
seconds** on macOS Python 3.13 and Bash **3.2.57**; syntax and whitespace checks
passed. The original script failed all 15 initial methods with 42 failure entries.
The first implementation exposed an empty-package regression; it was corrected.
Four initial independent review findings and two remaining reporting cases were
addressed; final static review found no blocker.

## Actual Family acceptance

The first read-only capture saved and validated **43 of 43 listed XML maps** in
**3,267.234 ms**. Directory/file permissions were `0700`/`0600`, and the registry
copy matched. The complete settings profile, layout, photo sources and
screensaver configuration matched before and after.

A second capture deliberately closed stdout after the first banner. It exited
zero, saved the same **43 listed files**, advanced to a unique snapshot and
retained all previous snapshot bytes, in **10,799.328 ms**. Complete saved
configuration remained unchanged. The two final error-notice guards were added
after this native success check and are covered by the final offline failure
case with both streams closed.

No device write, restore, install, navigation or new Portal artifact was needed.
Both rooms retain **1.87/code81**; Family alone was used for native acceptance.
Owner XML and registry contents were never copied into public evidence.
The generated native test kit, including its credential registry and both test
snapshots, was removed after validation. These temporary acceptance copies are
not retained as a production recovery backup; original connection data remains.

## Limits

This captures Immortal preferences and install metadata, not photos, other
applications' data, an atomic whole-app snapshot or power-loss durability.
Concurrent preference mutation and restore fidelity are not newly accepted.
The existing raw restore targets the release package; selecting a debug backup
does not provide cross-package restoration. Transfer-stall policy, Windows
execution, hosted Ubuntu/Python 3.12 and physical-phone acceptance remain outside
this unit. No general speedup or whole-product completion is claimed.

Original issue clock: **2026-11-02 22:56:14–23:26:14 UTC**, including cleanup.
Final regression counts, source identity, review and cleanup are recorded in the
[sanitized structured receipt](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-widget-gallery-20261102/evidence/fleet-backup-integrity-verification.json).

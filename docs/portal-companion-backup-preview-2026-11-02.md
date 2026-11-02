# Companion backup preview checkpoint — 2 November 2026

Companion **1.7.6-music-preview /code 18** now exposes Cancel during backup preview,
counts all inflated ZIP entries against the existing total budget and prevents
cancelled results from replacing newer work. Both Portals retain exact 1.85/code 79;
this unit has no Portal source, APK, profile or owner-data changes.

Validation: **632 JVM /80 suites**, debug/test builds, four unique native methods
at actual 100%/130% (**8 executions**), actual cancelled-reader settlement and
newer-result retention. Two additional executions verify corrected native card
capture. Single emulator cancel-to-idle samples: **65.005 /90.373 ms**. Complete
installed emulator APK hash matches the candidate; original AVD, unrelated
emulator and Family transports are retained and owned resources removed.

The phone repository contains the detailed receipt in
`docs/companion-backup-preview-validation-2026-11-02.md`.
[Structured evidence](../mocks/portal-widget-gallery-20261102/evidence/companion-backup-preview-verification.json)
records actual artifacts and limits. Public pixels cover the generated isolated
BackupCard, not the whole app or a physical phone. Physical installation, owner
archives, real ADB backup/restore, FPS and universal provider cancellation remain
unclaimed. Original **20:51:32–21:21:32 UTC** clock includes cleanup.

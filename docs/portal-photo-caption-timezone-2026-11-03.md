# Photo captions after a timezone change

Exact optimized 1.126/code120 is installed and fully hashed in both rooms. Newly read EXIF captions refresh the current default timezone inside the existing synchronized parser, preserving the local capture date and on-this-day comparison after a zone change. Six unchanged repaired tests pass; 4,343 JVM tests/451 suites pass with zero failures, errors or skips. Debug, optimized, R8 and vital lint pass. Four complete maps and 13 raw preference states per room remain unchanged; limited HTTP200 diagnostics record 1682/2015 lines with no fatal/ANR markers. Credentials are cleaned; public 1.83/code77 remains.

The shared EXIF formatter retained its startup zone while date and memory consumers used the current zone.  Fresh metadata reads now align those consumers. The UTC→LosAngeles→Kiritimati→UTC fixture checks literal epochs; LA January 1,2020 displays that date and a 3-year memory at January 1,2023 noon.

- Actual production read/parser/formatDate/memoryYears use mocked EXIF getters; Android Log is mocked only during EXIF mock setup. No real EXIF decoder, physical timezone change, caption UI, native/optimized caption-correction execution, provider or SMB acceptance.
- Already parsed Meta values, EXIF offset tags and concurrent default-zone changes remain unselected. This fixes newly read captions only; existing memory/fallback rules remain. The one current Family screenshot shows clock/date without a photo or caption and supplies no caption visual acceptance.
- Initial 6-test attempt had 5 fixture-initialization failures and 1 control; excluded from timezone acceptance. Fresh repaired unchanged-source baseline has 2 AssertionErrors and 4 controls. Same six bodies and literal expectations are retained.
- Public manifest remains1.83/code77. No phone, publication, HA, prior-scope reopening or broader completion claim; original cohort clock includes all discovery and delivery.

103 tasks: 67 delivered, 26 verified, 3 approval, 6 parked, 1 queued. All 102 older objects and 340607 raw suffix bytes are preserved; Weather stays default. Original 14:41:14.795668–15:11:14.795668 UTC clock retained. Structured proof: `mocks/portal-widget-gallery-20261102/evidence/photo-caption-timezone-verification.json`. Broader goal remains active.

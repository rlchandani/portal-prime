# Native folder and favorite edits preserve newer layout changes

Exact optimized **Portal 1.122/code116** is installed and fully hashed in
Family Area and Living Area. A folder or favorite action based on an older
assignment map or order now refreshes that saved field and asks the user to retry.

Home and Apps retained complete assignment maps and favorite order while open.
After a remote replacement added `com.remote → Remote`, a later native folder
move could replace that acknowledged map with its older map plus only the moved
app. Removed entries could return, a newer folder rename could be undone, and
newer favorite order could disappear.

The two native snapshot boundaries compare the current logical field with the
retained field and save only when equal, under the companion mutation lock.
All four generic UserLayout setters share that lock and retain their existing
whole-field replacement semantics. All eight Home/Apps edit actions use the
boundaries and reload canonical saved state. A rejected rename does not navigate
to an unsaved new name. Existing keys, codecs, component and shortcut identity,
offline membership, exact-case names and explicit empty folder overrides remain.
No new setting, schema, widget policy or layout design.

## Verification

- Ten independent literal tests first reproduce **five AssertionError failures**
  with five controls on the extracted-equivalent production snapshot boundaries.
  The same frozen test bytes then pass. This establishes the generated storage
  contract. It is not an untouched-APK or physical Compose baseline.
- The extracted baseline keeps the old saved expressions. Its added canonical
  reload and local exception ordering are explicitly qualified. The initial
  stale on-disk APK copied after the failing combined Gradle command is excluded
  from native admission. A separate successful debug assembly supplies the
  actual extracted baseline with both snapshot methods in `classes6.dex`.
- **4,313 JVM tests in 447 suites**, zero failures, errors or
  skips. Debug, optimized, R8 and vital lint pass in **1m 48s**.
- Actual debug Family storage checks improve 7/14 to 14/14: five stale-save corrections and two generic-setter monitor corrections, with five product controls and two harness controls retained. Each variant invokes assignment snapshots 5 times, order snapshots 4 times, remote read/validate/apply 17 times each, folder rename 2 times, direct layout loads 31 times and generic setters 2 times across 12 generated Contexts. Both 2 generated workers retire. Generated preference writes are 96/91, Context preference calls 212/216 and preference reads 116/125 for baseline/candidate.
- Two monitor controls hold the companion lock: each actual generic setter
  worker is blocked with zero preference calls until release, then performs
  exactly one write. The five stale candidate cases perform zero replacement
  writes. These bounded generated controls do not establish universal concurrency.
- Generated storage JVM and debug native acceptance only. Eight native menu actions and conflict reload/toast/navigation are source-reviewed. Optimized storage-boundary calls, physical Home/Apps edits, real HTTP acknowledgment, phone workflow, API28, raw writers and disk durability remain unverified. Optimized snapshot methods survive as renamed static methods; the singleton, remote store declarations and validation getter required by this exact debug fixture are unavailable. Zero optimized calls; no keep changes.
- Two accepted native parents and owned scratch retire normally with zero
  faults. External timeout/fault cleanup remains unverified. Actual compiler
  output has three Java8 obsolescence warnings and unchecked-operation notes.

## Preservation and limits

Four complete settings, layout, source and screensaver maps, room names and
Dev Mode match preflight. Twelve preference states per room match: Family eleven byte-identical files and one unchanged absence; Living eight byte-identical files and four unchanged absences. The original eleven captures stay unchanged; the touched UserLayout file has an additional before/after byte comparison. Family font, accessibility,
original Chrome task, resumed frame, awake display and exact installed APK remain.
The root inspected the current Family frame: Current photo frame with white 4:55 clock, Tue Nov3 date, Weather overlay and debug badge; screenshot retained privately.
This does not establish editor UI acceptance. Living visual acceptance remains open.

Limited lines=1500 requests produce 1645 Family and 2023 Living actual lines, with HTTP200 and zero fatal or ANR markers. This is limited installed observation. Copied registry credentials are
removed and original registry hashes unchanged. Public `version.json` remains
**1.83/code77** byte-for-byte; its guard deliberately exits 1 for local
1.122/code116. The native Fleet CLI is unchanged.

Generated storage JVM and debug native acceptance only. Eight native menu actions and conflict reload/toast/navigation are source-reviewed. Optimized storage-boundary calls, physical Home/Apps edits, real HTTP acknowledgment, phone workflow, API28, raw writers and disk durability remain unverified. Optimized snapshot methods survive as renamed static methods; the singleton, remote store declarations and validation getter required by this exact debug fixture are unavailable. Zero optimized calls; no keep changes. Natural preference callback delivery, multiprocess/raw-writer behavior,
storage faults and disk durability remain open. Previously delivered widget,
parsed-cache and folder-rename contracts remain. Chime is unchanged from its
prior negative audit and receives no new slot. All earlier held and exhausted
clocks remain; no phone, HA, authentication, push or publication operation.

The sanitized record is
`mocks/portal-widget-gallery-20261102/evidence/user-layout-save-conflict-verification.json`.
It binds actual source, extracted baseline, frozen regressions, artifacts,
generated native observations, delivery, preservation and cleanup. Closing
documentation/browser QA and commit are recorded separately to avoid circular
proof bindings.

APK SHA-256: `349b301559026e35fabf0e82edb11d218947da5b8dc0edb609591305a1a9adf7`.

**98 tasks: 63 delivered, 25 locally verified, 3 approval, 6 parked, 1 queued.**
All 97 prior task objects and their complete raw suffix are retained, with
Weather still the default. The original **12:41:33–13:11:33 UTC on 3 November**
clock includes this entire unit without reset. Broader goal remains active.

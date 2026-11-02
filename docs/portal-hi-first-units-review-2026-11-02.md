# Portal HI first units: independent QA review

Reviewed the working diff on `codex/portal-hi-widget-fixes-20261102`, based on
`6c018dc80524db2dad58e4542174326b9c673cd5`. Read `AGENTS.md`. Production and test
files were read only.

**Finding: no concrete defects identified in the two bounded fixes.**

## Photo Memory edit destination

`ApprovedWidgetCell` now maps Photo Memory settings to `sources`.
The existing settings navigation registers that page as Photo sources, and its
content does not require the slideshow to be enabled. The route continues
through the existing internal navigation and settings host. The primary photo
action, hosted app-widget settings, timer settings and other edit destinations
are unchanged by this diff.

## Alarm caption refresh

- The widget observes only AlarmConfig's owned fields while its page is active.
  It registers before reloading, covering changes made while hidden and the
  subscription/readback window. The existing observer retains its listener,
  unregisters once, and rejects queued callbacks after disposal.
- `produceHomeState` includes page activity in its effect keys. The alarm loop
  uses cancellable `delay`, exits immediately when disabled, and starts with an
  immediate tick when enabled or reactivated. It uses ordinary delay, so the
  helper's retained network-refresh deadline does not postpone its restart.
- The nested widget-page and launcher lifecycle scopes mark other widget pages,
  other tabs and stopped Home inactive. Inactive pages retain their last state;
  this change adds no ongoing preference subscription or clock loop there.
- Config, tick and activity changes invalidate remaining minutes, which are
  calculated from current wall time. An hour/minute/day edit therefore does not
  wait for the next minute tick, even when the enabled flag stays unchanged.
- The timing helper delegates to the same next-trigger calculation used by
  AlarmScheduler. That calculation selects a strictly future occurrence:
  exact trigger time advances to the next candidate. A fired one-shot is
  disabled by the existing receiver; the widget observer reads that change.
- Positive partial minutes round up. Disabled or unmatched schedules return
  null. Refresh delay stays within 1–60000 ms, including negative timestamps and
  exact boundaries. Calendar/TimeZone and Math.floorMod(long, long) are compatible
  with minSdk 24. java.time appears only in JVM tests.
- The existing time-format resume refresh and inline enable/scheduling action
  remain intact. No scheduler, persistence or settings-registry change is added.

## Verification and limits

Read the new helper and all 14 timing tests, plus the relevant existing
observation tests, scheduler, local-time/DST implementation, receiver, page
scopes and navigation. `git diff --check` passed for the tracked HomeActivity
change. All Git commands used `core.hooksPath=/dev/null`.

No Gradle, unit-test execution, lint, APK build, device command or runtime
measurement was performed: the coordinating task owns those checks. This
review does not establish actual Compose cancellation timing, Android callback
delivery, device Back/source-edit behavior, live caption behavior, APK
compatibility, or measured performance. No build/test/device result is claimed.

Reviewed file SHA-256 values:

| File | SHA-256 |
| --- | --- |
| `HomeActivity.kt` | `bb65b699ee0c3d58701525dfe1a9cab210953cba9280c181ccf55d28cdef6968` |
| `HomeAlarmWidgetTiming.kt` | `4216279ab9886dec12679d5d07cd2c81e8ddd902489491c5ca107205a633012b` |
| `HomeAlarmWidgetTimingTest.kt` | `f992b41fe24fb5197f7f4ca8c14f5b54f6ab058aa7ed8655270c0f95cbb15821` |

Only this review document was written. No GitHub, authentication, push or
secret access was used. Existing performance/slideshow issues were outside
the review scope.

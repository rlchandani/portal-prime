# Keep recurring events in their source timezone

Exact optimized **1.153/code147** is installed and fully hashed in both rooms,
one install each. Living's uncertain acknowledgment was reconciled with seven
read-only observations. All43/30 preference files and four complete maps match;
Family font, accessibility, original Chromium activity, resumed frame and
awake state remain. Public1.83/code77 is unchanged.

The first DTSTART parsed correctly, but recurring occurrences were generated
in the Portal's timezone. UTC events could drift an hour across the Portal's
daylight saving change; named-zone events could miss their own daylight saving
change. A weekly source Monday near midnight could even become a different
weekday. The prepared event now retains the timezone from its accepted DTSTART.
Both recurrence calendars and non-UTC/date UNTIL use that zone; UTC UNTIL
retains UTC precedence.

Floating local and all-day events retain the captured device timezone.
All-day exclusive midnight boundaries, absolute timed durations, COUNT before
EXDATE, overlap windows, result and generation caps, old-series seeking and
existing monthly/yearly clamping remain. Public Event fields, feed sharing,
cache keys, fetching, URL policy, settings, persistence and UI are unchanged.
Unknown-zone fallback, invalid/nonexistent local times and VTIMEZONE parsing
remain outside this change.

All **4,491 JVM tests /468 suites**, debug and optimized assembly, R8 and vital
lint pass. Fifteen frozen generated ICS cases improve thirteen expected
AssertionErrors and two passing baseline controls to fifteen passes.
They cover UTC spring/fall DST,
Berlin/New York/fixed India source zones, source weekdays, EXDATE/COUNT,
UTC/floating/date UNTIL, old-series seeking and mixed per-event zones.
Every expected start and end is a literal UTC instant.
Independent source and native/artifact reviews pass.

Six frozen actual-debug/API29 parser cases improve four baseline failures and
two controls to six passes, using identical Java, DEX, driver and literal gates.
Both fixture packages, processes and external directories retire.
The fixture passes only generated strings and literal numbers
into the isolated embedded parser, with timezone and all locale defaults
restored per case and globally. Zero context/provider/fetch/preference fields
describe source containment, not instrumentation of unrelated loader internals.

Mounted calendar UI, live feed/provider/cache-network behavior, optimized/API28
runtime, Living functional acceptance and device timezone-setting changes remain
separate. No household calendar, media, phone, account or provider was used.
Original11:24:52–11:54:52UTC window remains; the11:39:52 target had elapsed
before selection at11:39:59.
The unexecuted driver template was adapted before either native claim;
a host binding-name lookup was corrected without changing any source or gates.
Those setup qualifications and four Java8 compilation warnings are retained.

Evidence: `mocks/portal-calendar-recurrence-zone-20261104/README.md` in the repository.

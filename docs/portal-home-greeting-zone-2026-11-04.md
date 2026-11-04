# Home greeting: refresh retained clock timezones

Signed **1.166/code160** is verified locally; device deployment is **unverified**.
All **4,555 JVM tests / 474 suites**, debug and optimized assembly, R8 and vital
lint pass. One combined independent review has no blockers; root fully read it
and rehashed all 32 bindings before attempting delivery. All 23 source assets
match source and 1.165; only compiler-generated optimized baseline.prof differs.

Family's ADB serial read failed before any install, followed by a Fleet
“host is down” response. Living's single upload attempt timed out without a
confirmed push. No repeat install was issued. The last complete device readbacks
were **1.165/code159** before this attempt; no post-attempt installed-version,
settings-preservation or device-health acceptance is claimed. Delivery can resume
with read-only reconciliation when the known Portal connections return.
Living's observer ended in timeout after 57 read-only observations, with no
installed APK readback.

After a timezone change, Home's greeting used the current zone while its remembered
time, weekday and date formatters kept their construction zone. A UTC-to-India
transition at November 4, 2026, 20:00 UTC could show Wednesday/November 4/20:00
beside a greeting selected for Thursday at 01:30.

The greeting now samples the current timezone on each composition, updates its
three retained formatters and uses that same zone for its hour selection.
Four inserted lines and one existing-line adjustment leave the full remaining
Home source exact: format patterns, locale, remember keys, minute producer,
greeting selection, typography, layout, callbacks and storage are unchanged.

A frozen eight-vector diagnostic compiles the exact formatter/hour text captured
from each Home snapshot. Four baseline literal mismatches and four passing controls
become eight passes, with identical case names and expectations. It covers forward
and reverse transitions in 12h/24h, unchanged UTC, return-to-UTC and fresh India.
The fixture restores timezone and every Locale category.

This is a source-captured leaf check. Its construction-only `remember` adapter
retains the formatter fields; it does not run the actual composed function.
Composed UI, retained page lifecycle, immediate timezone broadcasts, native
rendering and optimized runtime remain unverified. Locale changes and tick cadence
are separate; no device timezone was changed for validation.

Original discovery **15:00:49 UTC**, selection **15:04:43**, target **15:15:49**,
hard deadline **15:30:49**, without reset. The separate audio-alarm observation
remains unselected under that same clock; older parked scopes retain their clocks.
Evidence is retained in `mocks/portal-home-greeting-zone-20261104/evidence/`.

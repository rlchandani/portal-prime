# Companion display bitmap checkpoint — 2 November 2026

Companion1.7.7/code 19 bounds photo/icon decoding and explicitly observes preview
state in the owning composition. Generated large-image allocations:48MiB raw,
0.75MiB grid,3MiB preview,0.1875MiB icon. Current Portal thumbnails are already
bounded; normal pixel/allocation equality passed, so no ordinary-photo speedup
is claimed.651 JVM/81suites and final13 native methods/14 executions passed.
Earlier UI failures are retained; exact root cause/universal flake elimination
and physical phone/live-server/full-page acceptance remain unproved.

[Structured evidence](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-widget-gallery-20261102/evidence/companion-display-images-verification.json)
contains final metrics, artifact and limits. Phone detail:
`docs/companion-display-images-validation-2026-11-02.md` in portal-toolkit.
Both owned clones were removed with donor unchanged, Family/unrelated emulator
retained. Both Portals stay exact1.85/code79; no Portal operation or publication.
Original21:14:39–21:44:39UTC first-discovery clock includes all work/cleanup.

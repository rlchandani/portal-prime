# Weather current-hour selection

Status: implemented, locally verified and installed as **1.73 / code67**
on Family Area and Living Area from `codex/weather-forecast-hour-20261102`.

At exactly13:00, the original `now − one hour` cutoff includes12:00 and
labels its forecast temperature **Now**. The source-supported issue was
reproduced in three failing assertions out of18 Weather tests, including
midnight and location offsets.

`Weather.parseHours` now floors the reference time to the start of the
current hour in the same response timezone used for forecast parsing.
Temperature rounding, weather codes,12/24-hour labels,12-item cap, network
requests and refresh cadence are unchanged. No settings or persistence changed.

The initial full green gate passed **3,843 JVM tests /403 suites**, with
zero failures/errors/skips, plus `:app:assembleDebug` in48 seconds.
Independent review found no introduced correction defect and noted a
nonblocking offset coverage gap. The final offset regression additionally
checks local13:45 for whole-, half- and quarter-hour offsets, detecting
incorrect UTC-only flooring. The subsequent focused run passed **18/18
in7 seconds**; the strengthened assertion and private receipt retain the
original full-gate scope.

Private red/green XML, exact APK identity and bounded-unit evidence:
`/private/tmp/portal-weather-design-feasibility-20261102/`.
An initial command without the SDK environment failed before test execution;
the actual red and green runs used the installed Android SDK explicitly.

The final versioned gate passed **3,843 JVM tests /403 suites**, zero
failures/errors/skips, debug assembly, optimized assembly, R8 and vital lint
in92 seconds. Version sync and the installed signing certificate passed.
The optimized APK is25,860,686 bytes with SHA-256
`82916caf75fb238bac21f133034c4f04fdc35ed39722ea0d2a70e0d960be4ff8`.
Its signer SHA-256 is
`4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6`.

Each room received one Fleet deployment. Both helper commands exited0 and
verified the changed installed APK against the complete local bytes/hash.
Subsequent authenticated room reads reported1.73/code67, complete profiles,
unchanged settings profiles and screensaver configuration, and existing dev
mode. Family's foreground remained normal Chrome; no sign-in or playback
was performed. Living's foreground was not inspected through ADB.
Private before/after data remains outside the repository in the same unit's
`release/` directory.

This delivers the parser correction; it does not establish native
visual/performance acceptance or an observed real-time weather boundary
on the physical panel.
The separate [Weather design review](http://127.0.0.1:8891/weather-review.html)
records an automatic-location presentation pilot, its data/size limitations
and pending user approval. The manual-location state remains outside that pilot.
The stale clock-format producer concern is open, not fixed by this cutoff.

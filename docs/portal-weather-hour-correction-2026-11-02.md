# Weather current-hour selection

Status: implemented and locally verified on
`codex/weather-forecast-hour-20261102`; not deployed.

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

This is parser correctness evidence, not native visual/performance acceptance
or an installed-room update. Both rooms retain exact1.72/code66.
The separate [Weather design review](http://127.0.0.1:8891/weather-review.html)
records an automatic-location presentation pilot, its data/size limitations
and pending user approval. The manual-location state remains outside that pilot.
The stale clock-format producer concern is open, not fixed by this cutoff.

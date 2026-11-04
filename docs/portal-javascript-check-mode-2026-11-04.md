# Independent JavaScript check mode

The existing local readiness checker now supports `--javascript-only`. Frontend
changes can run both established JavaScript gates and save a private receipt
without an Android SDK, baseline APK, synchronized manifest or Android build.
This reuses the existing test selection, explicit TAP reporter, command exit
checks, process cleanup, whole-run deadline and source-stability check.

```sh
python3 scripts/portal_release_readiness.py \
  --javascript-only \
  --output app/build/javascript-checks/my-change
```

Use a new output directory. Success is `status: "checked"` with
`scope: "local-javascript-checks"`. It contains no APK or JVM result. Full APK
readiness remains a separate command with its existing mandatory baseline,
SDK, build, signing and packaged-asset gates.

| Check | Result |
|---|---|
| Frozen actual-CLI regression | Six cases: four assertion failures and two controls before implementation; all six pass afterward |
| Real JavaScript-only command | Exit 0, checked receipt in 19.21 seconds |
| Slideshow gate | 398 passed; zero failures, cancellations, skips or TODOs |
| Bundled remote gate | 753 passed; zero failures, cancellations, skips or TODOs |
| Complete Portal Python gate | 288 passed in 37.47 seconds |
| Existing APK CLI guards | Included in the complete Python gate; source reconstruction retains the old APK path and exact test selections |

Regression cases cover both suites without Android/manifest inputs, a failed
Node exit stopping the next suite, a missing required module, changes to source
during checking, preservation of existing evidence, and the mandatory baseline
in APK mode. Node/build/SDK processes in those CLI regressions are inert fixtures.
The real JavaScript run uses Node 24.13.0 and Python 3.13.7.

The actual receipt and terminal counts are recorded in
`mocks/portal-js-check-mode-20261104/evidence/javascript-receipt.json` and
`mocks/portal-js-check-mode-20261104/evidence/javascript-terminal.json`.
Raw terminal logs and the frozen source/test inputs remain private at
`/private/tmp/portal-js-check-mode-20261104-185300`.

This is local tooling verification. There is no new APK, room installation,
phone operation, household source/provider operation, authentication, push or
publication. The prior exact room delivery remains 1.173/code167 as its recorded
snapshot. Native rendering and device acceptance remain separate.

The original issue clock is **18:53:00 UTC**, target **19:08:00 UTC**, hard stop
**19:23:00 UTC** on 4 November 2026. The queue adds one verified tooling task:
172 total, 117 delivered, 45 verified, two awaiting approval, seven parked and
one queued. All 171 prior task objects, their raw suffix, Weather default and
original clocks are preserved.

Discovery before this slice yielded no fresh defensible folder/selection or
world-clock defect. The public repository's open issue collection returned no
entries. Aurora forecast wording matched an existing exhausted finding and was
excluded. No prior native/browser/screensaver scope was reopened.

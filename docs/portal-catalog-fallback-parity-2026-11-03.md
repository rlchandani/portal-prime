# Bundled app-store catalog parity

Exact optimized **Portal 1.98/code92** is installed in Family Area and Living
Area. The app's bundled catalog now matches the repository catalog: **26 apps
instead of 22**, with current Portal-Kasa and Jarvis release metadata.

The store publishes the bundled asset first on a cold load and retains it when
the hosted fetch fails. Four catalog additions had never reached that asset:
Snapcast, ISS Tracker, Portal Overlays and Portal TTS. Kasa and Jarvis also
retained old frozen APK/version-code pins after the repository catalog moved
to latest-release URLs and live version manifests. History and independent
review found no intentional reduced-catalog policy.

The existing catalog validator now rejects a missing, malformed or divergent
bundled copy. It compares canonical JSON, ignoring formatting and object-key
order while preserving JSON value types and app/category order. Independent
review caught Python's `True == 1` behavior in the initial comparison; a new
regression reproduced that gap before the correction.

The catalog workflow watches both copies, the validator, its focused tests and
the workflow itself. It runs the focused regressions before network validation.
The normal Python CI discovery also includes the new test module.

## Verification and deployment

- Initial **8 regressions reproduced 6 failures**. The type regression separately
  reproduced the review finding. Final **10 focused tests** and the complete
  **206-test Python gate** pass.
- **4,066 JVM tests in 423 suites**, debug assembly, optimized `performance`
  assembly, R8 and vital lint pass. The combined Android gate took110 seconds.
- Network validation passes for all26 repository entries. The existing portalfin
  update-metadata warning remains. The first audit failed because this local
  Python installation had no default CA bundle; rerunning with the system
  `/etc/ssl/cert.pem` passed without disabling certificate verification.
- Root catalog, bundled source and the exact candidate APK's catalog are
  byte-identical. The unchanged slideshow stylesheet matches the preceding
  accepted1.97 source.
- On Family, a separate shell `app_process` loaded each actual optimized APK,
  extracted its own bundled asset and invoked its mapped production parser.
  Old1.97 parsed22 entries and passed0/10 current-catalog conditions; new1.98
  parses26 and passes10/10. These cover count, exact catalog bytes, four missing
  apps and the two latest-release/live-version pairs. The fixture makes no
  network calls and installs no catalog apps.
- Both deployment helpers verified the complete installed APK SHA-256. Initial
  post-install `/remote/catalog` responses returned26 entries in both rooms.
  Complete screensaver, profile, source and layout responses match their
  preflight baselines. Device names and dev mode match.
- Family's full installed APK hash, original Chrome task, font and accessibility
  services match; its normal manual photo frame is visibly restored. Living's
  native appearance was not inspected.
- Owned shell fixtures and the copied private registry are removed. Primary
  registry bytes are unchanged; native fixture processes and the independent
  review worker ended. Owner photos and credentials remain private.

The strict documentation build remains red on **433 inherited warnings**.
Building the prior commit separately produces the identical warning set.
The one new receipt-link warning was corrected; the broader documentation
repair is a separate unit starting03:59:26.692799UTC, due04:29:26.692799UTC.
Catalog, Android and Python gates above pass independently.

APK SHA-256:
`9051906e3c9a2a7ee6abaa348fb4fd9e2f9eade59601577dc7cd00fe0439e638`.

The sanitized verification receipt at
`mocks/portal-widget-gallery-20261102/evidence/catalog-fallback-parity-verification.json`
records artifact/source hashes, native parser results and preservation checks.

The original discovery clock is **03:50:19–04:20:19 UTC on3 November**,
including certificate diagnosis, implementation, review, builds, rollout and
cleanup. Hosted refreshes may contain newer upstream entries; this fix matches
the repository's26-entry catalog. Third-party APK compatibility, app installs,
offline installation and a full native store-navigation audit are not claimed.
No phone control, Home Assistant change or GitHub publication occurred. The
public self-update manifest remains unchanged.

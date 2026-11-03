# Family Area: legacy slideshow night check — November 1

**Result: incomplete; issue parked at the original 30-minute limit.**
The real controller and Portal WebView exercised all eleven legacy styles with
sixteen generated photos each. Four styles passed. Seven moving styles changed
their transforms beneath the night clock despite reporting paused animations.
No production playback fix was made or deployed in this slot.

| Style | Frozen beneath clock | Photo sequencing resumes |
|---|---|---|
| Ken Burns | Fail | Pass |
| Diptych | Pass | Pass |
| Framed | Fail | Pass |
| Gallery Wall | Pass | Pass |
| Push | Fail | Pass |
| Memories | Fail | Pass |
| Panorama | Fail | Pass |
| Polaroid | Pass | Pass |
| Mosaic | Fail | Pass |
| Cinema | Fail | Pass |
| Hero Sidebar | Pass | Pass |

The probe completed **1,267 assertions/observations, with 346 failures** in
219.151 seconds. Every mode held the clock for at least six seconds, longer
than its five-second photo interval. Actual new image identities appeared
3,656–4,100 ms after reveal. Failure observations repeat over the hold/reveal;
they are not 346 distinct bugs.

The retained scenes, controller and WebView stayed alive. For example, Ken Burns
changed from scale **1.06403 to 1.15** during the covered interval; its reported
paused animation time advanced from **1,616.757 to 10,566.399 ms**. Panorama
translated from **−563.219 to −1,920 px**. Thus a paused flag alone does not prove
that the Portal provider freezes motion. The exact provider/controller cause
has not been established; these results do not identify it.

An independent review found that advancing photo URIs alone could falsely
accept a motion-resume failure. The additional sample verifier matches retained
animation IDs/targets before any new visible photo appears, checks positive
animation-time progress and restored computed play states, and rejects resets.
It distinguishes continuous motion from static scenes and intentional mosaic
pauses. Mutating the real Framed and Diptych samples to leave animation/computed
states paused correctly changes their post-check results from pass to fail.
This strengthens the evidence check; it does not make the failed native run pass.

```bash
python3 -B scripts/verify-night-legacy-motion.py \
  mocks/portal-premium-player-20261030/evidence/family-night-legacy/native-samples.json.gz
```

The expected exit status for this recorded failing run is **1**.

## Fixture corrections and scope

The first attempt used photos below the loader's 512-pixel minimum edge, so it
never exercised a legacy WebView. The second exposed recursive Android callback
registration in the isolated test context (`StackOverflowError` in
`Context.registerComponentCallbacks`). The debug fixture now delegates framework
callbacks to the real application while keeping preferences/files isolated.
Both rejected attempts and their restoration receipts remain in the evidence.
Neither is counted as a production slideshow failure.

The final check ran at **2160 × 1440** on Family Area, Android 10/API 29,
using its Portal WebView. It uses generated media and anonymous DOM identities;
it does not establish panel FPS, natural-light brightness comfort, physical
touch behavior, or live household-photo behavior. Smart Frame and native
Filmstrip are outside this legacy matrix and retain their separately recorded
results.

## Restoration and checks

The original optimized APK **dcc2898e69e5c2bb13ca70baaf83d30739702f088f8ebc99a11b1f8ae329ed32**
was restored and verified at **18:37:24.910 UTC**, **26 minutes 6.910 seconds**
after the unchanged **18:11:18 UTC** issue start. All 97 setting values, sources,
accessibility services, pin, brightness policy/value and both face caches match.
The original test APK and Immortal Dream are restored; no instrumentation or
performance collector remains active. The system-server PID is unchanged.
Living Area was not touched.

Production controller, transition and slideshow asset hashes are unchanged.
Debug build, instrumentation build and **3,765 JVM tests in 395 suites pass**,
with zero test failures, errors or skips. The final build took 1 minute 22 seconds.
The on-device matrix itself fails and is recorded as such.

[Evidence](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-night-legacy/checks.json)
includes compressed complete samples, stricter motion proof, rejected-fixture
receipts, restoration guards, unit totals, production hashes and a SHA-256 manifest.
Natural room-light/physical fade acceptance remains pending because the user
cannot test it now. Do not silently extend or restart this parked investigation.

# Family Area: reopened legacy night-motion slot — November 1

**Not fixed; no experimental runtime change deployed.** The user explicitly reopened
this issue for **23:06:11–23:36:11 UTC**. Three bounded device candidates completed.
There is insufficient time left for another full candidate/build/device/restoration
cycle, so this slot ends with the original software and photos restored.

The user's report **“Both directions fade smoothly”** accepts the requested natural
brightness/photo-clock sequence on optimized APK **872b6af7…**. It is recorded separately
from this unsuccessful legacy-motion fix. The broader performance/player goal stays
incomplete; this slot does not reopen other exhausted investigations.

| Candidate | Native covered/continuity matrix | Retained-motion verifier | Final deployment |
|---|---|---|---|
| CSS same-time seek | 11/11; zero failed observations | 5/11; six moving styles stay stuck | Rejected |
| Temporary CSS playback rate zero | 11/11; zero failed observations | 5/11; same six remain stuck | Rejected |
| Rate zero plus explicit per-owner CSS pause | 9/11; 2 failed observations out of 1,271 | 5/11; same six remain stuck | Rejected |

The final candidate completed **1,271 observations in 219.200 seconds**. Ken Burns
and Panorama also fail reveal matrix continuity. Ken Burns, Framed, Push, Memories,
Panorama and Cinema do not advance their retained animation times after reveal even
when reporting running states. Mosaic resumes; the four static styles pass their
appropriate sequencing/state checks. New image sequencing alone would have falsely
accepted the first two candidates. None is accepted as a production improvement.

Research into Web Animations pending pause commits and CSS ownership motivated
synchronous holds without taking CSS play/pause ownership. Modern Chrome and the
models passed; the actual vendor WebView did not. The failed evidence controls the
verdict. A review additionally found canceled non-CSS fades could be revived by the
prototype. A guarded prototype passes **6/6** focused model/Chrome tests, with the two
new guard tests failing without the guard. That follow-on guard was not built or
installed, and its source/test copy is archived rather than added to production.
No fourth candidate or extra capture is started.

## Restoration and checks

Original optimized APK **872b6af7354b6616320b0081f0525d8c27c0bad29759c3bcc7a02a4a5d76ae10**, original test APK and the
photo-preview Activity were restored at **2026-11-01T23:31:56.712288+00:00**, **25.76
minutes** into the unchanged slot. All **97 settings**, sources, accessibility services,
persistent pin, brightness policy/value and both face caches match. The system-server
PID is unchanged. No instrumentation, performance collector or frame listener remains;
owned device evidence was removed. Living Area was not touched.

Production scheduler source is restored to its original committed content. The local
build outputs are rebuilt from that restored source, so rejected APKs are not left at
the normal install paths. **3,765 fresh JVM tests in 395 suites** pass. The prototype's
broader JavaScript run passed **350 tests**, with one separate opt-in test skipped;
focused Chrome/model runs passed. The older source-fixture checker stays **12/14**,
matching the isolated baseline's two preexisting failures. These passes do not negate
the native failure.

[Evidence](../mocks/portal-premium-player-20261030/evidence/family-night-legacy-reopened/attempts.json)
contains all three anonymous generated-photo reports, strict motion proof, exact tested
input hashes, restoration receipt, scoped human acceptance, rejected source/test
prototypes and a SHA-256 manifest. Household media, credentials and raw backups remain
private. Compressed native samples can be rechecked without another device capture.

References: [Web Animations](https://www.w3.org/TR/web-animations-1/),
[CSS animation ownership](https://www.w3.org/TR/css-animations-2/), and
[Animation playback rate](https://developer.mozilla.org/en-US/docs/Web/API/Animation/playbackRate).

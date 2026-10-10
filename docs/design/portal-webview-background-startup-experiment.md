# Background WebView startup experiment

The first legacy slideshow WebView construction previously took about 0.8–1.2 seconds on the two Portal+ devices. This candidate requests AndroidX WebKit background-only preparation before that first construction. A hardware speed benefit remains unproven.

## Admission and lifetime

The `portalWebViewBackgroundStartup` Gradle property defaults to false. The enabled build schedules one process-owned attempt after Home draws, waits one second, and then takes a Main queue idle opportunity. Pending Home callbacks are cancelled on pause/destroy and guarded by stale-registration tokens. Actual admission rechecks that an enabled screensaver uses a legacy WebView mode; native modes do not initialize the provider.

WebKit 1.17.1 receives `setShouldRunUiThreadStartUpTasks(false)`. Its completion callback denotes only the requested background preparation, not a fully ready WebView, first photo, or presented frame. Expected synchronous/asynchronous failures terminate the attempt without retries. Executor shutdown has one owner; fatal VM errors propagate.

`GET /perf` exposes five content-free booleans under `webViewBackgroundStartup`: enabled, pending, attempted, completed, failed. Reading them does not construct a coordinator, executor, or WebView. These fields remain available without starting a frame capture.

## Verified gates

- Frozen worker implementation: 40 coordinator/config/relevance tests; full suite 1,402 tests, zero failures/errors/skips, both default-off and opt-in builds.
- Main integration retains the deployed web-remote idle-render fix and the reviewed slideshow resource-loading fix. Both debug and benchmark builds passed the same 1,402-test gate for each flag value.
- Independent static review found no verified candidate blocker. JVM scheduling tests model the opportunity; they do not exercise Android draw/idle timing or prove installed-provider behavior.
- The content-free device cohort has nine virtual-clock cases, including preparation completed before capture, deferred completion, provider failure, unfinished preparation, malformed state, and existing paired-frame/thread checks. Failures before preparation completion cannot be accepted as a prewarmed comparison.

## Hardware acceptance still required

Compare preserved APKs from the same source with the flag disabled/enabled. Verify immutable preparation state before the first captured preview request, then separate first construction from repeated warm constructions. Repeat disabled/enabled blocks to check reproducibility. Preserve Home responsiveness and process/renderer memory checks; preloading can trade memory for startup time.

Window timings and overlapping startup spans do not establish delivered FPS, physical touch latency, first-photo visibility, or leak-free operation. The photo deck remains randomized, so those runs do not constitute a matched slideshow-content speed comparison. Keep the feature default-off until hardware measurements justify adopting it.

# Repeat performance validation

These findings extend the resource-readiness validation. They do not establish whole-product performance acceptance.

## Web remote

The idle operations-poll fix retains app-list nodes and icons when the rendered operations/error state is unchanged. Changed operations, deferred input updates, first errors, recovery, manual refreshes, and completed installs still render.

The old controller reproduced six failures in twelve focused adapter tests. The current controller passed all twelve; the full plain JavaScript suite passed 111 tests. With 24 synthetic apps, three unchanged polls kept three operations reads while reducing icon requests 72 to 0, document element creations 750 to 0, element listener registrations 99 to 0, and page replacements 3 to 0.

The same count/identity result was reproduced in isolated real Chrome using generated PNGs and intercepted requests. At a 402 x 874 mobile viewport, Installed and Catalog controls passed horizontal-fit, content-fit, and native hit testing. See [synthetic browser evidence](https://github.com/starbrightlab/immortal/blob/main/scripts/tests/remote_mobile_poll_evidence.md). This is desktop Chrome with controlled timers and synthetic content, not measured Android phone FPS, INP, or touch latency. Install completion still produced two renders and 28 icon requests; incremental updates during meaningful operation changes remain an optimization opportunity.

The exact reviewed controller asset was served by both devices after installation. Static LAN response samples do not prove full page readiness or user interaction performance.

## Fixed Filmstrip profile

Both rooms completed one warmup and one 35-second playback hold with explicit 10-ms Main stack sampling. All four owned collectors stopped, Home ownership was verified, and each room's original mode, configuration, and settings schema were restored.

Timed preview worst frames were 119.06 ms in Family Area and 117.10 ms in Living Area, with command-issue components of 109.90 and 108.65 ms respectively. These components concern graphics command submission, not GPU execution. This short fixed-style profile did not reproduce the long shuffled soak's approximately 300–400 ms queued-frame tails.

The sampler observed Main in native polling for most samples, with few observed Compose families. More than 2,000 cadence slots per capture were dropped; the dynamic inclusive-symbol map saturated, while independently bounded leaf/app histograms remained available. Fixed groups summarize only reported top-24 identities. Profiling overhead, partial samples, overlapping phases, and callback-receipt timestamps prevent CPU, causality, or delivered-FPS conclusions.

## Background WebView preparation

The [default-off experiment](portal-webview-background-startup-experiment.md) passed the full 1,402-test suite and debug/benchmark builds with the flag disabled and enabled. A disabled/enabled/disabled/enabled device sequence completed 32 captures and owned cleanups. Every candidate verified background completion before its first captured preview request; all configuration/schema checks passed.

| Room | Disabled first construction, rounds A/B | Enabled first construction, rounds A/B |
|---|---|---|
| Family Area | 1,290.59 / 1,300.87 ms | 853.09 / 765.34 ms |
| Living Area | 418.73 / 220.81 ms | 149.83 / 250.49 ms |

Family improved in both rounds. Living was mixed: the second enabled construction was slower than its disabled control. The candidate still had preview frame tails up to 419.54 ms. First-stop app-process PSS varied in both directions and excludes renderer-inclusive accounting.

Keep the feature default-off. Home behavior while preparation runs, first-photo visibility, full renderer memory, sustained operation, and the source of Living's variability remain unverified. These are first observed constructions after installation, not proven pristine-provider/cold-cache measurements; the photo deck remains randomized.

## Fidelity hold

The unrestricted Filmstrip black-overlay prototype was excluded from deployment. A generated RGBA fixture reproduced wrong fully-transparent and half-alpha pixel colors; the original filter baseline passed five cases while the overlay failed three. Opaque fixtures alone do not cover all supported photo formats. Preserve the existing rendering until an optimization passes alpha fidelity and actual hardware checks.

Remaining work includes shuffled rendering stalls, browser/website readiness, physical phone performance, broader navigation/UX acceptance, and longer uptime/renderer-memory verification.

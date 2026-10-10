# Ken Burns hidden-slot retirement candidate

Base: `c53ff61a935b777731a70d0b06875ecf24e46bf4`. Local branch: `codex/ken-burns-retirement`.
This is an isolated candidate for main to integrate and evaluate. It has not been deployed.

## Implementation and write scope

Only `MODES.kenBurns` in `app/src/main/assets/screensaver/mode-impls.js` changes production behavior.
The two reusable root/fill/hero slots retain their original geometry and 2.2s CSS opacity fade.
The outgoing slot keeps both backgrounds and its live motion through every positive opacity.
At exact numeric zero, it clears both background images, removes `ss-motion`, sets hero animation
to `none` and `will-change` to `auto`. On reuse it cancels older retirement ownership, restores
the CSS compositor hint, applies the exact decoded URL and existing `kbVars`, and runs the
original `animation=none; offsetWidth; animation=''` restart before promotion.

Each outgoing generation owns its transition handler and at most one fallback task, scheduled
with existing `t.after`/`t.cancel`. The fallback checks every 2200ms until zero, reuse or disposal;
there is no hard retry limit that would orphan a late missing-event fade. The number of pending
retirement tasks is bounded by the two slots, with no unowned timer, RAF or event listener.
Handlers require their exact root and opacity property. Both paths require the same generation,
entry identity, root parent ownership, inactive root and strict numeric opacity. Blank, null,
malformed, unavailable and near-zero opacity preserve content. Nonzero significands remain
protected even when Number() would underflow (`1e-999`).

Cleanup invalidates generations, aborts shared decode/timer work and releases retirement handlers
without mutating dead scene content. The existing ready/pause/resume protocol is forwarded.
Paused timers retain remaining delay; an event may retire only an actually zero outgoing root.
Neither active content nor positive-opacity paused content is removed.

All shared helpers, other modes (including Gallery and Filmstrip), CSS, scheduler, index and
photo/config/native code are byte-identical to the base. Only these three source/test files and
new files in this evidence directory are committed. `manifest.json` lists every generated file
with SHA256, source hashes, and the validated scope.

## Authoritative evidence

- `node-red-frozen-base.tap.gz`: final 13-test suite runs actual production mode from base; 10 fail,
  3 pass, exit 1. The retained-background assertion is a meaningful failure of the original mode.
  The first observed pre-implementation RED is preserved in `node-red-base.tap.gz` (8 failed / 12).
- `node-red-underflow-candidate.tap.gz`: added underflow case fails the earlier candidate's visibility
  guard (12 pass / 1 fail) before the guard is hardened.
- `node-green-frozen.tap.gz`: final candidate, all 13 pass, exit 0. Controlled real mode/makeTimers
  execution covers exact own target/property, visible/near-zero/malformed/unavailable opacity,
  event and missing-event fallback, delayed fade past first fallback, stale event/timer at reuse
  and at a later zero generation, parent ownership, pause/resume, face/non-face reuse and restart,
  exact prepared URL, initial/inflight cancellation, superseded decode, idempotence and ghost work.
  These DOM doubles do not emulate or prove CSS rendering.
- `node-suite-frozen.tap.gz`: complete discovered local JavaScript suite, 156 tests: 155 pass,
  0 fail, 1 opt-in synthetic remote Chrome test skipped. No Gradle was run.
- `browser-red-base.json`: final browser harness negative control, base versus base. Every pixel
  and geometry check passes, while retirement fails in all formats and the harness exits 1.
- `browser-green-frozen.json`: final candidate versus base with independent baseline-repeat
  controls. Installed Chrome `155.0.8059.40`; all three cases pass, exit 0. 66 candidate frame
  comparisons and 66 independent baseline-repeat comparisons have ZERO changed pixels and ZERO
  maximum channel delta. JPEG has 16 distinct baseline frame hashes; PNG and WebP each have 19,
  confirming the generated scenes vary across phases rather than a constant empty backdrop.

The fresh headless browser is launched using the installed Chrome executable with a temporary
profile and offline contexts. Non-data requests are aborted and no such requests are observed.
Fixtures are generated JPEG plus transparent/half-alpha/opaque PNG and lossless WebP, on colored
`#204060` backdrop, with landscape/portrait and face/no-face images across three consecutive swaps.
No household data, existing browser profile, screenshots, raw fixture URLs or image bytes are saved.

The probe executes the actual unchanged CSS, decoder, modes registry and scheduler, and the actual
base/candidate Ken Burns mode. It pins real CSS animations at 5000ms and real CSS opacity transitions
at 0, 550, 1100, 1650 and 2199ms; it checks native trusted completion at own computed zero and captures
zero and after-fallback frames, plus initial rest. Three swaps cover both retired-slot reuses and
face/non-face preparation. Geometry, motion vars and current transforms match throughout visible
phases. The actual scheduler pause/resume API holds positive-opacity content, then resumes. Native
end handlers observe retained images at zero before production retirement. Both backgrounds, animation,
`ss-motion` and compositor hint retire in the candidate while active content stays intact. Scheduler
next, outgoing freeze, decoded promotion and actual disposal (including detached outgoing references
and released handlers) also execute successfully. Screenshots remain in memory only; JSON stores
raw-pixel hashes and counts. No tolerance or source geometry/quality was changed to obtain equality.

Node output is preserved byte-for-byte in deterministic `.tap.gz` archives, including whitespace-only
assertion lines. Decompression reproduces the original captured stream; `manifest.json` records each
uncompressed SHA256 and length as well as each archive SHA256. This preserves the immutable observations
while allowing the default Git whitespace check to pass. Read with `gzip -cd <file.tap.gz>`.

Other files preserve earlier intermediate runs unchanged. The `*-frozen*` files above are the
canonical proof for the final source; earlier candidate reports predate the underflow hardening.

## Reproduction

From this isolated checkout:

```sh
KEN_BURNS_MODE_REF=c53ff61 node --test scripts/tests/slideshow_ken_burns_retirement.test.mjs
node --test scripts/tests/slideshow_ken_burns_retirement.test.mjs
node --test scripts/tests/*.test.mjs app/src/test/js/*.test.mjs
NODE_PATH=~/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules node scripts/ken_burns_retirement_browser.mjs
NODE_PATH=~/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules node scripts/ken_burns_retirement_browser.mjs --red
```

RED commands intentionally exit 1. Use a new `--out=` path under this evidence directory to preserve
new browser results: existing output files are never overwritten by the harness. Original evidence
is immutable, content-addressed in the manifest, and included in the local commit. Diff whitespace
and syntax checks passed; a structural split verifies all production text outside the Ken Burns
block is unchanged. The commit uses explicit paths with hooks and signing disabled.

## Limitations and handoff

This proves guarded style/resource retirement and exact generated pixels at sampled CSS phases in
Chrome155 software rendering (`--disable-gpu`, `--disable-threaded-animation`). It does not prove
Portal WebView/GPU equivalence, continuous-time rendering between sampled phases, actual texture or
decode-cache eviction, memory savings, frame-time improvement, thermal benefit or any device benefit.
Missing-event/malformed-style conditions and decode cancellation use controlled Node boundaries;
the browser separately proves real CSS/native completion behavior. `makeTimers` can rearm an extra
repeat handle when resume overlaps an inflight step; this existing shared behavior is unchanged and
the focused test asserts candidate retirement ownership rather than an incorrect shared handle count.

Main owns integration, Gradle/broader checks and hardware A/B. No Gradle, ADB, device HTTP, GitHub,
authentication, hooks, push, deployment or descendant workers were used. Freeze the committed branch
for handoff; further device/performance conclusions belong to main's later evaluation.

# Photo zoom slider width — 4 November 2026

The photo editor's fixed 620 dp zoom slider extended beyond the available
space on narrower screens. The existing controls now cap it to the measured
width after horizontal padding, while retaining the 620 dp maximum on wide
layouts. The seven-line change preserves the zoom range, labels, callbacks,
Save/Cancel behavior and photo/settings policy.

The same frozen fixture loads the actual debug APK classes on Family API 29.
Real Android layout at 320/480 dp reproduces two clipping failures before the
change; the 800 dp control passes. All three pass afterward, including
same-width editor hide/reveal. At the measured density, the slider widths are
288, 468 and 697 pixels. Both executions retire all three controls/stages and
the fixture package, process and external directory.

All **4,513 JVM tests / 471 suites**, debug assembly, optimized assembly,
R8 and vital lint pass. Both exact signed 1.161/code155 artifacts retain the
23 source assets byte-for-byte against source and baseline. The optimized APK's
compiler-generated `assets/dexopt/baseline.prof` changes; an initial overly broad
asset equality assertion is retained as a verification setup qualification.

Both rooms now contain the exact optimized **1.161/code155** APK, SHA256
`14a9a8e80670f9d8873844435955b7cbd9a785c31e3a9f9d5f8dac860332fd96`, after one install each.
Full readbacks match; all43 Family/all30 Living preference files and four complete
configuration maps remain exact. Living's install acknowledgement was uncertain;
six read-only observations confirmed the single install without retry.
Family font/accessibility, original Chromium activity, resumed frame and awake
state are preserved. The combined independent review passed before promotion.

This is detached native layout acceptance with generated resources and
fixture-set editor visibility. It does not exercise Adjust/Save/Cancel, photos,
controller startup, mounted UI, button fit, hardware pixels, retained
width-to-width resizing, optimized runtime, API 28 or Living visuals.
AT_MOST and unconstrained root width are source-reviewed only. The initial
plan's broader resize wording is retained and explicitly qualified.

Original discovery **13:42:16 UTC**, selected **13:45:17 UTC**;
target13:57:16, hard14:12:16. No clock reset. Final independent review,
room delivery, preservation and closure are recorded in
`mocks/portal-photo-zoom-width-20261104/evidence/verification.json`.
Public1.83/code77 remains unchanged; the whole-product goal remains active.

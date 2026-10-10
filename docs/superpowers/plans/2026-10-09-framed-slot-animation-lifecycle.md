# Framed Print slot animation lifecycle

## Scope and evidence

Both rooms currently use Shuffle. Keep the mode selection, photo source, dwell time, visible
Framed Print motion and crossfade intact. The renderer retains two print slots. An inactive
slot currently retains an infinite `breathe` animation and a `will-change: transform` hint.
This is proven in the source; a GPU or delivered-FPS benefit has not yet been established.

Thirty-second Home observations produced zero or one Window frame per phase. Slideshow
cohorts vary by randomly selected style. The UI thread was predominantly in native polling;
command issuance contributed more mean frame time than layout or drawing in all four
slideshow cohorts. These observations do not identify a particular style as the p95 cause.

## Implementation and checks

1. Mark an unshown slot inactive from creation.
2. Restore motion before a loaded incoming print starts its fade.
3. Keep outgoing motion throughout the 2.4-second fade, then pause motion and release its
   promotion hint. Preserve the CSS animation timeline instead of removing its animation;
   a reused print must retain its phase and direction. A stale completion must never
   deactivate a reused visible slot.
4. Use the existing owned, pausable timer infrastructure; destruction cancels retirement.
5. Keep CSS animation play-state owned by CSS. Scheduler API suspension must apply only
   to transitions and programmatic animations; API play must not override retired/freeze rules.
   Verify deterministic load/decode, repeated reuse, pause/resume, failure and cleanup using
   source fixtures. Compare actual computed CSS in Chromium with generated photos.
6. Build and run the complete Android unit gate. Review the bounded asset diff independently.
7. Run bounded fixed-Framed Print hardware observations at a five-second interval before and
   after the change, restoring and verifying the complete original settings response each time.
   Photo bytes and visible content are never exported. Deploy the same signed APK to Family Area and Living Area. Run sustained navigation,
   pin/cover/reveal and collector cleanup checks. Keep user configuration unchanged.
8. Document mechanism-level evidence separately from uncontrolled hardware frame timings.

Preview host lifecycle suspension and expensive filter changes remain separate work, because
user pause, native video/clock faces and retained WebViews need their own coverage.

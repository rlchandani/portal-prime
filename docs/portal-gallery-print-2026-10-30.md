# Native Gallery print

Gallery print (`galleryPrint`) displays one complete photo in a warm charcoal matte.
The inset is four percent of the available short edge, capped at 56dp. Tiny viewports
retain at least one pixel of usable space. The image preserves its aspect ratio and
stays still between the existing 900ms two-layer dissolves.

The style is an additional everyday option in the declarative screensaver registry,
so the on-device picker and phone remote receive it automatically. Smart frame
remains the default. All existing native and legacy style IDs retain their routing;
saved preferences are not migrated or rewritten. Gallery print hides the motion
control without clearing its stored value.

## Rendering and resource bounds

`SlideshowStage` uses the existing `SlideshowLoader`, decoder and shared 48MiB pixel
budget. Gallery print uses one photo per scene; the outgoing photo remains opaque
until the incoming dissolve finishes. It creates no additional decoded bitmap,
blur, shadow, camera work, movement animation or recurring callback. Normal
slideshow advancement, lookahead and image ownership remain in their existing paths.
Whole-image presentation skips face analysis because no automatic crop needs it.

The pure `SlideshowPresentationPolicy` places the print around measured clock and
calendar occlusions. Candidate ranking maximizes the displayed photo size for its
aspect ratio, so a portrait can use a tall side area and a panorama a wide lower
area. Search examines at most 32 occlusions and keeps at most 16 candidates per
step. Coordinates are clipped before subtraction; area comparisons use `Long`
and fitting uses `Double`. Density is finite, positive and capped for inset sizing.

Fully covered viewports, or more than 32 occlusions, fall back to a whole inset
photo. In that fallback an overlay can cover part of the image; the renderer never
replaces the photo with an empty or negative-sized layout.

Late calendar, caption and clock layouts refresh Gallery reservations when the
measured rectangles change. The update changes existing holder bounds and image
transforms for both retained layers; it does not decode, bind another bitmap,
change pixel ownership, or start/restart a dissolve. Unchanged rectangles are a
no-op, including a different enumeration order.

## Existing controls and lifecycle

Saved per-photo zoom and focus remain deliberate overrides, rendered within the
matte without rewriting their values. A manually zoomed image can therefore crop,
as it already does in Whole photo. Manual framing bypasses automatic overlay
reservation so it retains the chosen focus. Resetting the adjustment restores
uncropped Gallery print placement.

Photo taps, pause, previous/next, pinning, framing save/cancel, host suspension,
night-clock switching and sensor observer ownership use the existing controller
and stage. Gallery alone observes global layout while its host is attached and
active. Suspension and detachment remove the layout listener; resumption or
reattachment checks retained geometry again. Stop permanently removes both layout
and attachment listeners. Active framing edits suppress geometry refreshes, and
saved manual positions continue to bypass automatic reservations.

## Validation

Pure JVM policy tests cover small and invalid dimensions, maximum integer bounds,
portrait and panorama containment, extreme aspect ratios, malformed density,
measured overlay reservation, aspect-aware placement, manual zoom/focus, overlapping
and offscreen rectangles, and oversized metadata without traversal. Configuration
and registry tests include native routing, persistence, everyday picker exposure,
schema options and control gating for `galleryPrint`.

An isolated compiler/JUnit run passed 84 tests, including 11 Gallery print policy
tests and existing geometry, framing, style persistence/routing, picker,
navigation, pixel-budget, prepared-delivery and pausable-callback checks. The real
JSON library precedes Android stubs in that harness's runtime classpath.

The parent integration ran the full Gradle build and unit-test gate: **3,392 tests
in 350 suites**, zero failures/errors/skips, and a successful debug APK assembly.
It includes the updated registry-domain schema and control-gating assertions.

The late-overlay follow-up adds `GalleryOverlayLayoutObserverTest` for suspended
hosts, attachment/tree replacement, pre-attachment activation and terminal cleanup
with late callbacks. All five tests passed in the parent integration gate.

The actual Gallery stage also rendered in the isolated debug preview on an API 34
emulator at 2160 × 1440; native assertions confirmed the new mode and distinct
matte/photo pixels, followed by visual inspection of the capture. This confirms
basic Android rendering, not physical Portal overlay lifecycle or frame times.

The integrated APK was installed on Family Area and read back with exact SHA-256
`65820d2b22bf0d7584d84b74f2a73e649a07b5f395fa25ede50bc32e814fab9d`.
No screensaver settings changed during deployment. Family retains legacy Shuffle;
Gallery is an available option, not silently selected for the household.

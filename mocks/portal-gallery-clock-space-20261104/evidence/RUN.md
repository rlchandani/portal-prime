# Owned generated Gallery Wall clock-space acceptance

Build with `./build.sh /absolute/path/to/exact-performance.apk`. Parent owns device operations. Package `com.immortal.galleryclockfixture`, Activity `.GalleryClockActivity`; manifest has zero permissions.

Three scenes: single landscape with measured hidden bottom35% clock region, single portrait with right45% clock region, mixed six with bottom25% clock and right22% calendar/panel regions. These are generated large-clock stress rectangles, not room settings or real native clocks. A generated JavaScript bridge publishes each actual DOM rectangle scaled by devicePixelRatio and the native-pixel viewport. Actual packaged SS.photoLayoutArea and Gallery Wall mode/layout consume that facade.

The fixture always evaluates all scenes and retains computed/photo/doodle pixel controls before final failure if any cell intersects an occupied rectangle by >0.01 CSS pixels in both dimensions. Baseline final error is `clock_reservation_failed`. Expected 32 colored whole-photo corner witnesses, distance<=18; quiet outer-matte doodles use original delta25/count20 gates. Prepared URLs, opaque composed backing, known requests only, compositor fence, 45-second watchdog and destruction remain.

No real native FaceRenderer/clock, SlideshowStage Gallery Print/Spread, native resize, swapping, scheduler, motion or performance acceptance claim.

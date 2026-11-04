# Polaroid keeps paper prints clear of the clock

Polaroid previously scattered its prints across the full viewport, ignoring the
clock and calendar bounds already supplied by `PhotoFrameController`. It now uses
the existing `SS.photoLayoutArea` for cell dimensions and offsets. The rotated
paper envelope, including its border, fits the available area.

The original grid, photo selection and seven reusable owners stay intact.
Resize notifications coalesce into one pausable frame. Existing photos reflow
with their stored rotations and position biases, without new image requests or
random scatter. Cleanup removes the listener. The native bridge and scheduler
are unchanged.

Nine frozen regression cases improve from **eight assertion failures and one
control** to nine passing cases. The eleven existing lifecycle cases also pass.
Coverage includes scaled native coordinates, bottom and side clocks, calendar
reservations, fallback bounds, replacement, paused resize and cleanup. Independent
paired traces retain **44 random draws**, 11 resources/loads, cursor and timers;
only intended paper/photo dimensions and positions change. The reviewed Polaroid
geometry snapshot is updated; all other mode snapshots remain.

All **4,414 JVM tests / 461 suites**, **372 slideshow checks**, **743 application
JavaScript checks**, debug/optimized builds, R8 and vital lint pass.

An unchanged native fixture embeds the exact baseline and candidate APKs. Three
generated Family/API29 WebView scenes use measured synthetic bridge rectangles:
a bottom clock, a side clock, and a clock with calendar panel. The baseline
reproduces **1 / 1 / 5 paper intersections**. The candidate has **zero** across
all 14 rectangle pairs. Both retain all **32 photo corners** and eight paper
border pixel checks. Gradient, doodle, request-admission and retirement gates are
unchanged. Independent review replays the actual screenshots and coordinates.

The first baseline collector expected the wrong failure literal. Its failure is
retained; the fixture's actual `clock_reservation_failed` report was independently
admitted without another device run. The first source candidate retained the
test's numeric-coercion object as a scatter bias; storing numeric bias values
corrected its two counter failures without changing the frozen regression.

The actual desktop preview keeps six complete prints clear of its simulated
clock at 2560×1099 and after resizing to 1200×770. This is a browser acceptance
check. Native evidence covers generated initial WebView scenes; actual
`FaceRenderer` clock geometry, Android resize, scheduler, swaps, API28, Living
visuals and hardware performance remain separate.

Both rooms contain exact optimized **1.142/code136**, 25,886,431 bytes, SHA-256
`7af09cb1e3245b773c7b0940d8841615fadab643981720d42735a095d74a7b28`.
All **43 Family/30 Living preference files** and four complete maps match.
Family font, services, original Chromium activity, resumed frame and awake state
remain. Living's uncertain acknowledgment was reconciled through six read-only
observations without another install. The independent host review corrected an
unsuffixed package expectation; the exact candidate is
`com.immortal.launcher.debug`. Fixture and acceptance gates stayed unchanged.
Cleanup and elapsed time are recorded in the structured receipt. Saved Shuffle/fit remains;
installed support does not establish which Shuffle style is currently visible.
Public release remains **1.83/code77**.

The first observation's **08:48:03–09:18:03 UTC November 4** clock includes the
time spent finishing the preceding appearance slice. It is not reset when this
placement work starts. Earlier parked scopes and clocks remain. No phone,
source-media transfer, provider/account operation, push or publication.

Portable generated fixture, images and evidence:
`mocks/portal-polaroid-clock-space-20261104/`.

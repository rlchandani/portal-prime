# Photo grid and dark margins: status and visual review

The user's renewed report on November 3, Pacific time, concerns small photos
inside visible grid cells and excessive dark space around a single photo.
**Those appearance issues were not fully resolved by the earlier deliveries.**

The November 1 Mosaic correction preserved the whole image when motion stopped.
It retained fixed rectangular tiles, a 92% inset image box, `contain`, rounded
cell edges and an explicit one-pixel outline. These still make mismatched photos
small inside visible boxes. The November 2 Framed Print correction reduced the
mat to 4px and the outer edge to 2px; it preserved the photograph's maximum
60% viewport width and 72% height. The broad dark surround therefore remains.
The earlier crop and thin-frame receipts retain their original acceptance.

A [functional local comparison](http://127.0.0.1:8892/) now offers:

- Six larger whole photos arranged in rows by their actual proportions, with
  frames following the photographs instead of empty cells.
- A larger single photo above reserved sample clock space.
- Sparse, static original SVG doodles, a slim warm edge, and Paper, Graphite or
  Sage backgrounds. Paper is the recommended direction.
- Current/Proposed switching, wide/portrait display formats, four single-photo
  shapes, next sample photos, and frame/pattern controls.

The Current view executes four **unchanged local renderer assets**, with
deterministic generated fixture selection, disabled motion and long dwell.
The proposal is a browser design study. Nine scenic fixtures derive from three
bundled fallback photographs; some are cropped to create different sample
shapes. Every resulting sample is shown in full by the proposal. This is
not a comparison using household media or the installed Portal renderer.

## Verification and remaining implementation

All 81 browser geometry cases pass whole-photo aspect, scene containment,
photo separation, clock clearance, expected count and page width. They cover
320/390/768/1440px pages, three display formats, both layouts, three themes
and additional single-photo shapes. Six unchanged-renderer scene checks and
six actual control checks pass with zero page errors. JavaScript/Python syntax
and eleven server-route checks pass. Independent source review found no
blocking mock issue; ineffective proposal controls are disabled in Current.
Generated screenshots were inspected. No Android build or native check is
claimed for this review-only change.

Source, case results and generated screenshot hashes are in
`mocks/portal-photo-layout-20261103/evidence/verification.json`.
`mocks/portal-photo-layout-20261103/README.md` explains reproduction and limits.
The server exposes only the review folder and four allowlisted renderer files.
All 17 previously protected tracked files remain unchanged.

Visual selection precedes production implementation. Native work must preserve
face protection, photo selection, bounded pixel residency, pause/retirement and
replacement behavior; use measured overlay reservations; and verify Android
9/10 rendering and Portal acceptance. Static browser SVG rendering does not
establish hardware performance. Gallery Wall's later replacement proportions
and native scrim policy remain separate source observations.

The renewed report's review clock is **01:01:58.039–01:31:58.039 UTC November 4**,
starting at the actual human message so discovery is included.
Earlier delivered and parked issue clocks were not reset. No production asset,
device configuration, phone, live photo transfer, version or publication changed.

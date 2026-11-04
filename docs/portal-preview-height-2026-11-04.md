# Photo review fits beneath wrapped controls

The local photo-review wrapper previously subtracted a fixed130px from the viewport
for its controls and explanatory note. Both sections can wrap, making their actual
height larger and pushing the photo frame below the viewport.

Three CSS changes fix the measured geometry: the body is a full dynamic-viewport
flex column, the header and note retain their natural height, and the iframe takes
the remaining space. Its existing320px minimum stays in place. All HTML, handlers,
eleven layout choices, preview state and ten production engine assets are unchanged.

| Actual browser viewport | Before document height | After document height |
| --- | ---: | ---: |
|825×1313|1324|1313|
|390×844|956|844|
|320×900|1062|900|
|320×400|612|612|

The first three measurements now place the frame bottom exactly at the viewport
bottom. The short viewport keeps reachable scrolling to preserve the320px frame.
Removing the scrollbar also changes available width, so naturally wrapped header
and note heights need not remain identical. Photo wall/index0/motionfalse and all
11 options remained exact. Temporary browser viewport overrides were reset.

These are actual local browser before/after measurements, with one visually reviewed
final825×1313 JPEG. No new unit-test file is needed for this reversible CSS change.
Independent review accepted18 material bindings, all rehashed by root. The screenshot
was initially named PNG despite containing JPEG bytes; its format assertion stopped
metadata promotion before document/queue edits. The same bytes now use the correct
`.jpg` extension. Earlier startup console attribution remains unresolved.

The conservative original21:43:08UTC start, missed21:58:08 target and22:13:08 hard
stop are retained. No expired native issue was reopened. Both rooms retain
1.179/code173; this is a local review improvement with no new app install, native
screensaver handoff, provider, motion or performance acceptance. The whole-product
goal remains active.

Evidence: `mocks/portal-preview-height-20261104/evidence/verification.json`.

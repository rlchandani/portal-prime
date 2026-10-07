# Validation record

Revision: `portal-plus-ipad-01`. Date: October 4, 2026.

## Browser checks

- JavaScript syntax checks passed for `app.js`, `review.js`, and `tokens.js`.
- 62 combinations passed: 22 screens in both themes, eight settings categories in both themes, plus offline and first-use Home.
- Every checked device canvas measured 2160 × 1440 at the reference viewport. No horizontal pane overflow, duplicate element IDs, missing reference images, unnamed icon buttons, or JavaScript errors were found. Home favorites stayed above the dock.
- Nineteen interaction assertions passed for exact-row search, theme, source validation, credential visibility, pending completion/cancellation, multiple albums, widget order/addition, favorites, timer controls, store state, offline recovery, directional focus, and focus restoration.
- Six later checks passed for five widgets without Photos, the Home time toggle, Tokyo's next-day time zone, reviewer navigation metadata, reviewer theme, and the evidence panel.
- Comfortable/Compact selection was verified after correcting its button markup; the revised Customize reference was recaptured.
- Review, gallery, and contract pages passed at 1440px and 640px widths without page-level horizontal overflow. Contract anchors and reviewer controls worked.
- All local HTML asset/document links resolved; all 42 PNG hashes and included source/asset hashes matched the manifest. A final ADB check still listed the Portal+ as connected.
- 42 reference images were exported at 2160 × 1440 with fonts loaded. Screens show initial viewports; eight additional images record interaction states. The manifest records reproduction steps where required.
- Two overview contact sheets were visually inspected. The density chooser styling issue found there was corrected. Primary Home and Settings references received full-size inspection.

Sample contrast ratios on opaque surfaces:

| Pair | Ratio |
| --- | --- |
| Light primary text / white | 14.40:1 |
| Light secondary text / white | 5.96:1 |
| Light accent / white | 5.50:1 |
| Dark primary text / dark card | 11.45:1 |
| Dark secondary text / dark card | 7.22:1 |
| Home secondary text / home card | 7.43:1 |

These samples do not certify all controls, disabled states, photo overlays, or accessibility. Image-dependent contrast, TalkBack, Android focus and system font scaling require native verification.

## Device and repository

ADB read-only inspection confirmed the connected Portal+, Android 10/API 29, 2160 × 1440 display and 180 dpi. Font files were read from the device. No APK install, setting change, source edit, app restart, or native branch change was made for this mock.

The earlier 408-test baseline belongs to the existing Android code. Gradle was not rerun for standalone HTML/CSS/JavaScript artifacts; browser checks validate this deliverable, not native behavior.

## Remaining acceptance

Native appearance, service integration, input/keyboard insets, remote navigation, rotation, scaled text, performance, settings/widget migration, and extended screensaver operation are pending implementation after owner approval. The approved native delivery must be compared to these browser references; references alone do not establish fidelity.

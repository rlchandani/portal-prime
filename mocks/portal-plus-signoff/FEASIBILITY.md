# Can this be reproduced on the Portal+?

**The illustrated Immortal surfaces are feasible with the current Android/Compose stack. Exact installed appearance remains unproven until implementation and device comparison.** The mock deliberately avoids visual effects requiring newer firmware.

## Evidence, confidence, and remaining work

| Area | Evidence | Assessment |
| --- | --- | --- |
| Geometry | ADB display and density; fixed dp canvas | Directly implementable with Compose constraints; verify insets and full-screen bounds |
| Typography | Portal system TTFs, three weights, Apache 2.0 license | Same assets can be bundled offline; Skia/browser antialiasing and baseline require comparison |
| Cards, rows and dialogs | Existing Compose/Material3 and centralized settings components | Standard fills, strokes, clipping, layout and scrims; custom controls avoid Material default styling drift |
| Gradients | Existing tint/border glass and ambient backgrounds | Static gradients work on API 29; match endpoints and color stops explicitly |
| Photo crops | Project fallback photos shared by both references and app | `ContentScale.Crop`, center alignment; same image decoding/orientation |
| Icons | Included SVG path library | Paths can become Compose `ImageVector` or vector resources; app identity needs actual installed icons |
| Settings | Ten-domain registry; renderer and `NavSpec` architecture | Restyle shared rows and group presentation; retain apply/validation/side-effect boundary |
| Home | Existing widgets, launcher, layout storage | New fixed composition, dock, favorite model and safe migration are substantive work |
| Photo setup | Existing local/Immich/SMB/WebDAV/album integrations | Forms can match; native keyboard, real failures and atomic source switching need verification |
| Store | Existing catalog and install daemon | Match Immortal surfaces; system installer and third-party apps remain external |
| HA / music / remote | Existing REST, media and remote services | Reuse real service logic; do not treat browser sample status as connectivity evidence |
| Performance and accessibility | Browser layout/focus checks only | Native measurement, TalkBack, font scaling and extended photo operation still required |

## Why the assets matter

The current Inter font XML resources request Google's downloadable-font provider. Portal has no Google services. This revision replaces that uncertainty in the design with the actual Portal Roboto files; it is an intentional change from the earlier research's Inter suggestion.

Roboto asset SHA-256:

- Regular: `797e35f7f5d6020a5c6ea13b42ecd668bcfb3bbc4baa0e74773527e5b6cb3174`
- Medium: `7984aafeaf437899ce89b251d2875fc5048cb7d290152eac070239cca7ff726c`
- Bold: `36f3709dea3e3ce3c6aedc058079e55980825f898f1e901d091c73c40de8bab1`

See `assets/ROBOTO-LICENSE.txt` and `assets/ROBOTO-NOTICE.txt`. The copy was read-only and did not modify device fonts. Photos are bundled project fallback landscapes, not captured private photos.

## Translation details that can cause visible drift

- Android dp and sp are independent of physical pixels. The 1.125 scale is valid at the inspected density; typography also depends on runtime font scale.
- Disable additional font padding and set line height, weight and letter spacing explicitly. Do not approximate with default Material typography.
- CSS `box-sizing: border-box` includes borders in dimensions; Compose border placement and padding need matching.
- CSS `object-fit: cover` uses a centered crop. Do not switch to fit-inside and introduce letterboxing.
- CSS diagonal gradients have angle-specific endpoints. For a 135° gradient on a 1920 × 1280 surface, endpoints are approximately (160, -160) and (1760, 1440), rather than the default corner-to-corner vector. Use each actual component's dimensions.
- Custom switches, sliders, segments and focus indicators must use the reference measurements rather than platform defaults.
- App bounds, hidden status/navigation bars, keyboard insets and Activities must preserve the intended canvas. A full-screen browser screenshot does not prove native inset behavior.

## Constraints and safeguards

Keep screensaver timing, presence detection, anti-burn-in behavior, and overnight policy. The static photo reference is not a specification to disable those systems.

Do not replace the registry with direct SharedPreferences writes, add in-file sub-screen toggles, discard extra configured widgets, drop Flip clocks, or change installation/signing machinery as a shortcut.

First-generation Portal+ installation quirks and no-GMS firmware remain hardware facts. The selected connected device is API 29; this review does not guarantee identical behavior on API 28 or a different Portal.

## Native fidelity commitment

After implementation, capture the installed UI on this Portal+ and compare the approved screenshots using the same fonts, images and fixtures. Overlay/diff comparisons should distinguish layout or color errors from glyph-edge raster differences. Visible deviations must be corrected; necessary design changes require reapproval.

This is an engineering commitment to reproduce the reviewed design, not a claim of existing pixel proof. Geometry and asset feasibility are well supported; native scrolling, input, accessibility, and integration reliability still need hardware evidence.

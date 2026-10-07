# Portal+ shared-color contrast audit

The teal palette below records the earlier design. The October 7
[Meta charcoal update](portal-plus-meta-colors.md) supersedes the dark/Home
colors and includes fresh contrast and rendered device evidence.

Source checkpoint: `0017d61`, checked **2026-10-05**. This is an opaque-token
calculation, not a native screenshot, font-scale, assistive-reader or G9 pass.
No application colors were changed.

The calculation reads `ui/theme/ApprovedPalette.kt` and `ApprovedHomeUi.kt`
under `app/src/main/java/com/immortal/launcher/`. It resolves the light/dark
constructor arguments and Home's named palette overrides, checks that every
input is opaque, linearizes sRGB, and computes `(lighter + .05)/(darker + .05)`.
Ratios below are rounded only for display; screening uses unrounded values.

[Android's guidance](https://developer.android.com/guide/topics/ui/accessibility/apps)
uses 4.5:1 for small text and 3:1 for larger text (18 sp normal / 14 sp bold
cutoff). The table exposes both thresholds. A token combination is **not proof
that this exact pair is rendered**. The actual size, opacity, background, focus
state and compositing must be reconciled before applying a verdict.

## Findings and source reconciliation

- Of 59 screened combinations, nine are below 4.5:1 and one is below 3:1.
  Primary/secondary text combinations clear 4.5:1 in all three palettes.
- Light accent on selected is 4.370:1. The shared selected/action rows use
  larger text; this is a restriction on future small-label use, not a proven
  native failure. Check the actual row type and text scale.
- Error text has several large-text-only combinations. `ApprovedEditorsActions`
  uses 23 sp error text, `ApprovedSourcesStatus` uses its own declared size,
  and destructive `ApprovedEditorsButton` uses 24 sp Medium over `surface2`.
  These must be checked by call site rather than applying a blanket 4.5 rule.
- Home error on selected is 2.988:1. The inspected shared destructive button
  uses `surface2`, not selected, and its focus treatment is a border. No actual
  error-on-selected shared control was established; treat this combination as
  unavailable for new large-text/icon use until its contrast is corrected.
- The primary-label rows cover `ApprovedEditorsButton`'s direct palette
  mapping (dark: background on accent; light: surface on accent).
  `PrimaryPillButton` and `ApprovedAppsButton` also read Material onPrimary;
  Home overrides LocalApprovedPalette without replacing that inherited
  Material scheme. Source tracing of HomeActivity → PortalPrimeTheme confirms
  inherited `onPrimary = #14262D` against Home accent `#B4DCD0`: **10.476:1**,
  clearing both thresholds for enabled primary labels. This is an additional
  consumer-binding check, outside the 59 standard combinations. Disabled alpha
  and actual native rendering are still excluded.

## Measurements

Normal = ratio >= 4.5. Large = ratio >= 3.0. “No” is a mathematical
screening result for that pair, not an asserted UI failure.

| Palette | Foreground | Background | Ratio | Normal | Large |
|---|---|---|---:|---|---|
| light | text `#182C39` | background `#F2F3F7` | 12.988 | Yes | Yes |
| light | text `#182C39` | surface `#FFFFFF` | 14.401 | Yes | Yes |
| light | text `#182C39` | surface2 `#E5EBF2` | 11.999 | Yes | Yes |
| light | text `#182C39` | selected `#D7E7F8` | 11.436 | Yes | Yes |
| light | text `#182C39` | sidebar `#E9EDF3` | 12.257 | Yes | Yes |
| light | secondary `#536674` | background `#F2F3F7` | 5.380 | Yes | Yes |
| light | secondary `#536674` | surface `#FFFFFF` | 5.965 | Yes | Yes |
| light | secondary `#536674` | surface2 `#E5EBF2` | 4.970 | Yes | Yes |
| light | secondary `#536674` | selected `#D7E7F8` | 4.737 | Yes | Yes |
| light | secondary `#536674` | sidebar `#E9EDF3` | 5.077 | Yes | Yes |
| light | accent `#176ABB` | background `#F2F3F7` | 4.963 | Yes | Yes |
| light | accent `#176ABB` | surface `#FFFFFF` | 5.504 | Yes | Yes |
| light | accent `#176ABB` | surface2 `#E5EBF2` | 4.586 | Yes | Yes |
| light | accent `#176ABB` | selected `#D7E7F8` | 4.370 | No | Yes |
| light | accent `#176ABB` | sidebar `#E9EDF3` | 4.684 | Yes | Yes |
| light | error `#D33D4C` | background `#F2F3F7` | 4.170 | No | Yes |
| light | error `#D33D4C` | surface `#FFFFFF` | 4.624 | Yes | Yes |
| light | error `#D33D4C` | surface2 `#E5EBF2` | 3.853 | No | Yes |
| light | error `#D33D4C` | selected `#D7E7F8` | 3.672 | No | Yes |
| light | error `#D33D4C` | sidebar `#E9EDF3` | 3.935 | No | Yes |
| light | primary-label (surface) `#FFFFFF` | accent `#176ABB` | 5.504 | Yes | Yes |
| dark | text `#F4F7F9` | background `#14262D` | 14.513 | Yes | Yes |
| dark | text `#F4F7F9` | surface `#213840` | 11.452 | Yes | Yes |
| dark | text `#F4F7F9` | surface2 `#2B4650` | 9.319 | Yes | Yes |
| dark | text `#F4F7F9` | selected `#34556F` | 7.297 | Yes | Yes |
| dark | text `#F4F7F9` | sidebar `#162B34` | 13.657 | Yes | Yes |
| dark | secondary `#B8C9D0` | background `#14262D` | 9.148 | Yes | Yes |
| dark | secondary `#B8C9D0` | surface `#213840` | 7.219 | Yes | Yes |
| dark | secondary `#B8C9D0` | surface2 `#2B4650` | 5.874 | Yes | Yes |
| dark | secondary `#B8C9D0` | selected `#34556F` | 4.600 | Yes | Yes |
| dark | secondary `#B8C9D0` | sidebar `#162B34` | 8.608 | Yes | Yes |
| dark | accent `#9ACAFF` | background `#14262D` | 9.121 | Yes | Yes |
| dark | accent `#9ACAFF` | surface `#213840` | 7.197 | Yes | Yes |
| dark | accent `#9ACAFF` | surface2 `#2B4650` | 5.856 | Yes | Yes |
| dark | accent `#9ACAFF` | selected `#34556F` | 4.586 | Yes | Yes |
| dark | accent `#9ACAFF` | sidebar `#162B34` | 8.582 | Yes | Yes |
| dark | error `#FF747D` | background `#14262D` | 5.983 | Yes | Yes |
| dark | error `#FF747D` | surface `#213840` | 4.721 | Yes | Yes |
| dark | error `#FF747D` | surface2 `#2B4650` | 3.841 | No | Yes |
| dark | error `#FF747D` | selected `#34556F` | 3.008 | No | Yes |
| dark | error `#FF747D` | sidebar `#162B34` | 5.630 | Yes | Yes |
| dark | primary-label (background) `#14262D` | accent `#9ACAFF` | 9.121 | Yes | Yes |
| home | text `#F4F7F9` | background `#14292E` | 14.079 | Yes | Yes |
| home | text `#F4F7F9` | surface `#223B42` | 11.013 | Yes | Yes |
| home | text `#F4F7F9` | surface2 `#2E4851` | 9.028 | Yes | Yes |
| home | text `#F4F7F9` | selected `#35594F` | 7.247 | Yes | Yes |
| home | secondary `#BED0D5` | background `#14292E` | 9.501 | Yes | Yes |
| home | secondary `#BED0D5` | surface `#223B42` | 7.433 | Yes | Yes |
| home | secondary `#BED0D5` | surface2 `#2E4851` | 6.093 | Yes | Yes |
| home | secondary `#BED0D5` | selected `#35594F` | 4.891 | Yes | Yes |
| home | accent `#B4DCD0` | background `#14292E` | 10.162 | Yes | Yes |
| home | accent `#B4DCD0` | surface `#223B42` | 7.950 | Yes | Yes |
| home | accent `#B4DCD0` | surface2 `#2E4851` | 6.517 | Yes | Yes |
| home | accent `#B4DCD0` | selected `#35594F` | 5.231 | Yes | Yes |
| home | error `#FF747D` | background `#14292E` | 5.804 | Yes | Yes |
| home | error `#FF747D` | surface `#223B42` | 4.540 | Yes | Yes |
| home | error `#FF747D` | surface2 `#2E4851` | 3.722 | No | Yes |
| home | error `#FF747D` | selected `#35594F` | 2.988 | No | No |
| home | primary-label (background) `#14292E` | accent `#B4DCD0` | 10.162 | Yes | Yes |

## Excluded evidence and required follow-up

This calculation excludes photos/wallpapers, translucent scrims, disabled
alpha, Night Shift/tint overlays, image-backed labels, third-party/provider styling, gradients, switch
thumb/shadow boundaries, focus/selection boundaries and font rendering.
It does not verify native color binding, clipped text, real pixel contrast,
large-font layouts or touch/focus reachability. Dynamic backgrounds require
rendered worst-case samples, not an average source-image color.

The native switch uses a white thumb plus a 2 dp shadow and an On/Off semantic
state. Thumb/track token contrast alone cannot verify the rendered shadow
boundary or non-color state recognition; capture both switch states and focus
in each theme. Do not automatically recolor the approved switch from this
text-color screen.

Keep [G9 and G11](portal-plus-quality-gates.md) pending until each applicable
rendered control/state is reconciled with its real color pair and typography.
This source audit adds evidence; it does not transfer a token result to every
screen or relax the acceptance targets.

## Source fingerprints

- `app/src/main/java/com/immortal/launcher/ui/theme/ApprovedPalette.kt`: `be5fec09952b0a0ec244c929a0b6425be3f6cb6cd9ee44ab92b265e92a084f84`
- `app/src/main/java/com/immortal/launcher/ApprovedHomeUi.kt`: `6ae82034d953c7ca2039e64a79c9c7d2e5391ec2f18f63a1f6b559e57b26a0a5`
- `app/src/main/java/com/immortal/launcher/ui/theme/Theme.kt`: `597a8596af0911835d087033a26af78dbb0666fb4dedf70210992a09c5440348`
- `app/src/main/java/com/immortal/launcher/HomeActivity.kt`: `125245eecfb5fae48b1f3b26bb2b63268a37a3e89bd137f19e1d6d63ed0b1f01`
- `app/src/main/java/com/immortal/launcher/ApprovedAppsChrome.kt`: `71d08c0637f968d419c577397238aec96ef0cc73c434de247d96b6bcb1753c19`

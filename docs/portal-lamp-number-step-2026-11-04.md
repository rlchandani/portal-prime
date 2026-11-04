# Lamp numeric step overflow — 4 November 2026

In the Lamp brightness/warmth dialog, entering `2147483647` and pressing `+`
wrapped the draft to the minimum value. Entering `-2147483648` and pressing
minus wrapped it to100. Both callbacks performed `Int` arithmetic before
clamping. They now reuse the existing `stepSettingNumber` helper, which adds
using `Long` before applying the existing bounds.

Only those two callback expressions change. Draft parsing, empty/invalid fallback,
one draft/error publication, validation, Save/Cancel, ephemeral state and window
brightness ownership remain unchanged. No new setting or UI is introduced.

The frozen twelve JVM cases invoke the actual compiled callbacks: four overflow
failures/eight controls before the change become twelve passes. The same final
native fixture invokes the actual packaged debug callbacks on Family API29,
with generated mutable-state proxies and literal endpoint expectations.
It has twelve cases and the same four failure/eight control baseline.

The initial native attempt ran zero cases because its JVM-private access guard
did not match D8. Actual APK inspection shows both callbacks as package-visible
`static final` (`0x18`). The final adapter requires exactly those flags while
retaining names, parameter types, Unit return, origin and every behavioral
assertion. The excluded attempt, final sources and reports remain recorded.
The final fixture reuses one frozen DEX before and after.

All **4,525 JVM tests / 472 suites**, debug/optimized assembly, R8 and vital
lint pass. All23 source assets match source and baseline. Both rooms contain the
exact optimized **1.162/code156** APK, SHA256
`2c43d9e7a2fa0f76a82a4796317fbe2279747d62467ee0b64b2982c131d283c3`,
after one install each. Full readbacks match; preference/configuration and Family
runtime preservation are recorded in the receipt. All43 Family /30 Living preference
files are byte-identical; four complete configuration maps per room match. Family
font/accessibility, original Chromium activity, resumed frame and awake state match;
scoped diagnostics show zero fatal/ANR markers. Combined independent review passed
before promotion. Living returned an uncertain acknowledgement; six read-only
observations confirmed the exact full APK and service readiness without reinstall.

Acceptance covers direct draft callbacks, error clearing and state publication
with generated state. It does not exercise mounted Compose, keyboard/touch,
Save/Cancel, physical lamp/window brightness, optimized runtime, API28 or Living
visuals. The callback receives no Context; this is source containment rather
than comprehensive classloader IO instrumentation. The owner Activity/package,
process and external directory are retired after each run.

Original discovery **13:54:32 UTC**, selected **13:57:09 UTC**;
target14:09:32, hard14:24:32. Setup corrections remain inside the original clock.
Final build/test, artifact, combined independent review, room delivery and
preservation evidence is in
`mocks/portal-lamp-number-step-20261104/evidence/verification.json`.
Public1.83/code77 and all prior parked clocks remain; the whole goal stays active.

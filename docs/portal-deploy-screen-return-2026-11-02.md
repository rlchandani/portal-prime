# Explicit screen return after fleet replacement

The host helper now supports `deploy --after-install leave|home|screensaver`.
Default `leave` preserves the previous behavior. An explicitly requested return
uses the existing fleet token and `/remote/key` only after the installed APK has
been verified. This is a local host-tool delivery; Portal **1.87/code81** is
unchanged, and no APK version bump, external publication or Living native QA was
needed.

## Grounded diagnosis

Family's old baseline, the original post-install capture and a fresh unmodified
helper replacement all contain **the same** Home chooser activity record.
Current Android preferred-activity XML is empty, and MAIN/HOME resolves to
ResolverActivity. The replacement uncovered a chooser already underneath the
frame; creation by the update is disproved. The origin of the missing default
is unverified. No choice was selected and no default preference was assigned.

The app-source audit found no unconditional HOME launch during replacement.
`MY_PACKAGE_REPLACED` rearms scenes; the fleet installation success paths do not
launch HOME. MQTT requires a delivered command, and BootReceiver handles actual
boot. Their occurrence was not established as the cause.

## Implementation and checks

The helper requires a complete NUL-terminated process name matching the target
package before installing with a return option. A foreign APK mapped for icon
resources cannot establish ownership. Dispatch is one request, bounded to
**10 seconds total** and **64 KiB**; a retained-socket watchdog interrupts slowly
arriving headers or chunk framing. Literal HTTP/JSON dispatch contracts are
required. Empty, malformed, wrong, truthy, incomplete or uncertain responses
cannot report success.

If screen return is unconfirmed, the verified installation verdict remains
visible, the helper does not retry navigation, other rooms continue and exit
status is nonzero. Acknowledgment is labeled a request, not visual proof.

- Initial red: 52 methods ran with 41 failure records; missing implementation and
  unsupported ordering were exposed.
- Independent review caught slow-framing deadlines and foreign process identity;
  follow-up review caught an unterminated prefix. The latter reproduced one
  failing subtest before its fix.
- Final full room-helper gate: **60/60 methods**, **23 added**, zero failures or
  errors, **10.096 seconds**. The real TCP peer dripped more than 20 headers at
  250 ms intervals; the call failed within the 9–11.5 second scheduling allowance,
  with one POST and fixture-thread cleanup.
- Final bounded static review found no remaining blocker. The two workers were
  closed. No Android source changed, so the previous 1.87 APK gates were not
  redundantly rerun or represented as new results.

## Actual Family acceptance

The old helper replaced the exact same 1.87 APK, verified installed bytes, and
left the preexisting chooser visible. The intermediate new helper visibly
returned to both HomeActivity and PhotoFramePreviewActivity after replacement.
The final terminated-identity helper then replaced 1.87 and returned to the
normal manual photo-frame host with exit zero in **15,105.550 ms**, including
installation and installed-APK verification.

Complete profile, layout, photo sources and screensaver settings match baseline.
Font scale, enabled accessibility services, preferred Home XML and Home resolution
are unchanged. The final full installed APK readback matches:

```
25,861,122 bytes
SHA-256 67d0bd131046b2bba6f7b264f64d316cc60584fb26e0999f7fc2c93045c7f502
```

Owner screenshots and configuration remain private. The generated fleet registry
is removed during closure; the original shared connections are preserved.

## Scope and limits

This is an **explicit fleet-deployment mitigation**, not automatic destination
detection, an Android default-launcher repair or automatic OTA recovery.
Intermediate flicker, pinned-app/music/other-task continuity, origin of the absent
default and general latency/FPS improvements remain unproved. Home was visually
verified before the later identity-guard-only refinement; final guarded screensaver
replacement and unchanged configuration were separately checked.

Living retains the exact 1.87 APK from the previous release; this host-only unit
does not claim a new runtime deployment or duplicate its functional QA. Weather's
approved scope remains distinct from its parked native geometry acceptance.
The original **22:26:30.941464–22:56:30.941464 UTC** clock is retained, including
cleanup. The whole-product goal remains active.

[Sanitized structured receipt](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-widget-gallery-20261102/evidence/deploy-screen-return-verification.json).

# Approved mock → connected Portal+ implementation

No Android implementation is authorized by the creation of this review.
The user's design approval is the boundary for beginning native changes.

1. Record the approved revision and any requested exceptions using the gallery
   reference IDs. Preserve the mock, PNGs and SHA-256 manifest.
2. Inspect Git status and current branch. Create a new `codex/` branch before
   editing Android files. Preserve existing user changes and earlier branches.
   Do not reset, discard, overwrite or clean the current work.
3. Port the fixed 1920 × 1280 dp shell, eight categories and visual tokens.
   Reuse the included fonts, vector paths, gradients, photos and grain asset.
   Use solid fills; no browser backdrop blur or iOS dependency is involved.
4. Keep the declarative registry as the single source of settings. Bind custom
   fields to their existing Config objects through the appropriate specs.
   Constraints, visibility and side effects stay in the domain. Keep rich
   editors as Activities reached through NavSpecs. Do not introduce a second
   preference store or boolean sub-screen navigation.
5. Follow `coverage.json` for all 49 Activities and 74 custom setter references.
   Preserve automatic/legacy values and external interface handoffs. Do not
   expose Portal battery controls on this batteryless Portal+.
6. Preserve all nine configured widget families and their stable identities.
   Apply size and order changes to their existing model; paging is a reviewed
   presentation choice. Preserve app folders, favorites, recent-app actions,
   five built-in app tiles and all installed app entries.
   For Home, use a bounded vertical layout: header, weighted content area,
   then navigation with a 24 dp gap and 32 dp bottom inset. Favorites are
   88 dp horizontal tiles with 56 dp icons and 22 sp labels at the target font
   scale. The normal widget grid fills the remaining content space; custom
   widget sizing scrolls inside that space. Do not overlay navigation on the
   favorites or rely on bottom padding around an unbounded fixed-height grid.
7. Preserve the Portal+ install daemon, signature requirements and no-GMS
   constraints. Radio remains an optional installation. Music remains the
   existing YouTube Music web interface. Installed app internals and firmware
   settings are not part of the redesigned Immortal interface.
8. Use actual native source, installation, permission and connection results
   instead of fixture success. Cancelled asynchronous operations must not
   change the selected source or produce a late completion.
9. Run the required debug build and relevant unit-test gate. Obtain installation
   authorization if it was not included in the implementation instruction.
   Never change signing keys or replace a provisioned release unintentionally.
10. Capture every approved destination, popup pattern and state on the connected
    Portal+ with matched content, theme, font scale and resolution. Compare
    against the approved PNGs. Fix differences in typography, spacing,
    dimensions, wrapping, colors, geometry and navigation before claiming parity.

Live content and animations require a controlled fixture/frame for comparison;
random photos, network values and moving transitions cannot be compared as
uncontrolled screenshots. The browser's static reference is the intended visual
target, not evidence that Android already produces it.

Any newly discovered native-only setting or dialog must be mapped and reviewed
before completing the implementation. Do not silently remove a field, replace
an existing behavior or invent an unsupported device capability.

## Revision 03 conditional parity requirements

Read `state-audit.json` and `device-walkthrough.json` before implementing settings.
The registry alone does not describe every native editor. Preserve these verified
exceptions: Sunrise editors stay visible while disabled; standalone Sleep keeps
its pause-audio/close-app switches visible; the frame master hides its entire
settings body; Chime's five preview actions follow the three-parent truth table.
The Home Assistant dismissal path is a real field, not just a target choice.

Use the source URL validators, including calendar `webcal://` and the actual
shared-album providers. Link editors save immediately; SMB/WebDAV test image
results and retain their count/Done state; Immich supports an empty album list
with Whole library. Home Assistant search uses names and entity IDs, forces
matching groups open, and lifts the twelve-row browsing cap.

Retain normal Android/Portal and installed-app ownership at each handoff.
Do not create replacement permissions, uninstall, login, or calling dialogs
from the review annotations. Preserve existing credentials without copying them
into design fixtures. Compare the implemented native screens against the
approved revision 03 references on the connected Portal+ before declaring
visual parity. A passing browser suite is not a passing native implementation.

Preserve source validation and fallbacks: camera names default to `Camera`,
crypto symbols default to uppercase coin IDs, and camera forms accept literal
`rtsp://` addresses longer than eight characters. Alarm minute steppers wrap
by five; options default to a 0.20–0.30 delta band. Keep each native collection's
IDs and normalization rules; browser sample labels are not production identity
keys.

Intentional UX changes include confirmation before removal, explicit forms and
pickers in the new shell, search prerequisite guidance, and cancellation that
cannot apply a late network result. Implement these reviewed interactions while
preserving supported fields, stored values and existing service behavior.
Screen-off review controls are browser annotations; native Sleep turns the
display off through the existing permission. No lamp sleep-timer feature is
part of this mock.

The installed Speed test tile remained on its Tools grid during two USB
walkthrough checks. Verify its opening and progress/retry pane on the approved
native build; do not treat the source-mapped browser pane as hardware proof.

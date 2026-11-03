# Phone remote keyboard confirmation

The phone candidate **1.7.5-music-preview / code17** now keeps drafts when a
text POST lacks a valid typed application acknowledgement. Five original JVM
failures reproduced the problem. All 7 focused tests and **609 JVM tests /78
suites**, app/test assembly and existing signing checks passed.

Three rendered methods passed on an owned Android34 emulator at **360×800dp**,
with actual system fonts100% (**7.090s**) and130% (**9.377s**). The real panel,
controller, repository and client use memory-only replies. Missing/string/
malformed results retain the draft and existing warning; literal success clears
the draft/keyboard semantics; explicit rejection preserves its error. Nine
reply variants per font retain exactly one POST per Send and no automatic
resend after refresh. The test now rejects a mismatched system font before
rendering; parent-only `LocalDensity` is insufficient for the sheet's Android
window. A deliberate mismatch verified that guard.

Independent current source review found no remaining concrete production
contract, cancellation or retry issue. The initial styled-text assertion
failure and runner-selection error remain recorded; final acceptance uses
exact draft characters, original controller/request checks and the configured
`AwakeTestRunner`.

The complete installed application APK matches:
`dfc26b71f020e31936aac4d3ed7d2fb6f976e762f0eabc43c723a9a504cf84e4`,
**14,722,589 bytes**, with the existing signing certificate. Original AVD
fingerprints/metadata are unchanged, cloned resources are removed, and Family's
original transports plus the unrelated emulator remain. No phone was found
over ADB or mDNS; physical installation and acceptance remain open.

- [Structured verification](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-widget-gallery-20261102/evidence/companion-text-confirmation-verification.json).
- [Actual native unconfirmed state](https://raw.githubusercontent.com/starbrightlab/immortal/main/mocks/portal-widget-gallery-20261102/evidence/companion-text-unconfirmed-100.png).

This is a phone client correction. No Portal API, UI, APK, configuration or
input changed; both rooms retain the Calendar release 1.83. No GitHub or Home
Assistant action occurred. Only system 100% has a published settled unconfirmed image; system 130% has
resource, state, exact characters and reachable-control checks. Its opening
animation snapshot is excluded. Native success is proven through state, semantics and
reopened draft; its transient screenshot is not settled-success pixel evidence.
Real Wi-Fi text entry, physical Pixel/foldable behavior, whole-app snackbar and
universal performance remain unverified. Original issue clock:
**19:45:25–20:15:25 UTC**, including cleanup.

The browser's existing `sendText` delegates to `mutate`, which rejects literal
false `applied`/`dispatched` but does not require a typed positive application
result before announcing success. Its draft is retained independently.
That is a separate source finding, queued for a fresh reproduction before
any browser correction; this phone receipt does not claim browser parity.

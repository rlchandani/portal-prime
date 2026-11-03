# Home photo diagonal EXIF correction

Local source commit `60b1fc00` corrects the active Photo Memory reader's
EXIF transpose (5) and transverse (7) mappings. Only their rotation signs
change. The full JVM gate passes **4,358 unique tests / 453 suites**, with zero
failures, errors or skips; the debug APK builds successfully. Physical Home
photo rendering and deployment remain unverified.

## Source contract

`HomeActivity` dispatches Photo Memory to `ApprovedHomePhoto`, whose private
reader applies EXIF to local, cached, remote-byte and fallback streams. Its
comment explicitly includes mirrored camera images.

The [Android Matrix reference](https://developer.android.com/reference/android/graphics/Matrix)
defines `postRotate` as `M'=R*M` and `preScale` as `M'=M*S`. Starting from
identity, a positive quarter-turn followed by a horizontal pre-scale produces
`(0,-1;-1,0)`, the transverse mapping. A negative quarter-turn produces
`(0,1;1,0)`, transpose. Home previously assigned these in reverse. The corrected
signs match the existing shared slideshow transform table and its distinct
literal corner expectations.

Every other Home source byte is unchanged, including the other orientation
branches, sampling, stream cleanup, bitmap creation/recycling, source choice,
cache and lifecycle behavior. Earlier Home cache work explicitly preserved the
decoder; named slideshow/preview/buffer contracts own different paths. Their
original clocks remain. This is a bounded scope distinction, not an exhaustive
historical audit.

## Verification and limits

- Independent source algebra and complete byte comparison pass; separate
  verification and execution reviews were fully read and rehashed before commit.
- All **12 existing** shared-budget controls pass before the edit and within the
  full post-edit suite. They execute `SlideshowDecodeBudget`, not Home or Android
  Matrix. There is **no Home pixel assertion RED/GREEN or new regression coverage**.
- The full run executes Kotlin compilation and unit tests. All 453 archived XML
  suites have fresh timestamps within the actual 44.76-second run. The 12 controls
  are included in the 4,358 unique total.
- Retained debug APK: **51,438,540 bytes**, SHA-256
  `1a4aef15085df4e151803e536da5fa496bbb3d3222a8bea33d62c1a705d9704b`. It was not installed; signer/version/native decoding
  and release acceptance are not claimed. Existing compiler/JVM warnings remain.
- Gradle dependency resolution is offline. Existing tests are not certified as
  DNS-isolated: the MA fake socket's hostname may resolve before its inert
  `connect`. No DNS delay or reliability defect was observed.

The sanitized source/run/artifact bindings are in
`mocks/portal-widget-gallery-20261102/evidence/home-photo-exif-diagonals-verification.json`.
Machine-local logs, XML, APK and reviews remain private. The initial pending-handle
caution is resolved by actual timestamps: the oracle run finished before the edit.

Original unit clock: **21:08:44–21:38:44 UTC on 3 November 2026**, including
all discovery. MA framing and WebApp error checks yielded bounded negatives;
inactive thumbnail helpers were excluded. No photo-source, household image,
device/phone, provider/account, publication or earlier held-scope action occurred.
Native Home pixel verification and deployment remain separate from this local
source correction. The whole-product objective stays active.

# Home photos: local decode fallback

Home's Photo memory card now tries the next local image when an earlier image has
invalid decode bounds, returns no bitmap or throws an ordinary decode exception.
The existing loader previously selected only the first image, so one bad file could
hide later good photos through repeated refreshes. The change is confined to local
selection. The existing60-file cap, video exclusion, first successful result and
per-attempt work checkpoint remain. Decoder, EXIF, cache, TTL, layout and other
photo-source branches retain their implementation.

Eight frozen tests call the actual private loader with real temporary files and
LocalMedia enumeration. Android decoder/Options/Bitmap/normal EXIF/Matrix/Log are
mocked. The unchanged baseline has three intended AssertionErrors and five controls;
the candidate passes8/8. The initial fixture attempt had an ExifInterface host-stub
logging setup failure and is excluded from admitted RED. A scoped Log mock repaired
that adapter; all eight method inputs and assertions remained unchanged, and the
corrected baseline ran before any product edit.

Full **4,593 JVM cases /478 suites**, debug APK and optimized APK/R8/vital lint pass
in **113.05seconds**. Independent review of32 material bindings,23 source assets and
all478 fresh XML files was fully read and root-rehashed before promotion. Both APKs
have the expected debug package,1.178/code172 and established signing identity.
All23 source assets retain their1.177 bytes; only generated optimized baseline.prof
changes.

Exact optimized **1.178/code172** is installed and fully hashed in both rooms, one
install each. Living's uncertain acknowledgment was settled by6 read-only observations,
without replay. APK SHA256
`2331559622debc6e111a66167c2ba25bf5515cfe919d1baf990daaa826925042`
(25,886,947bytes). All43 Family and30 Living preference files and four complete maps
are identical. Saved Shuffle/fit, names, Developer Mode, Family font/accessibility
and the original Chromium activity are preserved. Both frame requests pass;
Family frame is resumed and awake. Captured fatal/ANR markers are zero.

Original20:42:52UTC start,20:57:52target and21:12:52hard stop include discovery,
checks, review, delivery and cleanup without reset. The15-minute target was missed;
delivery/preservation/credential cleanup took **25.05minutes**. Documentation
and commit use the same original hard stop. Copied credentials are retired and owner
registries unchanged. All177 older queue objects, raw task suffix, default and clocks
remain; current queue178 has123 delivered,45 verified,2 approval,7 parked and1 queued.

This establishes generated JVM selection behavior and exact delivery. Native pixels,
mounted UI, actual household corrupt files, optimized helper runtime and runtime
cancellation/fatal-error behavior are not newly demonstrated. The earlier screensaver
black-handoff issue and pending human priority choice remain open. No phone/provider/
account/household media/auth/push/publication operations; public version1.83 remains.
Evidence: `mocks/portal-home-photo-fallback-20261104/evidence/`.

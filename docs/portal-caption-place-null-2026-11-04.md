# Photo captions: optional place names

Photo place labels now treat explicit JSON-null city, locality, region and country
as absent. Existing city to locality to region/country fallback and all non-null
optString coercion remain. A literal string null remains a string. Only four lines
in the existing parsePlace block change; metadata/date parsing, geocoder requests,
place cache, source ownership and rendering retain their implementation.

The frozen eight host cases all passed BEFORE this correction: the host org.json
library differs from Android here, so that baseline supplies no RED. A generated,
offline Family Android10 app_process probe then loaded the exact previous1.178
debug APK through DexClassLoader and invoked its actual parsePlace method. Four
expected output mismatches and four controls were observed: the incorrect outputs
included null, Ireland; Paris, null; and null, null. The corrected1.179 debug APK
passes8/8 through the identical fixture DEX, binary manifest and expected rows.
Class origin and embedded source APK hashes were checked; both owned device
fixture directories were removed. This probe did not install an APK or call UI,
provider, photo, configuration or account operations.

The initial native packaging attempt used a wrong asset-directory argument and
failed before any device calls or actual cases. Only that argument was corrected;
all test inputs, expectations and production bytes stayed unchanged before the
accepted native baseline. Failed public source retrievals supplied no authority;
the actual native differential supplies this defect's evidence.

Full **4,601 JVM cases /479 suites**, debug and optimized APK/R8/vital lint pass in
**121.27seconds**. Independent review of46 material bindings,23 source assets
and all479 fresh XML was fully read and root-rehashed before promotion. Both APKs
have the expected debug package,1.179/code173 and established signing identity.
All23 source assets retain their1.178 bytes; only generated optimized profiles may
differ. The native Java/manifest are retained with the same frozen assertions in
mocks/portal-caption-place-null-20261104/native for future Android checks using
the existing scripts/portal_native_fixture.py packaging helper.

Exact optimized **1.179/code173** is installed and fully hashed in both rooms, one
install each. Living's acknowledgment status is uncertain, settled by
6 read-only observations without replay. APK SHA256
`2de751d0dc880fccd389a3195bdc072a67b8b8234fad4c1961b40852117c6eb0`
(25,886,951bytes). All43 Family and30 Living preferences and four complete maps
are identical. Saved Shuffle/fit, names, Developer Mode, Family font/accessibility
and the original Chromium activity are preserved. Frame requests pass; Family
frame is resumed and awake. Captured fatal/ANR markers are zero.

Original21:08:43UTC start,21:23:43target and21:38:43hard stop include discovery,
checks, review, delivery and cleanup without reset. The15-minute target was missed;
delivery/preservation/credential cleanup took **19.59minutes**. Documentation
and commit use the same clock. Copied credentials retired; owner registries remain
exact. All178 older queue objects, raw suffix, default and clocks remain; queue179
has124 delivered,45 verified,2 approval,7 parked and1 queued.

Actual generated Android debug-parser behavior does not establish installed
optimized-helper, API28, provider data or rendered/native photograph caption pixels.
Earlier held issues and the pending human priority choice remain open. Previous
unselected News observations retain their20:42:52/21:12:52 clock and are not reopened.
No phone/provider/account/household media/auth/push/publication operations; public
version1.83 remains. Evidence: mocks/portal-caption-place-null-20261104/evidence.

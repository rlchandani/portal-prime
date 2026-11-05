# Photo face metadata: exclude off-image detections

Both rooms now contain exact optimized **1.192/code186**, confirmed by complete APK readback. One install was attempted in each room. Living Area's acknowledgment was uncertain after restart; six read-only observations confirmed the artifact and service readiness without another install.

A detector box wholly outside the image could pull the focal point toward an unrelated edge and inflate the People score even when the visible face bounds were correct. The two existing admission filters now require a positive intersection with the image. Face thresholds, centroid/coverage math, partly visible raw-box behavior, detector, cache and renderer stay unchanged. Existing complete cached metadata is not rewritten.

Ten frozen tests call the actual pure production methods with generated coordinates. Six AssertionErrors and four passing controls on the baseline become ten passes. Cases cover every outside side, mixed visible/outside detections, edge contact without visible area, ordinary portraits and partly visible faces. Full **4,662 JVM tests / 485 suites** pass with zero failures, errors or skips. Debug and optimized builds, R8 and vital lint pass in **98.49 seconds**. Independent source review accepted the two-filter change; root rehashed all 18 review bindings before promotion.

Both signed artifacts retain the signing certificate. All 23 packaged source assets are exact against 1.191; only permitted generated optimized profiles may differ. The delivered optimized APK is **25,903,483 bytes**, SHA-256 `97b1c55d38df61f545073969025777c91435550bf805290ea262fe4104b237fa`.

All **43 Family / 30 Living preference files**, their file sets and four complete saved maps including revisions remain identical. Names and Dev Mode remain. Family font, accessibility, original Chromium Activity, resumed frame and awake state remain. Both frame requests are acknowledged; captured diagnostics contain no fatal or ANR markers. Copied registries are removed and original registry hashes match.

These are generated metadata-math tests and artifact/preservation checks. Actual ML Kit output, mounted photo crops, pixels, optimized function behavior and API28 are not established. The broader visual backlog stays open. No household photo/provider operations, phone control, account changes, GitHub authentication, push or publication occurred. Public 1.83 remains unchanged.

Original clock: **02:17:52 UTC**, discovery cap **02:22:52**, target **02:32:52**, hard limit **02:47:52** on 5 November 2026. Earlier held clocks remain separate. A preceding install-wait discovery closed with no fresh defect and no code/device change; it was not promoted as a feature.

Machine verification: `mocks/portal-face-image-admission-20261105/verification.json`. The whole product objective remains active.

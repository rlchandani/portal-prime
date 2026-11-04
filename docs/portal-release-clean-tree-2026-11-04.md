# Reject untracked release inputs

The release script now refuses nonignored untracked files before fetching,
bumping versions or building an APK. Previously its tracked-file checks could
accept a new Kotlin source or bundled asset that entered the APK without
entering the release commit and tag. The change adds two lines; all other
release-script bytes remain unchanged.

Ten frozen tests execute the actual cleanliness block in generated Git
repositories. The baseline has six expected assertion failures and four passing
controls; the candidate passes all ten. Cases include Kotlin sources, bundled
assets, unusual filenames, hidden untracked status and a failed Git query.
Clean committed trees and ignored signing/build outputs remain accepted;
staged and unstaged tracked changes remain rejected. Ignore-rule controls use
generated fixture rules; repository and nested ignore rules were separately
source-reviewed.

All **264 Python CI tests** pass in 24.692 seconds, with zero failures or errors.
Bash syntax and independent source review pass. The full release script,
authentication, fetch, version mutation, Android builds, signing, tags, pushes
and publication were not executed. Ignored build inputs, nested repositories
and changes after preflight remain outside this check's guarantee.

This is a locally verified host-script change. Both rooms retain their prior
**1.151/code145** delivery; no device or phone was inspected or modified.
Public **1.83/code77** remains unchanged.

The original issue clock is **11:04:11–11:34:11 UTC**, with an **11:19:11**
target. Selection occurred at **11:19:11.508455 UTC**, after the target had
elapsed; the clock was retained. Evidence is in
`mocks/portal-release-clean-tree-20261104/`.

# Implementation handoff — conditional on approval

The owner requested this mock and specified that **Android implementation starts on a new Git branch after design sign-off, preserving old changes**. This file carries that instruction forward. The mock is not sign-off; do not implement or install yet.

## Recorded starting point

- Repository: `~/portal-prime`.
- Branch at review: `main`.
- HEAD: `caf8f350cabd00ab1b4aa2951e512bf6e351a702`.
- Tracked working tree was unchanged at inspection.
- Earlier `mocks/ipados` files and `mocks/ux-research-2026-10` were untracked. This new mock is also untracked.

A branch protects commit history; it does not by itself protect untracked files. Recheck the current tree immediately before implementation because the owner may have continued working.

## Mandatory first actions after approval

1. Record the then-current branch, HEAD, tracked diffs, staged state, and non-ignored untracked paths without printing signing credentials or fleet tokens.
2. Preserve user work in a recoverable checkpoint, including the old and approved mocks. Keep secrets, signing files and ignored fleet registry outside commits; preserve their existing locations.
3. Create a fresh `codex/portal-plus-ipad-ux` branch from the approved current state. If that name exists, use a new descriptive suffix rather than overwrite it.
4. Confirm the new branch and preservation checkpoint before any Android source edit. Work on that branch; do not reset, clean, force checkout, overwrite user changes, or drop recovery stashes.

## Implementation order

Start with bundled fonts, tokens and shared settings components. Validate appearance and row geometry on hardware early. Then implement the category map and Activity navigation, safe home/favorites migration, source and picker journeys, and secondary tools/store surfaces.

Use the declarative registry for all settings and preserve `onApplied` hooks. Keep specialized source credential groups atomic. Respect existing boot, dream, installer, remote and fleet behavior.

The approved screenshots, `tokens.js`, CSS and screen map remain the design reference. Ask for reapproval when a required change would visibly deviate, rather than substitute an approximation.

## Validation and acceptance

Run the relevant build and unit tests required by `AGENTS.md`. Use the existing debug application ID and signing workflow. Install only as part of the approved implementation; preserve the release app and data.

Record device configuration, capture native screenshots with deterministic fixtures, compare the reference gallery, and exercise actual touch, remote, Back and keyboard behavior. Verify settings and widget migration, real services, long text, offline/empty/error states, portrait rotation and changed font scale. Complete owner review of the installed result before declaring the redesign finished.

# Native settings editor source review — 2026-11-02

Independent bounded static review of the working tree at
`~/.codex/worktrees/portal-music-player/portal-prime`, branch
`codex/portal-hi-widget-fixes-20261102`, against base/observed HEAD
`4b701641ffdd3531d5eb0e01cba1c60a06b2f88d`.
Source identities were recorded at `2026-11-02T07:03:46Z`.

No concrete production regression was found in the reviewed change. One test
coverage claim needs qualification; it is not evidence of a current rendering bug.

## Finding

### [P2] Classification coverage does not prove that a renderer branch emits a control

Location: `app/src/test/java/com/immortal/launcher/NativeSettingsEditorTest.kt:78–83`;
related renderer integration: `app/src/main/java/com/immortal/launcher/SettingsRenderer.kt:281–329`.

`registeredWritableAndNavigationSpecs_cannotSilentlyDispatchToAnEmptyRow` asserts
only that each registered non-derived spec maps to an enum other than `HIDDEN`.
None of the nine tests invokes `SettingControl` or composes its output.
Consequently, replacing the renderer's `TEXT` branch body with `Unit`, while
leaving `nativeSettingsEditor` unchanged, would leave all nine assertions
unchanged. The same gap applies to dropping the navigation click callback or
replacing the renderer's clock component with its ordinary integer component.
These are static counterexamples, not executed mutations.

The current renderer is correctly wired by source inspection. The issue is the
stronger “cannot silently dispatch to an empty row” claim and any interpretation
that these tests satisfy the plan's renderer-level regression requirement.
Describe them as classification/projection guards. If automated protection of
actual component dispatch is required, separately exercise that integration.
Main owns any such implementation and native acceptance.

The related test titles also have a narrower meaning than UI acceptance:
`inlineStrings_haveAnEditorIncludingMaskedSecrets` checks `TEXT` for a secret
spec, not masking; `richEditors_keepNavigationForImmediateAndWorkerLabels`
checks `NAVIGATION` for both flag values, not execution on a worker.

## Source conclusions

- `NativeSettingsEditor.kt:23–34` exhaustively classifies the sealed spec types
  using type and editor metadata. It has no snapshot or Context parameter and
  calls no getter, setter, visibility predicate, navigation label, or activity.
  `timeOfDay` takes precedence over `asText`; inline and activity-backed strings
  remain distinct.
- The renderer diff preserves the existing components, snapshot getters,
  `onApply` keys/values, help propagation, theme special case, and enabled
  arguments. The new policy directly selects the actual `SettingControl`
  branches. Its returned kinds match the casts in those branches.
- Fleet name still passes `preserveUneditedDraft = true`. The string editor's
  remembered draft/opening value and untouched-save check are unchanged.
  Inline secret summaries, empty initial secret drafts, password transformation,
  blank-secret preservation, and `applyWhen` handling are unchanged.
- Activity-backed strings retain their destination and getter; rich navigation
  retains both the immediate-label and `valueOnWorker` paths. The worker row and
  navigator are unchanged. This is preservation evidence from source comparison,
  not proof of runtime execution.
- Visibility remains separate from editor selection. The existing
  `prepareSettingsVisibility` filters derived fields before creating controls,
  retains exclusion/Advanced/screensaver gates, and is still used by the
  renderer. The new derived test calls this actual preparation/projection with a
  synthetic domain and `render = true`; its asserted absence is meaningful.
- There are nine new `@Test` methods. The registry-wide test visits the real
  `SettingsRegistry.domains`, which delegates to `SettingsDomains.all`; the fleet
  name and dashboard URL test selects real registered specs. Synthetic fixtures
  use throwing getter/setter or navigation-label lambdas, so accessing those
  supplied callbacks would fail those tests. This does not instrument the real
  registered getters/setters or exercise real snapshots.
- Removing `onDeviceRenderedDomains_haveNoUnrenderableInlineStrings` is justified:
  its comment says inline strings render nothing, but the base renderer already
  routes them to `ApprovedStringSetting`. The removed test encoded a stale
  prohibition and omitted fleet/dashboard domains. Its deletion does not remove
  an accurate guarantee that inline strings lack an editor.

## Reviewed file identities

SHA-256 of exact working-tree bytes; paths are relative to the repository above.
The two new files were untracked at inspection.

| File | SHA-256 |
| --- | --- |
| `app/src/main/java/com/immortal/launcher/NativeSettingsEditor.kt` | `1939e3a3075db76b6d6309f78a0b40d6bc85b29cdd802ccb7c45ae7776bdb808` |
| `app/src/main/java/com/immortal/launcher/SettingsRenderer.kt` | `63afa6f10117b4f96b78a9c7395cb730f978d469f1397a854bd6fa532f74156e` |
| `app/src/test/java/com/immortal/launcher/NativeSettingsEditorTest.kt` | `42a1d05e895f1736dd1511199bf13989df7a2283d2cb0a8b7a0559d1d6b25b78` |
| `app/src/test/java/com/immortal/launcher/settings/SettingsDomainTest.kt` | `8c7a2870cb922017808b4cb26f5ba099f318ef55f49614dc3d52c9b9f98dcc04` |

Supporting source identities:

| File | SHA-256 |
| --- | --- |
| `app/src/main/java/com/immortal/launcher/SettingsVisibilityPreparation.kt` | `75ab835407ebb97bb771005b5e41eb9f73dd9bf151c6ef9e07a1a85bd9950bdc` |
| `app/src/main/java/com/immortal/launcher/settings/SettingSpec.kt` | `756ab777e09c9ae44362f0ec2484027eb5159a42cbc287549b876113265b1c85` |
| `app/src/main/java/com/immortal/launcher/settings/SettingsDomain.kt` | `0893fa845f5849a71a3619f23b58cc1f5275777b36402cb737e0e71a41313fec` |
| `app/src/main/java/com/immortal/launcher/settings/SettingsDomains.kt` | `de366051ad676a3b8f555f828dda45fba334f72ba7364d344a62dc8d2d4f13a6` |

## Unverified limits and scope

No tests, mutation checks, builds, app/device operations, network/auth operations,
GitHub actions, hooks, commits, or agents were run. Read-only Git commands used
`git -c core.hooksPath=/dev/null --no-optional-locks`. Only this document was
written by this reviewer.

Compilation, JVM results, real UI reachability, D-pad/touch/keyboard behavior,
dialog recomposition during companion renames, secret masking on screen,
navigation destinations, worker execution/lifecycle, and native acceptance remain
unverified here. Main owns builds and native acceptance. Existing behavior outside
the specified diff was inspected only as needed to assess preservation; this is
not a full settings subsystem audit. Concurrent changes after the recorded hashes
are outside this review.

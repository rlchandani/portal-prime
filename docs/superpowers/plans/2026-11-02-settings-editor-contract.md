# Native settings editor contract

Whole-product backlog item 3. Slot **06:56:24–07:26:24 UTC**, including cleanup.
Base `4b701641`; previous turn delivered World Clock and is verified progress.

Replace the stale inline-string prohibition with a native editor policy consumed
by the actual renderer. Preserve existing controls, validation, secret handling,
visibility, rich navigation, worker labels and persistence. No redesign or
disabled-parent policy change. Do not infer UI reachability from classification.

The tests must catch an inline string classified as hidden, a time-of-day
integer classified as an ordinary editor, or a rich string losing its navigation kind.
Exercise all real registered specs and the real projection's derived-field
exclusion. Use metadata-only fixtures whose getters/setters fail if accessed.
These are classification/projection tests. They do not compose a control or prove
that a renderer branch emits UI; independent source review records that limit.

- [x] Functional red for the unimplemented policy; no claim of a reproduced UI bug.
- [x] Exhaustive policy and renderer integration; remove obsolete prohibition.
- [x] Focused green, deliberate hidden-text classification mutation failure, restore.
- [x] Full JVM gate and applicable builds; source review/traceability.
- [x] Record actual delivery boundaries and commit; no APK installation for this maintenance unit.

Independent companion worker owns only its new Toolkit checkout and fallback
tests. Gradle work is serialized to avoid competing compiler heaps. No GitHub,
HA service, credential or device changes by that worker.

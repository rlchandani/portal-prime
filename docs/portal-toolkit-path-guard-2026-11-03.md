# Portal Toolkit basic-mode parent path guard

Portal Toolkit's file manager now rejects exact `..` path components in basic
mode and checks the constructed Rename destination before dispatching `mv`.
Previously, `/sdcard/../data/local/tmp` passed the textual `/sdcard/` prefix
check, and Rename checked only its source. The existing v1.4.0 release notes
promise traversal protection; the design requires Advanced mode for operations
outside `/sdcard`.

This is a local correction in the separate Portal Toolkit app, whose source
declares package `com.portalinstaller`, version 1.4.0/code5. It does not change
the Immortal launcher or the paired companion's delivery.

The Toolkit commit is `5bee76745775af97454e46768fa6abdbb541c9b6`, based on
`4e1dadb8ec2ec018b6fdc250486e0c476467a7c4`, on local branch
`codex/toolkit-parent-path-guard-20261103`. Exactly three existing files changed:
`PathSafety.kt`, `PortalFileManager.kt` and `PathSafetyTest.kt`. The original
Toolkit checkout remains clean at its original commit; the fix is in an
isolated checkout.

## Recorded validation

Before editing production, the existing full Toolkit JVM suite was rerun
offline: **175 tests / 34 suites**, zero failures, errors or skips. Its selected
source and build files remained unchanged.

Eight regression methods were then added without changing the five existing
test bodies. Against unchanged production, the focused task executed **13
tests: five assertion failures and eight passing controls**. Each failure
reported an expected `SecurityException` that was not thrown.

After the two production checks were added, the identical fixture passed all
13 methods. The full candidate gate passed **183 tests / 34 suites**, with zero
failures, errors or skips, and assembled the debug APK:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --offline --rerun-tasks
```

The actual candidate run completed at 17:32:08–17:32:29 UTC with exit 0.
The debug APK is 13,901,710 bytes, SHA256
`540c13b9b9bb53f14260cfc17179c02215feda989dbc26d701fdd65e5ab996e3`.
Two native libraries were packaged without symbol stripping; assembly succeeded.
Independent review confirmed the source changes, unchanged fixture, actual
baseline failures, fresh candidate XML results and APK hash.

## Preserved behavior and limits

Advanced mode keeps its existing explicit bypass. Basic mode still accepts
ordinary `/sdcard` paths, dotted and hidden filenames, single-dot components
and repeated slashes. It rejects parent components even when a path would
leave and subsequently return to `/sdcard`.

The tests execute the real shared guard and real path construction. They do
not invoke `PortalFileManager.rename` or a remote shell. The actual
UI-to-ViewModel-to-manager route and destination-check ordering were reviewed
as source. Symlinks, filesystem permissions and race-free containment remain
unverified; host path canonicalization cannot establish a Portal filesystem's
containment.

This checkpoint establishes host JVM verification and debug assembly only.
No phone or Portal installation, native UI/runtime, ADB/provider operation,
signer identity or publication is accepted here. Existing phone authorization
and delivery boundaries remain. The original discovery clock is
**17:15:43–17:45:43 UTC on 2026-11-03**, including discovery and the earlier
Toolkit host gate; it was not reset. The broader product goal remains active.

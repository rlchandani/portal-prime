# Owned native memo-finalization fixture

`MemoActivity.java` loads actual production classes from an exact debug APK asset
with a framework-only DexClassLoader. Each case supplies a generated files directory
and injects recorder/staging/start ownership before calling public `stopRecording()`.
The real shared worker and production finalization/publication code execute.
Preference reads are forbidden.

The `MediaRecorder` subclass allocates a native handle, generates bytes in `stop()`,
and calls actual `super.release()` before an optional simulated release exception.
It never configures an audio source, prepares or starts recording. The manifest
requests no permissions. Seven modes cover valid replacement, first save, short,
stop/release failure, empty output and rename denial using an owned directory's
permissions. The denied directory retains staging until permission restoration and
fixture cleanup. An unrelated generated staging file must remain unchanged.

Build with Java21 `javac -source 8 -target 8` against Android36 `android.jar`,
then build-tools36 `d8`, `aapt2 link`, `zipalign` and the ordinary debug signer.
Supply the exact candidate debug APK as temporary `assets/source.apk`.
No APK or generated audio is committed.

Only the authorized Family Area Portal was used. The seven candidate cases pass
on API29; `metadata.json` and `report.json` bind the loaded APK hash. The separate
package `com.immortal.memofixture` is uninstalled afterward. `cleanup.json` records
removal. This fixture does not establish real capture, media validity, native
preparation cancellation, mounted Compose, API28, optimized recording behavior or
a native baseline differential.

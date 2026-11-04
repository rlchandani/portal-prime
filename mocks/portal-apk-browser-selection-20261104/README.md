# Local APK selection acceptance

See [delivery receipt](../../docs/portal-apk-browser-selection-2026-11-04.md).
The baseline text models the old public-file handoff; it is an extracted equivalent,
not historical Activity execution. Native files are generated and metadata callbacks
read literal bytes. No real APK installation or UI acceptance follows.

Build the frozen wrapper with `bash native/build.sh /absolute/baseline.apk [SDK]`.
Use `bash native/repack.sh /absolute/candidate.apk [SDK]` to swap only source.apk,
retaining the exact frozen DEX. Device execution belongs to the authorized coordinator;
retained full APKs, compiler logs and household snapshots stay private.

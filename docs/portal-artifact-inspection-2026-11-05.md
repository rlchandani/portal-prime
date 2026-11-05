# Inspect supplied APKs without rebuilding

The local checker now accepts `--inspect-apk PATH`. This removes the need for another Gradle build or repeated ad hoc inspection code when a frozen candidate and its separate gate evidence already exist. The default full-readiness and JavaScript paths retain their code.

Inspection verifies current version policy, selected package, baseline signing certificates, all current bundled source assets, and stable complete APK/source hashes. Version parsing happens after the initial source snapshot. It reads both APKs in place and reports `checked` in `local-artifact-inspection`; it does not produce fresh test/build readiness or establish compiled-code provenance. See [the command and requirements](releasing.md#inspect-an-already-built-apk).

Fourteen frozen actual-CLI methods use generated APK ZIPs and SDK executable fixtures. Before implementation, twelve fail because the option is absent and two parser controls pass; these are missing-feature assertions, not twelve security defects. The initial macOS canonical-path expectation was corrected, then the final suite was rerun against the byte-exact original CLI. All345 Python tests pass against final code in34.43seconds. Hosted CI was not run.

The final mode also inspected the real signed1.201/code195 debug and optimized APKs with SDK36 aapt/apksigner against retained1.200. They took1.10 and0.67seconds, matched the expected package/version/certificate, and verified23 source assets each. All497 watched files, including492 existing JVM reports and both APKs, retained their full bytes and modification times. Node, Gradle and test gates were not invoked by inspection. These two measured runs establish inspection time, not an end-to-end release speedup.

Independent source review passed; root fully read it and rehashed21 input bindings. The final ordering correction received source review and fresh root Python/SDK runs. Source helpers and default execution functions are byte-exact to the prior version. The machine receipt is `mocks/portal-artifact-inspection-20261105/verification.json`.

No build, room installation, device/provider/account/media/phone action or GitHub authentication/push/publication occurred. Rooms retain previously verified1.201/code195; there is no fresh room check. Public manifest1.83 remains. Supplied APKs can change after inspection: rehash against the receipt before subsequent use.

Original start **2026-11-05 04:54:52 UTC**, discovery cap **04:59:52**, target **05:09:52**, hard limit **05:24:52**. Source opportunity was confirmed04:56:53 before the cap; the frozen actual-CLI baseline followed the cap. No clock reset. Prior held scopes and the whole-product objective remain.

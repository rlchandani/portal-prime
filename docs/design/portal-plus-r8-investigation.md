# Portal+ R8 benchmark candidate

This is an opt-in experiment, not a release configuration change or acceptance
claim. `:app:assembleBenchmark -PportalBenchmarkOptimize=true` applies R8 to the
non-debuggable benchmark variant under the existing debug package/signing key.
The default benchmark keeps the production release's unminified options.

The first optimized build failed on `javax.el` and `org.ietf.jgss` references.
The narrowly scoped rules are based on the locally resolved dependency bytecode:

- SMBJ 0.13.0 `SmbConfig` detects `android.os.Build`, sets `ANDROID`, and skips
  reflective construction of `SpnegoAuthenticator.Factory` on Android. It
  registers NTLM. `SmbSource` uses plain `AuthenticationContext`, not
  `GSSAuthenticationContext`; the missing desktop GSS interfaces belong to the
  unused Kerberos path.
- MBassador 1.3.0 `MessageHandler.Properties.create` calls `ElFilter` only when
  `Handler.condition().length > 0`. The annotation's default is empty. All
  three SMBJ annotated methods (`SMBClient`, `Session`, `Connection`) use that
  default; app source defines no MBassador handlers. EL is therefore outside
  the current event-bus path.
- MBassador uses runtime annotations and reflective invocation/filter
  construction. The candidate conservatively keeps that small library and
  annotated SMBJ handler methods. It also keeps the photo engine's annotated
  JavaScript bridge methods.
- The separately compiled debug test probe shares the target's Kotlin runtime.
  Its bytecode calls `kotlin.jvm.internal.Intrinsics` by name, while the first
  candidate's mapping renamed that class to `tm1` (and renamed Kotlin IO,
  collections and Unit). The measurement returned `NoClassDefFoundError` before
  completing. The screening candidate therefore retains Kotlin runtime names
  and members with optimization allowed. This accommodation is benchmark-only
  and means the candidate is not an exact prospective shipping R8 artifact.
Shipping validation requires a test artifact built against its matching
  optimized application, or an independent framework-only probe.

The rebuilt artifact `bade4da2217d7ead65a5a3654ecbaccf3a1453245b8ebbe2667503d8f6fd9e6a`
installed and independently matched its hash. One Settings trial completed:
120 valid frames, p95 elapsed proxy 38.51 ms, maximum 49.96 ms, 36/120 over
the measured 16.666666 ms screening budget. CPU-stage elapsed proxy p95 was
28.50 ms. It therefore still fails the target. This is a single screening
trial, not three paired trials or a comparative improvement claim. The debug
APK `5f1de1a9…` and original widget bytes were restored afterward.

The rules suppress only the two proven optional missing API families. They
neither supply those APIs nor make Kerberos or expression filters work on
Android. Revisit this boundary if dependency versions, authentication context,
handler conditions or bridge discovery change.

Before changing release options, verify the resulting artifact on the Portal:
native navigation and widgets, a real SMB NTLM connection/list/read/close and
cancellation, MBassador disconnect/logoff delivery, and the photo WebView bridge.
Then collect three paired rendering trials with matched source, content, cache,
thermal and compilation conditions. A successful build alone does not pass
these checks. Physical input/presentation timing remains a separate quality gate.

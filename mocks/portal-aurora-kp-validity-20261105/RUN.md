# Generated Aurora Kp getter validity probe (source only)

Baseline /private/tmp/portal-product-discovery-20261105-033149/candidate-debug-1.196.apk
Bytes 46746006 SHA256 dcf74425ebb61379a153d0119a3fbc879019728fcbd54a485cfa3f7340de1f61; supplied expected binding match: True.

Root reuses established offline native app_process/DexClassLoader infrastructure. Compile AuroraKpValidityProbe.java against Android jar using javac -source8 -target8; D8 ALL generated class files --lib android.jar --min-api29 into owned probe.jar. Freeze Java/JAR before baseline; same source/JAR/driver and expectations for candidate, exact APK alone differs. Main: AuroraKpValidityProbe EXACT_OWNED_DEBUG_APK OWNED_DEX_DIRECTORY. Root owns isolated fresh process invocation and scratch paths/cleanup; no author compiler/build/test/device/runtime execution.

Verify actual APK Aurora.INSTANCE class origin against exact DexClassLoader. Uniquely discover actual private kpOf(Object) returning java.lang.Double (mangled suffix accepted), invoke directly; unsupported reflection/origin/signature setup is not semantic RED. Inputs use framework JSONObject/JSONArray built from generated strings. No copied extraction algorithm, HTTP call, cache manipulation or classify/status invocation. Fresh-process URL factory denies all URL opens without delegation; counter0 required.

Eight frozen literal expectations: object Kp string Infinity=>null; array stringNaN=>null; array stringInfinity=>null; lowercasekp -0.5=>null; uppercaseKp9.5=>null; uppercaseKp0=>0; array string9=>9; lowercasekp string3.67=>3.67. Nonfinite actual Double values serialized as safe stringNaN/Infinity/-Infinity with separate null/finite flags; never pass nonfinite Double to JSONObject.put. Finite results compare to exact numeric literals. Predicted native five mismatches/three controls awaits actual API29 evidence; source preparation itself is not reproduction.

No Context, actual requests/sockets, preferences, household location/data, providers, NOAA, full outlook, geometry/classification, HTTP/caching/rendering/native UI acceptance or product edits. Original2026-11-05T03:45:03Z target04:00:03Z hard04:15:03Z retained; native source-preparation cap03:52:00Z.

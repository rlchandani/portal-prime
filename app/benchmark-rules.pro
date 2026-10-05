# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.

# The photo engine invokes annotated bridge methods by name from JavaScript.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# SMBJ 0.13.0 skips its SPNEGO factory when android.os.Build is present and
# SmbSource uses plain AuthenticationContext/NTLM, never GSSAuthenticationContext.
-dontwarn org.ietf.jgss.**

# MBassador 1.3.0 loads EL only for a nonempty @Handler(condition=...).
# All three SMBJ handlers use the annotation's empty default; Immortal adds none.
-dontwarn javax.el.**

# Candidate-only conservative boundary: MBassador constructs invocation/filter
# objects reflectively and discovers the SMBJ event methods through annotations.
# Preserve this small dependency rather than inferring every reflection target.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep class net.engio.mbassy.** { *; }
-keepclassmembers class com.hierynomus.smbj.** {
    @net.engio.mbassy.listener.Handler <methods>;
}

# The separately built debug test APK uses the target's Kotlin runtime by its
# original ABI. Preserve that boundary only in this screening variant; caller
# optimizations remain allowed. A shipping benchmark must use its own matched
# test artifact rather than inheriting this instrumentation accommodation.
-keep,allowoptimization class kotlin.** { *; }

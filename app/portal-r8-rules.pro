# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.

# WebView calls annotated bridge methods by their JavaScript-visible names.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Preserve the small legacy SMB implementation and its reflective event bus.
# This candidate does not optimize their authentication or event-dispatch code.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }

# SMBJ skips SPNEGO on Android; Immortal uses NTLM, not GSSAuthenticationContext.
-dontwarn org.ietf.jgss.**
# SMBJ handlers use the empty condition default; no application EL handlers exist.
-dontwarn javax.el.**

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

# Hearthpane Calling: libwebrtc calls back into Java through JNI (@CalledByNative), by name.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Cold start: keep code that names classes missing on Android 10 (API 29) out of the classes Home's
# first frame loads. R8 merged or inlined these into Compose startup classes (Recomposer's Latch,
# MeasureAndLayoutDelegate's depth sets, AndroidParagraph, animateValueAsState's coroutine), so the
# install-time verifier soft-failed them and ART re-verified them on the main thread in every cold
# start's first frame (~15 ms on a Portal+). `allowoptimization` is deliberately absent: it allows merging.
# StartupVerificationRulesTest checks that every class named here still exists.
-keep,allowshrinking,allowobfuscation class androidx.media3.exoplayer.mediacodec.LoudnessCodecController
-keep,allowshrinking,allowobfuscation class androidx.media3.exoplayer.trackselection.DefaultTrackSelector$SpatializerWrapperV32
-keep,allowshrinking,allowobfuscation class androidx.compose.ui.scrollcapture.**
-keep,allowshrinking,allowobfuscation class androidx.compose.ui.text.android.AndroidLayoutApi34 { <methods>; }
-keep,allowshrinking,allowobfuscation class androidx.compose.ui.text.android.selection.Api34SegmentFinder { <methods>; }

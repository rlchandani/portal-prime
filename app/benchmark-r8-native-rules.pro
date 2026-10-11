# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.

# In-app native diagnostics use application class names for window attribution.
# Preserve surviving app class names while allowing shrinking and optimization.
# No separately compiled test APK shares this candidate's Kotlin runtime.
-keep,allowshrinking,allowoptimization class com.immortal.launcher.**

# Preserve the finite operation selectors used by MainThreadSampleHistogram.
# Inlining/removal can still eliminate stack frames; zero samples are not proof
# that an operation did no work. Timed acceptance disables stack sampling.
-keep,allowshrinking,allowoptimization class androidx.compose.ui.spatial.RectManager { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.ui.node.NodeCoordinator { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.runtime.Recomposer { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.runtime.ComposerImpl { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.ui.node.LayoutNodeLayoutDelegate { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.ui.node.LayoutNodeLayoutDelegate$* { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.ui.node.MeasureAndLayoutDelegate { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.ui.node.MeasureAndLayoutDelegate$* { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.ui.node.MeasurePassDelegate { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.ui.node.MeasurePassDelegate$* { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.ui.node.LookaheadPassDelegate { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.ui.node.LookaheadPassDelegate$* { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat$* { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.ui.semantics.SemanticsNode { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.ui.semantics.SemanticsNode$* { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.ui.semantics.SemanticsNodeKt { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.ui.semantics.SemanticsNodeKt$* { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.ui.semantics.SemanticsNode_androidKt { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.ui.semantics.SemanticsNode_androidKt$* { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.ui.semantics.SemanticsOwner { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.ui.semantics.SemanticsOwner$* { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.ui.semantics.SemanticsOwnerKt { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.ui.semantics.SemanticsOwnerKt$* { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.ui.semantics.SemanticsConfiguration { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.ui.semantics.SemanticsConfiguration$* { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.ui.semantics.SemanticsConfigurationKt { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.ui.semantics.SemanticsConfigurationKt$* { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.ui.semantics.SemanticsInfoKt { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.ui.semantics.SemanticsInfoKt$* { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.ui.semantics.SemanticsSortKt { <methods>; }
-keep,allowshrinking,allowoptimization class androidx.compose.ui.semantics.SemanticsSortKt$* { <methods>; }

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Composable

// STUB owned by agent C (cameras, energy, alerts, security). Delete this file when implementing.
@Composable internal fun CcCamerasScreen(s: CcUiState, ui: CcUi) {}
@Composable internal fun CcEnergyScreen(s: CcUiState, ui: CcUi) {}
@Composable internal fun CcAlertsScreen(s: CcUiState, ui: CcUi) {}
@Composable internal fun CcSecurityScreen(s: CcUiState, ui: CcUi) {}
/**
 * A camera picture for any screen: a snapshot refreshed every 5 s while on screen, or the live
 * MJPEG stream when [live] (only one live stream at a time Portal-wide). Owned by agent C.
 */
@Composable internal fun CcCameraTile(s: CcUiState, cameraId: String, live: Boolean, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
                                      label: String? = null, onClick: (() -> Unit)? = null) {}

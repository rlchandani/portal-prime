# Settings detail replacement — 11 October 2026

The right-panel slide was deliberate Compose motion in ApprovedSettingsDetailPanes: 24 dp
translation and a 160 ms fade. Category selections and ordinary scalar sub-settings use the
existing ImmortalSettingsActivity through local route/history state. Dedicated pickers,
connection forms and external destinations still use their declared Activities.

The change removes that motion and admits the selected pane in the same composition instead
of waiting for LaunchedEffect. The current detail renders immediately; SideEffect commits its
snapshot and the bounded four-pane LRU cache. Cached scalar panes retain their objects/keys,
focus/input/semantics and lifecycle gates. Resource-owning editors dispose on departure.
Existing navigation, registry bindings, theme, dimensions and controls are unchanged.

The complete Android gate passed 1,592 tests across 189 suites, zero failures/errors/skips.
Debug and benchmark builds passed. Independent source review found no verified blocker.
Packaged DEX inspection found no Animatable, animateTo or translationX call in the shared
pane method. Native Home remains disabled and accepted native Filmstrip remains enabled.

Family Area baseline and candidate each passed six source-positioned category actions,
covering first visits, cached returns and more than four visited panes. Detail composition
was observed after every action; the Settings window identity stayed unchanged. Seven paired
effect-marker spans were 167–181 ms before (median 172 ms), and all seven were 0 ms after.
These are effect markers, not physical touch latency or proof of first visible pixels.

A separate candidate check passed source-known first/second Appearance rows and the first
Photos row, each followed by Back. All three entry/Back pairs stayed in the same Settings
window with detail composition observed. Exact visible titles, draft/scroll persistence,
physical focus traversal and first-visible-frame correctness were not independently captured.
The source preserves the existing retention/gating policy; unit checks cover route history.

All three completed captures ended inactive, returned Home, preserved full settings schema,
configuration and photo-source groups, and independently verified the expected installed APK.
One initial baseline setup used the helper's integer window ID as a dictionary; the corrected
run passed. The failed setup and verified cleanup remain in private evidence.

The Family candidate APK is 37,133,972 bytes, SHA-256
`a564577a0a3f0d17b1f68adfa8cef3e0d2590525ac0ee8e1aa97d7889b0c02b7`.
Evidence is private under build/fleet/diagnostics/settings-instant-detail-2026-10-11.
Living Area had no test/profiling operations. This accepts the requested UI motion removal;
Settings startup performance remains a separate task. Sparse/cold callback distributions
from these functional checks are not used to claim a general speed improvement.


The same verified artifact was subsequently deployed to Living Area. Independent installed
APK hashing matched a564577a... on both rooms. Living deployment preserved full configuration,
settings schema and photo-source groups, ended with the collector inactive and Home verified,
and started zero performance captures. This was deployment verification only, not duplicate
Living UI/performance testing. The new exact accepted rollback artifact for subsequent Settings
work is the saved candidate-benchmark.apk in this evidence directory; the previous 19abed09...
artifact remains retained for recovery/history.

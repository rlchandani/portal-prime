# Native Settings functional probe

The Settings category, detail, search and Back checks need committed native route evidence; a remote input receipt alone cannot prove the intended page opened. The opt-in `/perf/settings-ui` endpoint provides that evidence for functional tests. It makes no frame-time, display latency or speed improvement claim.

## Build and runtime boundary

`PORTAL_SETTINGS_UI_DIAGNOSTICS` defaults to false. Only a benchmark built with `-PportalSettingsUiDiagnostics=true` enables it. Ordinary builds do not add the diagnostic layout/draw observers. The endpoint is GET-only, Fleet authenticated, Dev Mode gated, and unavailable during a performance capture. Reads run on main with a two-second deadline and require a resumed, focused, non-finishing `ImmortalSettingsActivity`.

The probe reports machine route identifiers, history depth, native Window object identity, Window animation style, screen dimensions, query length, canonical search destinations and clipped control bounds. It does not report search text, labels, preferences, URLs, credentials, pixels or images. Query identity and per-observer ownership remain in memory. Disposing a control retires only its own geometry; disposing a pane retires its own draw evidence. Popup positions use their actual screen offset rather than the Activity decor origin. While search results are visible, underlying category/navigation targets are excluded.

The native Window object ID and Android accessibility Window ID are independent namespaces. The functional driver binds them separately, verifies current route/draw evidence before input, and uses fresh bounds for Settings taps. Search results use the same canonical route resolver as navigation, including category-only Advanced results. Unconfirmed input stops the sequence; input is never automatically retried.

## Current local checks

The complete debug unit suite passes: **1,697 tests in 196 suites, zero failures, errors or skips**. The probe policy contributes 19 tests for route/draw matching, conditional and query-specific geometry, clipping, bounded storage, replacement-safe disposal and canonical destinations. Debug and benchmark builds pass.

Compiled APK inspection confirms a non-debuggable benchmark, package `com.immortal.launcher.debug`, the accepted signing certificate, the opt-in probe enabled, and Settings theme `0x7f110111` with null Window animation style. The 789-file app source inventory is frozen with the test APK under the ignored private diagnostic directory.

The Family navigation driver passes 40 offline tests normally and with `-O`; its parent transport adapter passes 11. The latter checks found and corrected connection-construction and close-error handling before device use.

## Native trial scope

A separate Family-only trial tests five fresh Settings entries: Appearance → Photos → Appearance → Display → Back → search for Display → Back → Home. It starts no performance collector, changes no settings and imports no historic timing cohort as a control. The accepted APK and SDK install profile are restored unconditionally after the trial, with actual APK, installed dex metadata, reference profile, mapped ARM64 OAT header and complete canonical SDK profile-set checks. Configuration/schema/source snapshots remain in RAM and are compared afterward.

The browser cascade-column design remains awaiting user review. This probe adds no cascade layout and does not implement the proposed fourth-level editors. Native trial results are recorded separately after the process terminates; local green tests do not establish native success or completion of the overall performance objective.

## First native attempt and restoration

The first trial terminated with zero completed cycles. Four input operations were acknowledged, then entry stopped at `invalid_pin_owner` before category, subpage or search input. `PinnedAppFrameHost.status()` reports both foreground identity fields as JSON null when its current application root is absent; a read-only bounded entry wait is needed before binding a Settings owner. A missing owner never authorizes input.

Accepted APK restoration and a fresh SDK cache result of 1 passed. The parent initially gave the profile audit tool a project output directory, although that tool requires a child of its private tool directory. An independent offline completion audit of the already captured restoration reference profile passed complete SDK method/class/flag and DEX-checksum equivalence, with zero application entries; exact installed dex metadata and mapped ARM64 `speed-profile` / `install-dm` OAT evidence had already passed. The original failed postflight remains unchanged. No timing or functional success claim is made for that attempt.

## Separate entry-wait retry

The corrected driver permits bounded read-only polling for exact both-null foreground fields during unbound entry observation only. Bound-owner checks and all mutation guards remain strict. Its 47 tests, combined with 11 parent transport tests, pass normally and with `-O`. The parent uses separate private tool-directory outputs for SDK audits. Original failed files are untouched.

The separate native retry completed **five independent Settings entries**. All category, Display, page-only search, Back and Home route checks passed. All five entries retained their own native Window/accessibility owner pair across navigation, with runtime Window animation style 0. There were **111 input attempts, 111 acknowledged operations, 10 acknowledged Back operations, zero uncertain mutations**, and 1,346 bounded API reads/actions overall. Each entry used previously unseen native and accessibility IDs. These are functional ownership/draw-recipe checks, not delivered-frame or speed measurements. The parent terminated with EXIT 0. Accepted APK and SDK profile restoration passed: exact installed APK/DM, a full reference-profile snapshot with complete canonical SDK method/class/flag and DEX-checksum equivalence, mapped ARM64 `speed-profile` / `install-dm` OAT header, and a fresh SDK cache result of 1. Configuration/schema/photo-source snapshots matched, Home was restored, and the collector was inactive with zero listeners. The frozen app source and all input hashes were revalidated after completion. Living Area and the phone had zero operations. The optional test build was not promoted.

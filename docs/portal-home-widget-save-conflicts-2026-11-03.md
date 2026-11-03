# Home widget edits preserve remote layout changes

Exact optimized **Portal 1.100/code94** is installed and fully hashed in Family
Area and Living Area. Native Add now uses the current saved widget list; stale
resize, move and remove edits refresh the layout and ask for a retry.

Home previously retained its widget list until resume. A remote restore could
acknowledge `[A,B]` while Home still displayed `[A]`; native Add C then replaced
storage with `[A,C]`, silently deleting B. This was reproduced with the
production stores and on Family's actual optimized 1.99.

All widget writers now share the companion's revision/preflight/apply lock.
Whole-list edits carry the snapshot from which they were calculated and save
only if it still matches storage. A resize retains that snapshot from gesture
start, including across Compose callback updates. Home observes saved widget
changes while open and rereads after subscribing. A successful removal saves
before deleting its Android widget binding under the same mutation lock.
There is no new setting, schema, storage format or widget design.

## Verification

- Seven initial API regression methods reproduced five failures using a
  temporary adapter that retained the old replacement behavior. Final **nine**
  methods cover restored additions/removals/sizes, stale resize/remove/reorder,
  fresh retries, concurrent single-winner saves, shared locking and observer
  filtering/disposal. Add's original retained-list failure was separately
  reproduced with the production stores and actual native UI.
- **4,084 JVM tests in 425 suites**, debug assembly, optimized assembly, R8
  and vital lint pass. The final gate ran after review's shared-lock and
  gesture-snapshot corrections and took 95 seconds. Reviewed source and test
  hashes match the final files.
- A separate Family shell process loaded the exact debug APK's production
  store/policy classes. All **15 checks** passed using generated in-memory
  preferences, including actual remote preflight/apply, Add, stale edits,
  competing writers and subscription disposal. It touched no application
  preferences, alarms or Android widget bindings. This is Android store/API
  evidence; it does not establish optimized API behavior or real notification
  thread delivery.
- On optimized **1.99**, Home → Customize → Add widget remained open while the
  remote added generated Calendar B. Selecting Today deleted B. On optimized
  **1.100**, the same sequence retained A, B and Today. The same Home Activity
  record survived the candidate remote update. The open gallery's saved-widget
  labels changed from one to two.
- An exact 2×2 generated widget resized normally to **3×2**. A second gesture
  was demonstrably active before and after a remote **1×1** update; releasing
  it retained the remote size. Five final gesture checks pass.

The first resize probe used a legacy presentation hint. It saved an explicit
2×2 size, making the probe's stored-span-change assertion unsuitable; this was
not a production failure. The corrected fixture starts at an explicit size.
The first Add probe requested an offscreen Stocks tile; its bounded lookup
refused the action, then the visible Today tile reproduced the intended case.

## Preservation and limits

Both rooms' complete settings profiles, layouts, sources and screensaver maps,
device names and dev mode match their preflight baselines. Home-widget and
kitchen-timer preference-file bytes also match. Family font/accessibility and
its preflight Chrome task are retained; its normal photo frame is resumed,
displayed and visually reviewed privately. An interim preservation check ran
while Home was intentionally open; only the frame-return check was false.
The final check passes every field.

Owned device fixture/XML and copied registry are removed; the primary registry
is unchanged. Owner photos, tokens, addresses and raw diagnostics remain
private. Existing local roadmap server and unpublished worktree are retained.

Actual app-widget binding deletion, provider setup, API28, full Move/Remove
navigation, original Home page state, natural Dream recovery and Living visual
acceptance remain unverified. No general performance or whole-product claim.
No phone operation, Home Assistant change, GitHub authentication or publication
occurred. Public `version.json` remains 1.83/code77; its version guard deliberately
rejects the local 1.100/code94 mismatch.

APK SHA-256:
`ecf713dbf11abbe71e93f5e6fc1af5e0caa5df2599623c3535e9b4d4de13172b`.

The sanitized receipt is
`mocks/portal-widget-gallery-20261102/evidence/home-widget-save-conflict-verification.json`.
Strict documentation builds with zero warnings. Actual desktop/mobile Chrome
checks preserve the default task and all 72 previous records, render 73 tasks,
select both the new task and signing-gate task without overflow/errors, and
serve the proof byte-identically. Captures were reviewed; the mobile capture
contains the existing focused skip-link overlay, so clean visual/accessibility
acceptance is not claimed. Owned browser contexts are closed.
The original **04:53:22–05:23:22 UTC on 3 November** clock includes discovery,
implementation, review, verification, rollout, restoration and closure.

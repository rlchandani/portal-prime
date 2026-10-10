# Preserve the pinned app beneath the screensaver

## User outcome
Open an app, navigate to the desired page, reveal the system top bar and pin the
current session. Immortal photos may cover it. Tapping the frame reveals that
existing session without an app launch intent, dashboard URL, task movement or
second WebView. Home or deliberate app navigation releases the pin.

## Architecture
Use the provisioned BarWatch accessibility service and its metadata-only window
reader. Keep a runtime package/window identity, never persisted task IDs. Normal
PhotoDreamService dismissal bypasses the configured dismiss-app launcher while
pinned. Its force-wake recovery uses the existing PhotoFrameController hosted as
a nonfocusable TYPE_ACCESSIBILITY_OVERLAY, rather than bringing Home's Activity
task forward. A deliberate screensaver start while pinned uses the same overlay.

The overlay intercepts its tap and immediately hides its own view. Captured, bounded
retirement work then stops the renderer and removes that same WindowManager view;
failed removal remains tracked and prevents a second cover from accumulating. All state/navigation happens on main; the normal wake path does no
network request, browser launch, photo decode or shell call. Only one frame overlay is owned.
Native Dream attachment retires a prior continuation overlay. Navigation and
service unbind clear ownership and stop the overlay. Android's presence/sleep and
overnight policy retain authority; a pin must not create an overnight wake loop.

Window inspection reads package, id and focus/active metadata only, recycles every
pooled node/window and keeps no content or URLs. A changed observed target window
invalidates the session. A lost session stays explicit in the native Dream or continuation curtain: go Home to
reopen deliberately, never silently relaunch. Occlusion during a native Dream is
not itself evidence of destruction.

## Limits
Android may kill/recreate an external activity and Chromium may reload its renderer
independently. A normal launcher cannot forbid these. This feature promises no
Immortal-induced relaunch, not external process immortality. Pin state expires on
Immortal restart or loss of accessibility ownership. Other explicit app navigation
releases the pin; this is not Android screen-pinning/kiosk lock task mode.

## Acceptance evidence
Unit coverage for identity transitions, pooled objects, bypass/ownership and failure
cleanup; synthetic runtime fixture for retained page state and intercepted taps;
both Portal hardware paths including forced-wake recovery; generated browser
fixture with per-document boot identity and HTTP load counts; repeat wake latency
observations and stopped-resource checks. Do not export household content or claim
API acceptance alone proves visible recovery. Keep unmet hardware checks explicit.

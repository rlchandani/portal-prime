# Retained preview host suspension

The preview may survive beneath QuickBar's opaque AppSwitcher and external apps. Its terminal `stop()` destroys the WebView, loader and executors, so cover/reveal must use a reversible host state.

- Preserve user pause independently of host visibility. Bind activity start/stop and screen power to host activity, without focus heuristics or Meta screensaver detection.
- Share a thread-safe pausable callback queue with the native loader. Freeze remaining dwell/refresh delays; retain late decode delivery in the existing budgeted slot. Terminal close rejects late posts.
- Suspend native motion/dissolve, legacy fades, bundled HTML engine and per-view WebView lifecycle without reloading the document. Reconcile page completion with current host state.
- Suspend clock/weather/now-playing UI ownership independently; refresh current time on return.
- Verify pure callback races and remaining delays, real generated-media AppSwitcher cover/Back retention, user pause, engine identity and terminal cleanup. Build/unit and generated-media emulator checks precede test deployment; both-room navigation/pin regression checks then verify the installed candidate before finalizing the change.

Scope: this slice covers stopped/power-off preview hosts. Native Dream coverage without an activity stop, camera stream suspension and synthesized ambient-audio suspension require separate ownership verification. Arbitrary web-page JS is not process-wide frozen; only owned engine JS is paused explicitly. No blanket `pauseTimers()` is allowed.

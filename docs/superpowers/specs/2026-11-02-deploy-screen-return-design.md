# Explicit screen return after fleet replacement

Family's pre-install and post-install captures contain the same Android Home
chooser activity record. Current preferred-activity XML is empty, and resolving
MAIN/HOME returns ResolverActivity. Replacing the foreground Immortal package
uncovered a chooser already underneath it; creation by the update is disproved.
The origin of the absent default remains unknown.

Provide an optional `room-fleet.py deploy --after-install leave|home|screensaver`.
Default `leave` preserves existing behavior. The operator selects a return screen;
the tool must not infer an owner preference or assign Android's Home default.
Only the running agent's own package supports this return. Refuse unsupported
targets before installation using an exact NUL-terminated `/proc/self/cmdline`
identity; an APK mapped for resources alone is insufficient.
After the existing exact installed-APK verification,
send one existing fleet-token-authenticated `POST /remote/key` action.

Recognize only HTTP 200, literal `ok:true`, the requested action, and the native
dispatch contract: `dispatched:true` for Home; `result:legacy_frame_requested` or
`result:shown` for screensaver. This proves a request, not visual acceptance.
Bound the request to ten seconds and 64 KiB; never retry a navigation whose
completion may be unknown. A retained-socket watchdog bounds slow HTTP framing,
not just idle intervals. On return failure, preserve the installed verdict,
report screen return unconfirmed, continue other rooms, and exit nonzero.

No APK, setting, paired-session credential, default preference, accessibility
reconnect, UI automation, Calls handoff, or automatic OTA recovery change.
Verify Family's actual replacement and screen separately. Living receives no
duplicate functional QA. Retain the original 22:26:30.941464–22:56:30.941464 UTC
issue clock, including cleanup. The implementation choices above are the
coordinator's narrow operational mitigation, not a claimed Android default fix.

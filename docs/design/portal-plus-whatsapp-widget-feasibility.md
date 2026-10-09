# Portal+ WhatsApp widget feasibility

Investigated October 8, 2026 on the connected first-generation Portal+, Android 10/API 29,
2160 × 1440. Branch: `codex/portal-whatsapp-widget`, based on the preserved companion API
commit `7435aa2`. This is an investigation report, not an implemented calling widget.

## Required experience

The user manually adds people to a Home widget in Immortal. Tapping a person opens an
Immortal confirmation with Audio and Video choices. Confirming starts the chosen native
WhatsApp call without showing or navigating through Meta Home. Preserve the working native
WhatsApp setup and the existing launcher, photos and companion pairing.

The user initially had not tested calling recently, then confirmed during this investigation:
“I just tested it - it works.” Treat that as confirmation that native calling currently works;
it does not validate direct initiation from Immortal.

## Installed native interface

| Package | Installed version | Relevant interface |
| --- | --- | --- |
| `com.facebook.aloha.app.whatsapp` | `531.0.0.0.4982` | Exported `com.facebook.alohacommon.pef.RootActivity`, action `com.facebook.aloha.calling.START_CALL` |
| `com.facebook.alohaapps.contacts` | `1159.0.0.2.59` | Contact-card action `com.facebook.alohaapps.contacts.VIEW_CONTACT_V2`; `portal://contacts/` routes |
| `com.facebook.alohaapps.launcher` | `1160.0.0.0.5` | Exported Meta Home with `portal://launcher` routes |

Public package manifests and application bytecode were inspected locally. No native account
storage or contact databases were read. APK copies and raw device observations remain in
private temporary storage, outside Git.

The exported WhatsApp activity is insufficient for third-party calling. Its calling root
checks the incoming intent with a start-call IPC permission verifier **before** initializing
an outgoing call. Rejection follows its “Caller is not verified!” branch and stops the
calling root. The associated permission identifier is
`com.facebook.aloha.app.whatsapp.fbpermission.START_CALL`. Normal Meta calls use its trusted
intent dispatch, including a Parcelable `call_parameters` object. This is not a documented,
unrestricted phone-number/audio/video URL interface.

Contacts independently checks a trusted caller in `BaseContactsActivity`. The main contacts
screen requires `com.facebook.aloha.contacts.fbpermission.LAUNCH_CONTACTS`; the contact card
requires `com.facebook.aloha.contacts.fbpermission.VIEW_CONTACT`. A failed check finishes the
activity. This agrees with the earlier [Calls handoff investigation](portal-plus-calls-handoff.md).

Meta Home’s examined `portal://launcher` handler selects a Home tab from the last path
segment. It does not, in that handler, accept a recipient and initiate a call. Do not present
`portal://launcher/home` as a direct-call implementation.

## Other native routes checked

Android Telecom lists WhatsApp’s account as **self-managed**, with video capability and the
`tel` scheme. Its account is owned by Meta’s WhatsApp package. It is not a selectable managed
system-dialer account that Immortal can drive as a generic telephone service. Android’s
[self-managed ConnectionService contract](https://developer.android.com/reference/android/telecom/TelecomManager#placeCall(android.net.Uri,%20android.os.Bundle))
uses the service’s own account and `MANAGE_OWN_CALLS`; that permission does not transfer
ownership of Meta’s account to Immortal.

Android’s shortcut service publishes no shortcuts under either the native WhatsApp package
or the Contacts package on this device. There is therefore no existing WhatsApp audio/video
contact shortcut available for Immortal to launch through `LauncherApps`.

Two no-recipient activity launches were attempted. Neither provided a working call screen
or a verified direct-call path. No contact was selected and no call was placed by the agent.
Release logging did not expose the exact runtime rejection reason, so the launches alone
are not claimed as proof of a particular error. The caller restriction above is established
from the installed code, not inferred solely from the unsuccessful empty intents.

## Recommendation and limits

**Do not implement the requested exact flow against the currently installed native calling
interface without a supported caller route.** A widget that only opens Meta Home would not
satisfy the request. Adding a manifest permission cannot supply Meta’s trusted signing
identity. Guessing phone-number extras or claiming an outgoing call after activity dispatch
would also be incorrect.

The candidate alternative is a separate official WhatsApp linked client. First validate
Portal camera/microphone access, account linking, audio/video calling, entry points and
return-to-Immortal behavior. It may avoid Meta Home while still requiring a final call tap
inside WhatsApp. That remaining tap must be disclosed; the requested one-confirmation flow
is **not verified**. Installing/linking a separate client is pending the user’s preference.
Do not replace, clear, unlink or uninstall the working native client.

[WhatsApp click-to-chat](https://faq.whatsapp.com/5913398998672934) opens a conversation;
it is not a per-person audio/video-call API.
[Call links](https://faq.whatsapp.com/456694046556486/) let participants join a shared call;
they do not ring a saved person in the requested way.
[WhatsApp Web calling](https://about.fb.com/news/2026/07/whatsapp-web-calling-new-features/)
is now documented by Meta, but browser compatibility and Portal media permissions must be
validated separately. General web support is not proof that Portal’s existing browser can
provide this experience.

No contacts widget, native IPC workaround, UI automation or calling dependency was added
in this investigation. Existing Meta account and calling settings remain untouched.

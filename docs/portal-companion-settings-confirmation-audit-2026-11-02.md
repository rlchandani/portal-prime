# Phone settings confirmation — source audit

**Source findings only; no correction or runtime reproduction yet.**
Toolkit HEAD verified as `6bf11bf1d35cffdab43a2c766fd57ae0595fef6f`.
Current Portal source is unchanged from1.82; the Quick controls mock is separate.

The existing settings response has literal `ok:true`, `applied` keys and an
actual serialized `domain` object. Two phone paths can accept weaker replies:

| Finding | Current source | Required next evidence |
| --- | --- | --- |
| Single-room confirmation ignores returned domain | `CompanionRepository.writeSettings`, line438, only checks applied keys | Reproduce a matching-key acknowledgment naming another domain |
| Group confirmation substitutes the requested domain when identity is missing | `CompanionGroup`, line261 | Reproduce absent/malformed object or ID |
| Success flag is coerced | `CompanionClient.executeResponse`, line230, uses `optBoolean` | Reproduce string `"true"` with otherwise matching settings acknowledgment |

Portal response: `RemoteRoutes.kt:497`. Existing matching group fixture at
`CompanionGroupTest.kt:463` omits domain identity and will need to represent
the actual wire contract if that guard is implemented.

The independent reviewer inspected source only. The coordinator checked the
current commit and relevant caller/response code. This does not establish that
the installed Portal normally emits malformed responses, or prove a runtime fix.
Missing applied keys and explicit401 already reject.

Next: focused single/group regression reproduction, shared confirmation guard
if reproduced, then relevant JVM/build and isolated rendered companion checks.
Preserve explicit rejection, partial-key handling, session/room ownership,
cancellation and existing error presentation. Do not automatically replay an
uncertain settings write or alter the Portal schema.

For the half-hour rule, use conservative first-audit start
**19:08:00UTC**, retaining **19:38:00UTC** as the hard deadline including
cleanup. Implementation must not silently reset this issue's clock. If that
window expires before a verified correction, park it and choose independent work.
No phone or Portal code, preferences, API state or APK changed in this audit.

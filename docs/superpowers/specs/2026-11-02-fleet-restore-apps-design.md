# App restore selection and confirmation

Original issue clock: **2026-11-02 23:15:37–23:45:37 UTC**.

The original restore selected an app marked not installed, omitted the installed
app and reported done after its install reply was `ok:false` with process exit
zero. The synthetic baseline reproduces both errors without device writes.

Scope is the host's app-restore path: validate the complete app plan using the
existing snapshot JSON helper, select independently of formatting/key order,
exclude both launcher variants and require exact literal installation success
followed by installed presence readback. Stop on unconfirmed results without
retrying or entering later preference writes. Dry-run makes no Fleet calls.
The shared restore entry boundary also resolves exactly one safe device selector
locally; `all`, unknown or ambiguous serial/name matches cannot fan out any mode.
Duplicate JSON fields are rejected because the CLI and Python otherwise disagree
about their values. A real run freezes the complete selected record in a private
registry, removed on exit, so subsequent CLI reloads cannot expand its targets.

Raw preference restoration, reboot, config XML parsing, package migration and
full native restore acceptance remain separate. Family validation is read-only:
build and compare a dry-run plan from its actual current catalog metadata.
No owner app installation, data restore, APK change, Living native QA or publication.

Acceptance: malformed/missing/unsafe metadata fails before mutation; empty plans
succeed; duplicate packages install once; positive results require literal success,
exact package and terminal installed result plus readback; uncertain replies,
process errors or absent readback fail truthfully. Preserve current room state.

# Companion photo-upload consistency — 2 November 2026

The phone now requires literal success, the exact destination/file and matching
numeric bytes before confirming an image upload. Unreadable positive replies
and transport loss remain **Unconfirmed**, separate from Failed. Retry failed
only excludes uncertain uploads, preventing accidental duplicate sends.

Confirmed results survive local import cleanup exceptions, with a cleanup notice.
Single-room uncertainty also survives a failed subsequent photo-list refresh.
Existing rejection,401, cancellation, streaming and captured-room retry behavior
are preserved. No Portal API or installed Portal software changed.

| Target | Verified status |
| --- | --- |
| Reproduction | Eleven initial methods, nine old failures; review found two additional corner defects |
| Companion local gates | **590 JVM /76 suites**, zero failures/errors/skips; app/test builds passed |
| Actual rendered fixture | **7/7 in14.901 seconds**: three upload plus four retained deletion cases |
| Enlarged-text upload fixture | **3/3 in8.765 seconds**,130% text,360×800dp |
| Companion candidate | **1.7.3-music-preview /code15**, signed, full installed emulator APK matches |
| Physical Pixel | Installation and live-network acceptance remain open |
| Family and Living | Exact Portal1.82 APKs and complete configuration retained; zero writes/installs |
| QA cleanup | Owned emulator/clone removed; original AVD contents and metadata unchanged |

The three upload cases exercise the real group panel/repository/client with
generated PNGs and an in-memory transport. They verify wrong-path uncertainty,
an explicit409 followed by the actual failed-only retry, and unreadable positive
reply handling. Prepared streams are opened, closed and disposed once. The JVM
checks include mixed three-room outcomes and cleanup/refresh exceptions.
This does not establish whole-app snackbar, all-screen visual, physical phone,
real-network stream/latency, playback, cold reconnect or FPS acceptance.

Review regressions failed before correction. Two later full-gate failures came
from looped test scenarios sharing a cancelled scope; owned child scopes corrected
the fixture. Final gates passed, and fresh independent source review found no
remaining concrete regression. Source inspection does not prove fresh
auth/cancellation races. Cleanup-exception checks prove attempted disposal and
retained outcome, not successful disposal when the callback throws.

App **15,374,817 bytes**, SHA256:

`2c3361e926d6659e83eac51a60d7c51d81a80fa9503aac28fae7cb1e46c83436`

Signer:
`4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6`

Companion commit: `6bf11bf1d35cffdab43a2c766fd57ae0595fef6f`.
Both repositories use isolated `codex/companion-photo-upload-20261102` branches.
There was no public release, GitHub authentication or HA action.

Source receipt:
`~/.codex/worktrees/portal-companion-compat-20261102/portal-toolkit/docs/companion-photo-upload-validation-2026-11-02.md`.
Private artifacts/gates/native/retention/cleanup receipts:
`/private/tmp/portal-companion-photo-upload-20261102/`.
The unit starts18:35:48UTC and retains its19:05:48UTC hard deadline.
No automatic replay or new polling was added; no general speedup is claimed.

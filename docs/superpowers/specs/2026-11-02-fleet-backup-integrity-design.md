# Truthful private fleet preference backups

Original issue clock: **2026-11-02 22:56:14–23:26:14 UTC**, including cleanup.

The original command reports one device backed up and publishes `latest` after
`ls` exits zero with `ok:false`; zero preference files were captured. A successful
process exit is insufficient evidence of a usable backup.

Scope: host Bash backup plus a small Python 3 validator, offline end-to-end
fixtures, the existing provisioning CI gate, documentation and read-only Family
acceptance. No Android changes, restore operation, default Home selection,
GitHub publication or Living native QA.

Acceptance:

- Require literal JSON success, complete app identity/install markers, exact
  preference directory and a nonempty safe readable XML preference list.
- Pull and validate every listed XML map before publishing.
- Create private, owned directories; reject symlink or obstructed parents.
- Keep previous snapshot bytes and `latest` on capture/publication failure.
- Publish uniquely named completed folders and replace the pointer atomically.
- Continue other rooms and exit nonzero when any room fails.
- Treat publication as the commit point; output cannot reverse that verdict.
- Verify real Family capture without settings/layout/source changes.

This is not a whole-app backup, power-loss durability guarantee, restore
acceptance or an atomic snapshot of preferences changing during capture.
The optional debug package path does not extend the release-only raw restore.

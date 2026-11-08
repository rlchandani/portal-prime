# Wireless (OTA) updates via GitHub Releases — Hearthpane + Portal Toolkit

Decision (2026-11-08, owner): updates come from **GitHub Releases** of the public repos, like the
Hearth app's flow but without Home Assistant. After one last cable install, every future update is
wireless.

## Constraints

- Installed packages: Portals run `com.immortal.launcher.debug` (performance build type); the phone
  runs `com.portalinstaller` (debug build type). Both are signed with the same certificate,
  SHA-256 `4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6`. Every OTA build must
  be signed with that key, or Android rejects it. The keystore is backed up privately; it is never
  committed and never uploaded to CI.
- Version codes only go up (Android rejects downgrades).
- Portals: Android 10, no Play services, the installer dialog is auto-confirmed by the app's own
  `InstallConfirmService` (armed per install by `HeadlessInstaller`). Never force-stop the launcher.
- Phone: Android 15. `PackageInstaller.SessionParams.setRequireUserAction(USER_ACTION_NOT_REQUIRED)`
  lets updates after the first one install without a prompt. "Install unknown apps" must be allowed
  once for Portal Toolkit.

## Feed

One GitHub release per app version, published by a local script (no CI signing):

| App | Repo | Tag | Assets |
| --- | --- | --- | --- |
| Hearthpane | `rlchandani/portal-prime` | `hearthpane-v<versionName>` | `hearthpane.apk`, `hearthpane-latest.json` |
| Portal Toolkit | `rlchandani/portal-toolkit` | `toolkit-v<versionName>` | `portal-toolkit.apk`, `portal-toolkit-latest.json` |

Apps read `https://github.com/<repo>/releases/latest/download/<app>-latest.json` (each repo has only
these releases, marked "latest").

```json
{ "schema": 1, "packageName": "com.immortal.launcher.debug", "versionCode": 223,
  "versionName": "1.229", "sha256": "<hex of the APK>", "size": 12345678,
  "apkUrl": "https://github.com/<repo>/releases/download/<tag>/<app>.apk",
  "minSdk": 24, "notes": "One-line what's new" }
```

## On-device checks before installing

1. `packageName` equals our own package and `versionCode` > installed.
2. `apkUrl` is https and on `github.com` / `objects.githubusercontent.com` (redirect target).
3. Downloaded file's SHA-256 equals `sha256`, and its size equals `size`.
4. `PackageManager.getPackageArchiveInfo` on the file: same package name and version code as the
   manifest, and its signing certificate SHA-256 equals the pinned digest above.
   (Android also enforces the signature; the app check gives a clear error instead of a failure.)

## Hearthpane (Portal)

- Revive/replace the unused `UpdateManager` with a small `OtaUpdater` (pure, unit-tested decision
  logic + thin Android I/O). Feed URL constant; overridable in dev mode only.
- When: check at start, then every 6 h. Install automatically only when the Portal is idle: no
  music playing, no call, no routine on screen, not during the user's active use (no touch for 10 min),
  preferably inside quiet hours / 02:00–05:00. A "Check for updates / Update now" row in Settings
  (shows current version and the last check result) installs immediately.
- Install with `HeadlessInstaller` (auto-confirm armed). After the update the app restarts itself;
  Home stays the default home app.
- Fleet: `GET /update/ota` (state) and `POST /update/ota` (check now / install now) for fleetctl.
- Paired phone: expose OTA state in `/remote/companion` or a feature route so Portal Toolkit can show
  "Hearthpane 1.229 available on Living · Update now".

## Portal Toolkit (phone)

- `OtaUpdater` with the same checks; WorkManager periodic check daily + on app open.
- Notification "Portal Toolkit X is available" with Update; Settings row "Portal Toolkit X · Check
  for updates". Download in a foreground worker, install via `PackageInstaller` session with
  `USER_ACTION_NOT_REQUIRED`; handle `STATUS_PENDING_USER_ACTION` (first time) by launching the
  confirm intent; ask for "Install unknown apps" when `canRequestPackageInstalls()` is false and
  resume on return.
- Show Portals' Hearthpane update state on each Portal card with "Update now".

## Release scripts (local)

`tools/scripts/ota-release.sh` in each repo: clean tree on the release branch, version bumped and
higher than the published one, gates (tests, build, lint), `apksigner verify --print-certs` digest
equals the pinned digest, compute sha256/size, write manifest, `gh release create` with both assets
(`--latest`), then download both URLs back and re-verify. Never prints secrets.

## Rollout

1. Build OTA-capable versions, install by cable once (Pixel by USB, Family by USB, Living by fleet).
2. Publish a test release with a higher version; confirm each device updates by itself.
3. From then on: bump → `ota-release.sh` → devices update wirelessly.

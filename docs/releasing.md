# Releasing

Immortal is hosted from its own GitHub repo. Two files drive what devices see.

## Check local readiness without publishing

Use the separate local checker when building or verifying a candidate:

```sh
python3 scripts/portal_release_readiness.py \
  --baseline-apk /path/to/previous-accepted.apk \
  --variant performance \
  --sdk "$ANDROID_HOME" \
  --output app/build/release-readiness/my-candidate
```

The baseline must be a trusted, signed local APK of the **same package**.
`release` (the default) expects `com.immortal.launcher`; `performance` and `debug`
expect `com.immortal.launcher.debug`. A missing baseline or changed certificate
fails; there is no first-release exception. This command does not read a keystore
or require a GitHub connection.

The checker runs version sync, slideshow/bundled JavaScript tests, Portal Python
tests, fresh JVM tests and the selected APK build with offline Gradle. It verifies
the built package/version and all signing digests, records source/build-input and
APK hashes, and copies the inspected APK to `candidate.apk`. Different before/after
source-input snapshots, or changed baseline/artifact bytes, invalidate readiness.
It explicitly requests TAP output rather than depending on Node's default reporter.

Before promoting `candidate.apk`, the checker also compares every file under
`app/src/main/assets` with its packaged `assets/` entry. Missing or duplicate
entries, different sizes or bytes, and unreadable contents fail readiness.
`sourceAssets` in the receipt records each relative path, size and SHA256.
This covers the main source assets; extra library or generated assets and
Android resources remain outside this comparison.

Python 3.12+, Node 22+, the Android SDK's `aapt`/`apksigner` and cached Gradle
dependencies are required. The checker currently runs on macOS/Linux; Windows
provisioning checks remain separate. The selected SDK is passed to the build.
Missing offline dependencies fail honestly. Prior JVM XML reports are archived
in the output before the forced test task, so they cannot satisfy a fresh check.
`--timeout-seconds` controls the whole check (default 900, maximum 1800);
timed-out command groups are stopped. Output must be a **new directory**, either
outside the repository or under `app/build`. Existing evidence is never overwritten.

Inspect `receipt.json` and its named gate logs. `status: "ready"` means **local
artifact readiness**, with `published: false` and `deployed: false`; it does not
prove clean-main/tag eligibility, hosted CI, provisioning compatibility, phone or
hardware acceptance. After output creation, a failed check writes
`status: "failed"` and returns nonzero. Preflight refusals preserve existing files.
No source bump, authentication, Git fetch, tag, push, publication or device install
is performed. The publishing workflow below remains a separate explicit operation.

### Check JavaScript changes independently

For frontend changes, reuse the same slideshow and bundled-remote gates without
waiting for Android setup:

```sh
python3 scripts/portal_release_readiness.py \
  --javascript-only \
  --output app/build/javascript-checks/my-change
```

This mode needs Python 3.12+, Node 22+ and macOS/Linux, with a new output directory.
It runs both existing JavaScript selections using TAP, preserves their exit
statuses and logs, and checks that source inputs stayed unchanged during the run.
The same whole-command timeout and process cleanup apply. No SDK, baseline APK
or synchronized release manifest is required.

A successful receipt says `scope: "local-javascript-checks"` and
`status: "checked"`, with no APK or JVM result. Run the full readiness command
before promoting an APK; this check establishes only the JavaScript gate results.

## Check provisioning scripts locally

Run `scripts/check-provisioning.sh` before packaging the provisioning kit. It
checks Bash syntax and the existing ASCII rule for `provisioning/provision.ps1`,
`provisioning/*.bat` and `scripts/test-provision-access.ps1`; UTF-8 `provision.sh`
remains allowed. PowerShell parsing runs when `pwsh` is installed and otherwise
reports `SKIPPED`. That skip does not prove Windows compatibility: CI separately
parses with Windows PowerShell 5.1. This local command does not provision devices
or publish a release.

## Cutting a release (the one safe command)

```sh
scripts/cut-release.sh <versionName> "<release notes>"
# e.g.
scripts/cut-release.sh 1.44 "Adds the home-header remote button and a tidier remote layout."
```

That single command does the whole release and **fails fast at every gate**, so the two
things that historically broke releases — forgetting the `version.json` bump, and misnaming
the APK — can't happen:

1. **Preflight** — `gh` authenticated; a clean tree on an up-to-date `main`; the signing key
   present; Android build-tools found; the tag still free. Tracked changes and nonignored
   untracked files must be committed or removed before releasing; ignored signing keys and
   build outputs remain allowed.
2. **Bumps `versionCode` (auto, current + 1) / `versionName` / `notes` in both
   `app/build.gradle.kts` and `version.json` together**, then re-checks they agree.
3. **Builds the signed APK and verifies it** — `aapt` confirms the APK's version matches what
   was just written (no stale build); `apksigner` confirms it's signed with the **same key as
   the currently-published `immortal.apk`** (a different key silently breaks self-update).
4. **Builds `portal-kit.zip`** from the committed `provisioning/` tree (`git archive` — only
   tracked files, never local junk or secrets).
5. Commits, tags, pushes; creates the Release as a **draft**, uploads exactly **`immortal.apk`
   + `portal-kit.zip`**, then publishes (drafts aren't "latest", so `latest/download` keeps
   resolving to the previous release until the new assets are in place).
6. Verifies both `latest/download/` URLs resolve **and** the published `immortal.apk` reports
   the `versionCode` shipped.

Requires `gh` (write access), the Android SDK build-tools, and `keystore.properties` (see
[Signing](#signing)). A release attaches exactly two assets: `immortal.apk` and
`portal-kit.zip` — nothing else, no versioned APK copies.

The currently published `immortal.apk` is a mandatory signing baseline. HTTP,
DNS, timeout or TLS failures stop the release before packaging, commit, push or
publication; they never skip the signing-key comparison. Restore access to the
published asset and retry. There is no automatic first-release exception.
The version bump and local build happen before this gate, so inspect those local
changes after a failure before starting another release attempt.

!!! note "Drift is blocked in CI too"
    [`release-guard.yml`](https://github.com/starbrightlab/immortal/blob/main/.github/workflows/release-guard.yml)
    runs `scripts/check-version-sync.sh` on every change to `version.json` or
    `app/build.gradle.kts`: if their `versionCode`/`versionName` disagree (or `apkUrl` isn't
    the stable URL, or `notes` is empty), the check fails before it can reach a release.

## `version.json` — the self-update manifest

Immortal polls
[`version.json`](https://github.com/starbrightlab/immortal/blob/main/version.json); when it
advertises a higher `versionCode`, the device downloads and installs the new build over itself
(`UpdateManager`). No cable, no laptop.

It advertises the build by `versionCode`, points devices at the stable
`releases/latest/download/immortal.apk`, and carries the release `notes`. Don't hand-edit it to
cut a release — [`cut-release.sh`](#cutting-a-release-the-one-safe-command) writes it (in lockstep
with gradle) and the release guard keeps it honest.

!!! danger "The release asset **must** be named `immortal.apk`"
    The manifest's `apkUrl` (and the store catalog) point at the stable
    `releases/latest/download/immortal.apk`. If a release attaches only a versioned name, that
    URL 404s and **breaks self-update for every device** — which is exactly what happened once
    (a release shipped only `immortal-1.42.apk`). `cut-release.sh` always uploads the asset as
    `immortal.apk` and then verifies the URL resolves, so this can't recur.

## `catalog.json` — the app-store catalog

The [App Store](features/app-store.md) reads
[`catalog.json`](https://github.com/starbrightlab/immortal/blob/main/catalog.json). Edit and
commit; clients pick it up on next open (a bundled copy ships as the offline fallback). Every
PR that touches it is validated by CI.

## Signing

Release builds must be signed with the **same** key every time — in-place self-update is
signature-checked, so a different key means devices can no longer update.

Signing is configured via `keystore.properties`, which the build looks for first at the repo
root (git-ignored) and then at `~/.immortal-signing/keystore.properties` — the recommended home,
since nothing in a git working tree can be considered safe from cleanup.

!!! warning "Back up the signing key"
    Keep the key backed up safely (e.g. iCloud). **Losing it means devices can no longer
    self-update.** CI guards against publishing an *unsigned* release, but it can't recover a
    lost key.

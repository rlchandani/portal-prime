# Portal Python and JavaScript regression gates — 2026-11-02

`.github/workflows/tests.yml` now runs the complete Portal Python regression suite
and all 29 application JavaScript test modules before Gradle, retaining the earlier
slideshow tests. It pins Node.js 22 and Python 3.12 and prepares `/private/tmp` on
the ephemeral Ubuntu runner, preserving the production profile tooling's deliberate
containment contract and all existing test files. Actual hosted Ubuntu execution
with these runtime versions is pending.

## Python discovery and local result

From the repository root:

```sh
python3 -m unittest discover -s scripts -p 'test_portal_*.py'
```

The local run after the workflow edit used macOS and Python **3.13.7**, with
`PYTHONDONTWRITEBYTECODE=1` in the environment to avoid writing Python caches into
the checkout. It exited **0**:

```text
Ran 140 tests in 0.804s

OK
```

There were no failures, errors, or skips. The count is unittest test cases;
individual `subTest` iterations are not additional cases.

All seven files matching `scripts/test_portal_*.py` were included:

| File | Cases | Regression guard |
| --- | ---: | --- |
| [`test_portal_home_preflight.py`](../scripts/test_portal_home_preflight.py) | 16 | Owned, idle Home preparation; one dispatch; deadlines and destination proof |
| [`test_portal_install_observation.py`](../scripts/test_portal_install_observation.py) | 23 | One install attempt; uncertain ACK observation; exact artifact/readiness checks; deadlines |
| [`test_portal_native_geometry.py`](../scripts/test_portal_native_geometry.py) | 21 | Visible, unambiguous action targets; observed dock/rail geometry; malformed bounds |
| [`test_portal_navigation_observation.py`](../scripts/test_portal_navigation_observation.py) | 26 | Window ownership, transition gaps, destination stability and deadline enforcement |
| [`test_portal_profile_equivalence.py`](../scripts/test_portal_profile_equivalence.py) | 28 | Canonical ART inventories, DEX checksums, flags and counts; malformed proof rejection |
| [`test_portal_profile_validation.py`](../scripts/test_portal_profile_validation.py) | 16 | Workspace/evidence boundaries, input hashes, profile format and subprocess cleanup |
| [`test_portal_warm_dream_contract.py`](../scripts/test_portal_warm_dream_contract.py) | 10 | Synthetic activity-dump parsing; exact package identity and live Home/preview counts |
| **Total** | **140** | |

## Application JavaScript gate and local evidence

The added step runs the exact command:

```sh
node --test app/src/test/js/*.test.mjs
```

Inspection confirmed **29 matching modules**. Main reported a complete local run
on **Node.js 24.13.0**: **652 passed, 0 failed, 0 cancelled, 0 skipped**, in
**14.03 seconds**. This is main's supplied execution evidence; the unchanged full
suite was not rerun during this CI edit.

The modules cover offline remote-control, photo, navigation, settings, music and
screensaver duplicate-avoidance contracts. The shared
[`remote-browser-harness.mjs`](../app/src/test/js/remote-browser-harness.mjs) loads
the shipped controller into a synthetic DOM using `node:vm`, supplies fixture
responses through a replacement fetch function, and controls browser timers.
Imports across the modules and harness are limited to repository code and built-in
`node:test`, `node:assert/strict`, `node:fs`, `node:path`, `node:url`, and `node:vm`.
The inspected test-context methods (`after`, `diagnostic`), VM functions, file/URL
reads, and web globals (`Response`, `Blob`, `DOMException`, `AbortController`,
`structuredClone`, `URL`, `queueMicrotask`, `setImmediate`) are supported by Node
22. No Node 24-only API or third-party harness dependency was identified in the
modules, harness or loaded assets.

`screensaver-dedup.test.mjs` uses its own assertion wrapper and exits nonzero on
failure; Node's runner includes that script as a file-level test rather than
counting every internal check separately. The reported 652 is the Node runner's
count, not an application-coverage percentage.

Node setup precedes both JavaScript steps:

```yaml
- name: Set up Node.js 22
  uses: actions/setup-node@v4
  with:
    node-version: '22'

- name: Test slideshow sources and labels
  run: node --test scripts/test_slideshow_sources.mjs scripts/test_slideshow_labels.mjs scripts/tests/slideshow_native_filmstrip.test.cjs

- name: Test remote and screensaver JavaScript regression guards
  run: node --test app/src/test/js/*.test.mjs
```

The action and version match `.github/workflows/provisioning.yml`. The existing
slideshow command is retained exactly. No npm install, lockfile, browser, device,
or network access is needed for the test harnesses. Local Node 24 results and API
inspection do not establish execution on hosted Node 22; that run is pending.

## Runner prerequisite and workflow change

The workflow runs on `ubuntu-latest`. Fifteen of the sixteen
`ProfileValidationTest` cases call
`tempfile.TemporaryDirectory(dir="/private/tmp")`.
[`reserve_workspace`](../scripts/portal_profile_validation.py) also deliberately
requires the tool directory to be a child of `/private/tmp`. Setting `TMPDIR`
alone would not fix these explicit paths.

`/private/tmp` is a macOS filesystem convention, not a standard Ubuntu temporary
directory. The original workflow did not create it. To check the failure mode
without modifying the filesystem, a local, in-memory simulation intercepted
`tempfile`'s directory creation below `/private/tmp` and raised the
`FileNotFoundError` expected when that parent is absent. The unchanged
profile-validation module then ran **16 cases: 15 errors, 0 failures, 0 skips**.
The remaining case, snapshot-metadata relocation, does not allocate that
directory. This was an environment simulation, **not an actual Ubuntu CI run**.

After the JavaScript steps, the workflow adds these three Python steps immediately
before the existing Gradle test step:

```yaml
- name: Set up Python 3.12
  uses: actions/setup-python@v5
  with:
    python-version: "3.12"

- name: Prepare profile test workspace
  # Preserve the profile tooling's /private/tmp containment contract.
  run: sudo install -d -m 1777 /private/tmp

- name: Test Portal Python regression guards
  run: python3 -m unittest discover -s scripts -p 'test_portal_*.py'
```

The Python action/version matches the catalog, docs and release-guard workflows.
GNU `install -d` creates the runner directory with mode `1777`, allowing temporary
workspace creation while retaining the sticky bit. This runner preparation keeps
the existing containment checks rather than changing production or test paths.
The test step uses ordinary failure propagation; no matching files or failing
tests are excluded or ignored.

The privileged command exists only in workflow YAML and was not executed on the
user host. Its shell syntax was checked without execution:

```sh
bash -n <<'SH'
sudo install -d -m 1777 /private/tmp
SH
```

That check exited **0**. The workflow YAML also parsed successfully with the
host's existing Ruby YAML parser; no parser dependency was installed. These are
syntax checks, not evidence that runner preparation or the hosted job executed.

## Dependencies, determinism and limits

The suite imports only Python standard-library modules and repository helpers.
It needs no pip packages, device, network access, Android SDK, Gradle build,
captured APK/profile evidence, or external ART mapper/comparator tools.

Most cases use synthetic inputs and injected clocks/callbacks. Profile validation
also creates disposable files and starts child processes using `sys.executable`.
Its descendant-cleanup test uses a real 0.15-second timeout, a child with a
0.5-second delay, and a 0.6-second observation wait; another test allows three
seconds for a Python process to exit. These are host scheduling limits, so a
passing local run does not prove absolute determinism under heavily loaded CI.
Process-group cleanup requires POSIX behavior. The geometry module also permits
a `PORTAL_GEOMETRY_HELPER` environment override; this workflow does not set it.
Python 3.12 on Ubuntu was not executed here. Docker is installed, but its daemon
socket is unavailable, and Podman is not installed. No container environment was
started and no images or runtimes were downloaded.

The configured gates make existing install-observation, geometry, navigation,
profile, warm-dream, remote/photo/music and duplicate-avoidance regressions block
CI alongside Kotlin and the retained slideshow regressions. Both discovery
commands include future matching test modules automatically. These guards exercise
helper and controller contracts; they do not measure complete application coverage
or establish live native-widget, browser rendering, device lifecycle, or
installed-profile behavior. Native-widget verification remains separate.

Only `.github/workflows/tests.yml` and this assessment document were edited. No
test files, dependencies, other pipelines or application code were changed, and no
commit was created. The existing Kotlin/slideshow commands, job triggers and
test-report upload remain intact. Hosted Ubuntu/Python 3.12/Node 22 execution is
pending; native-widget verification is independent of these gates.

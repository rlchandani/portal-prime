Offline native Chrome comparison, frozen against checkout
`53319dde523da9fb4964bc378308d9d419248dc7`.

The only additions are `scripts/tests/remote_mobile_poll_rendering.test.mjs` and
this evidence file. No product assets were edited. The old controller comes from
local commit `4d512a5706c889f14ca9f3334a74fc7d8a3ca729`; both runs use the checkout's
same core module, CSS, and extracted `RemoteHtml.PAGE`.

| Source | SHA-256 |
|---|---|
| Browser test | `9568162a2fbd3bb8fa635d8495ebca8087686bf7dcfa3d5be762a6bb2efa480b` |
| Old `control-center.js` | `4965a2e7eb1b67096d6a73644e2a8fb0f8372aeee52529e85651440f7dd59aa9` |
| Current `control-center.js` | `4b1df0a4859353caa8ffe366785b69cf1d755b078ce7b3ad9abec99bb12cce43` |
| Shared `core.mjs` | `d32a5d42344810a9ebce40945131e5158f3acb7d4e56aea81e797b73107928a2` |
| Shared `control-center.css` | `b0b12874e279bf98068e3b264372c68424dbe6521966fb9743cfaad5f3930e30` |
| Shared `RemoteHtml.kt` | `8352cec12055a0b2778e81c1c2e01b107705962fae8e780ba654e6e102302c6c` |
| Served HTML, extracted and trimmed | `420c25ca591e432ebed42e8eae71b4496cdc68f90e3a722c585bc8fa6d74a6e2` |

Runtime: Node 24.13.0, bundled Playwright 1.62.1, installed Google Chrome
155.0.8059.40 at `/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`.
Each run launches a fresh ephemeral headless Chrome profile and context, with a
402 x 874 CSS-pixel viewport, device scale 1, mobile emulation, touch capability,
light color scheme, and reduced motion.

All page HTTP(S) resources are intercepted and fulfilled in memory at the fixed
generated fixture origin. Unknown requests are aborted, WebSockets are closed,
service workers are blocked, background networking is disabled, and hostname
resolution is denied. Both runs recorded zero unknown requests, zero WebSockets,
and zero uncaught page errors. There is no server, existing browser connection,
existing storage, real credential, Portal request, or household content.
Only the fixed synthetic roster marker required by the existing adapter contract
is seeded in the new context. Requests/headers, UI strings, screenshots, and DOM
dumps are never emitted.

The fixture uses 24 synthetic apps and operations plus generated 2 x 2 RGBA PNGs
(74 bytes each). Chrome loads the native blob URLs; the fixture awaits
`HTMLImageElement.decode()` and checks each icon's natural dimensions.
Readiness tracks native fetch and JSON/blob body consumption, then requires two
consecutive stable work samples and complete images, so a previous render's late
icon completions cannot leak into the next phase.

Playwright's paused clock advances the controller through exact 4000 ms periods.
The first measured poll was split into 3999 ms plus 1 ms: no early operations
request occurred in either run. All three-poll phases retained exactly three
operations reads and zero app/catalog metadata refreshes. The complete bounded
scenario advances 16 periods (64 seconds of application timer time), rather than
measuring wall-clock browser latency.

| Three-poll phase | Icon reads, old -> current | createElement calls, old -> current | Element listener registrations, old -> current | Page replacements, old -> current | Native page root retained, old -> current |
|---|---:|---:|---:|---:|---|
| Apps: unchanged empty operations | 72 -> 0 | 750 -> 0 | 99 -> 0 | 3 -> 0 | false -> true |
| Catalog: repeated identical operations error | 72 -> 0 | 897 -> 0 | 171 -> 0 | 3 -> 0 | false -> true |
| Activity: repeated identical operations error | 0 -> 0 | 75 -> 0 | 21 -> 0 | 3 -> 0 | false -> true |

In both Apps/Catalog phases, the old controller replaced the actual app-list node
and every icon node; the current controller retained the list and all 24 connected
icon nodes in order. All 24 icons remained natively decoded. Activity has no icon
list; its assertion concerns the native page-root identity.

The counters measure document.createElement calls, addEventListener calls on
elements, and replacement of `#page` children. They do not count text-node creation
or nodes parsed from SVG innerHTML, and are not heap, paint, CPU-time, or frame-rate
measurements.

Both controllers also passed these behavior checks:

| Transition | Observed result in both runs |
|---|---|
| Pending operation received while app search is focused | Page/list/icon identities retained; zero rebuild work |
| Focus leaves; the pending operation is received again | One page replacement; Running visible and Update disabled |
| First operations error on Catalog | One page replacement |
| Catalog recovers with the same operation list | One page replacement |
| Confirmed install succeeds | Finished visible; exactly one catalog refresh; two page replacements |
| First operations error on Activity | One page replacement; error UI visible |
| Activity recovers with the same operation list | One page replacement; error UI removed and Finished visible |

Install completion's two renders produced 28 icon requests in both runs (the
first four started before the catalog response replaced the intermediate page).
This existing transition behavior is unchanged and outside the idle-work
optimization being verified.

Native mobile bounds were identical for the old and current controller:

| Check | Installed Apps | Catalog |
|---|---:|---:|
| Viewport width x height | 402 x 874 | 402 x 874 |
| Document scroll width | 402 | 402 |
| App rows | 24 | 24 |
| Checked controls, including Catalog Update buttons | 24 | 48 |
| Minimum app-row left / maximum right | 38 / 364 | 38 / 364 |
| Minimum control width x height | 74.0625 x 48 | 74.0625 x 48 |
| Horizontal overflow | false | false |
| Rows and row children fit their bounding rectangles | true | true |
| Button content fits client bounds | true | true |
| Controls fully in viewport and unobstructed after scrolling into view | 24 / 24 | 48 / 48 |

Bounds come from getBoundingClientRect, client/scroll dimensions, and native
elementFromPoint hit testing at each control's center. Vertical page scrolling is
expected for 24 cards. No screenshot or DOM dump is taken.

Commands executed from
`~/.codex/worktrees/portal-remote-browser-poll/portal-prime`:

```sh
NODE_PATH=~/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules IMMORTAL_REMOTE_CHROME=1 IMMORTAL_REMOTE_SOURCE=baseline node --test scripts/tests/remote_mobile_poll_rendering.test.mjs
```

RED: exit 1, one failed test. All setup, bounds, transition, and isolation assertions
passed before the intentional idle-work failure `72 !== 0`. The diagnostic also
recorded false native app-list and icon identity retention.

```sh
NODE_PATH=~/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules IMMORTAL_REMOTE_CHROME=1 IMMORTAL_REMOTE_SOURCE=candidate node --test scripts/tests/remote_mobile_poll_rendering.test.mjs
```

GREEN: exit 0, one passed test, no failures or skips.

```sh
env -u IMMORTAL_REMOTE_CHROME -u IMMORTAL_REMOTE_SOURCE node --test app/src/test/js/*.test.mjs scripts/test_slideshow_sources.mjs scripts/test_slideshow_labels.mjs scripts/tests/*.test.mjs
```

Plain Node gate: exit 0, 111 passed, zero failed, one skipped. Without the explicit
browser opt-in the new test reports its skip reason and never loads Playwright or
launches Chrome.

```sh
node --check scripts/tests/remote_mobile_poll_rendering.test.mjs
git diff --exit-code HEAD -- app
git diff --check
```

All exited 0. Headless Chrome required the authorized sandbox exception to launch;
the same interception and profile isolation were used for both successful fixture
runs.

This provides real desktop Chrome DOM, layout, native image-decode, and object
identity evidence for synthetic mobile-viewport content. It does not establish
Android phone FPS, INP, touch latency, physical mobile acceptance, or behavior
with real app catalogs or device/network latency. No hardware or Gradle was used.

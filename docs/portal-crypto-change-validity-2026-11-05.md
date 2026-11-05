# Crypto percentage validity

A numeric overflow in an optional crypto percentage could reach the Home widget
as `+Infinity%` or `−Infinity%`. The parser now admits only finite percentages;
unavailable values use its existing null path. Prices, configured labels,
sparklines, quote order, defaults, cache publication and request cadence remain.
This is a source-discovered correctness fix, not new user feedback.

Four frozen actual-parser tests cover eight generated vectors. Before the change,
both quoted overflow values fail with the intended AssertionError; the finite
and unavailable controls pass. After the one-expression correction all four
pass unchanged, preserving symbol, price and sparkline in every vector. The six
existing parser tests and ten generated cache child cases also pass.

Fresh full gates pass: **4,743 JVM tests / 494 suites**, zero failures/errors/skips;
debug and optimized builds, R8, vital lint, and **404 slideshow / 786 remote**
Node-reported tests. Independent source review passes and all seven bindings
were rehashed. All 23 bundled source assets match 1.205; the generated DEX profile
changes with the rebuilt code. This is generated JVM parser acceptance, not
native Crypto widget, optimized parser, live provider or API28 acceptance.

Exact optimized **1.206/code200** is fully read back in both rooms, 25,887,623 bytes,
SHA256 `f65239e425badf4bb2f4c42cc72aa954dac3ccbd616ac553491cf599f8ae7b5c`.
Each room receives one install. Living's uncertain acknowledgment is reconciled
by six read-only observations without replay. All 43 Family / 30 Living preference
files, four complete maps/revisions, names and Dev Mode remain unchanged.
Family fonts/accessibility, original Chromium activity, resumed frame and awake
state remain. Both normal frame requests pass; captured fatal/ANR markers are zero.
These are scoped observations. Copied registry records are removed, originals
match, and every capture remains in its own private directory.

Original **06:56:14 UTC** start on 5 November; discovery cap **07:01:14**,
target **07:11:14**, hard limit **07:26:14**. Source qualification and selection
precede the cap; the definitive baseline completes 7.06 seconds after it.
No clock resets. Prior crypto cache acceptance, screensaver holds, phone and
publication boundaries remain. The previous optimized-photo preflight mapping
was retained privately before this rebuild replaced its build-output path.

Selected evidence: `mocks/portal-crypto-change-validity-20261105/verification.json`.

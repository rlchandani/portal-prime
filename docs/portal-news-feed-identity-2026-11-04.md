# News removal keeps the other subscriptions

A saved News feed with no convertible ID changed identity on every load, so its
Remove action could do nothing. Duplicate saved IDs made Remove delete multiple
rows and supplied duplicate keys to the native collection.

`NewsConfig` now deterministically assigns distinct missing/duplicate identities
using the already proven Camera pattern. It reserves all existing IDs first,
retains the first valid occurrence and preserves both Long bounds. Loads stay
read-only. Add and Remove persist the normalized snapshot; new IDs skip existing
ones and the three public storage operations are serialized. Names, URLs, order,
trimming, blank filtering, News UI, fetching, cache and settings schema stay intact.

Twelve frozen JVM cases reproduce **nine failures and three controls**, then pass
unchanged. All **4,426 JVM tests / 462 suites**, debug and optimized builds, R8 and
vital lint pass. Independent source review confirms the actual editor key/removal
call paths and eighteen protected input hashes. New-ID collision skipping and
serialization are source-reviewed; these cases do not force a same-millisecond
collision or concurrent writers.

The same frozen zero-permission generated Android fixture loads actual original
debug APKs with DexClassLoader and calls public `load`, `add`, and `remove`. Its
Context wrapper validates the requested store/mode and returns only fixture-local
preferences. Baseline **eight failures/eight controls** become **sixteen passes**
on Family/API29. Every string/integer sentinel remains exact; unexpected preference
requests are zero. Independent report/DEX/source/embedded-APK checks pass; both
fixture runs retire package/process/external files. The first runner preflight
incorrectly treated absent-package query exit1 as an error; that query handling
was corrected before any claim/install/launch. Failed log retained, assertion
gates unchanged, and no baseline device run repeated. An independent host-review
expectation was also corrected: blank Add intentionally makes no preference call;
the other fifteen cases must request the exact admitted store/mode. That failed
host assertion and its correction are retained separately from product failures.

Both rooms contain exact optimized **1.145/code139**, 25,886,647
bytes, SHA-256 `a78f11081e556e85b6348b2d0a80579d4e5bd63645075ce19713786100b1fe35`. One install per room; Living's
uncertain acknowledgment is reconciled through 6
read-only observations. All **43 Family/30 Living preference files** and four
complete maps match. Family font/services/original Chromium/resumed frame/awake
remain. All ten screensaver APK entries are exact, so no JS check rerun or new
screensaver functional acceptance is claimed. Saved Shuffle/fit stays.

Rendered News settings, live feeds/cache/article opening, optimized NewsConfig,
API28 and physical Living functional acceptance remain separate. No owner feed
was inserted/removed, no phone/media/provider/account operation, push or publication.
Public release remains **1.83/code77**. Whole-product goal active.

Original **09:39:57–10:09:57 UTC November 4** issue clock retained, target15minutes;
final timing/cleanup recorded in the verification receipt. All136old queue objects,
raw suffix, Weather default and earlier parked scopes/clocks remain.

Portable generated fixture and evidence:
`mocks/portal-news-feed-identity-20261104/`.

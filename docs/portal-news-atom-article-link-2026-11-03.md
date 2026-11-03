# Atom news article links — 2026-11-03

An Atom entry can list its self or enclosure link before its article
representation. NewsFeed previously took the first href regardless of relation.
A headline could therefore target entry XML or an audio enclosure instead of
the article. The active Home news widget fetches and interleaves these headlines
and passes each returned link directly to ACTION_VIEW when tapped.

NewsFeed now selects the first nonblank Atom href whose relation is absent or
exactly alternate. An omitted relation uses Atom's alternate default. Entries
without such a link retain their headline with a null article target. Complete
quoted attributes accept standard single or double XML quotes and prevent
data-href or href-like text inside another value from becoming an article URL.
Existing nonempty RSS link text keeps priority. Title cleanup, entities, order,
headline cap and malformed-document behavior remain. No MIME preference,
namespace, xml:base, relative-URI or general XML-parser redesign is included.

Ten frozen real-parser methods reproduce **eight AssertionErrors and two
passing controls**, zero errors or skips, on the unchanged baseline. Literal
headline/URL expectations cover self and enclosure ordering, omitted relations,
no eligible article, multiple alternates, XML quote styles, blank eligible
targets, attribute order and independent entries. The two new controls retain
the first of multiple alternates and RSS text priority/entity cleanup. The six
existing NewsFeedTest methods retain cap, missing-title, CDATA/entity and garbage
controls. All ten methods pass unchanged.

Full gates pass **4,283 JVM tests / 444 suites**, zero
failures, errors or skips, plus debug and optimized APKs, R8 and vital lint
in **1m 55s**.

Actual debug Family parsing improves 6/16 to 16/16: 10 corrected checks and 6 retained checks, comprising 5 product controls and one classloader harness control. Each variant makes 17 actual parse$app calls and observes 24 headlines through 24 title getters and 24 link getters. Actual generated XML inputs also exercise exact attribute
boundaries. Complete ordered titles and optional links are compared to literal
expected values. The native fixture calls the actual internal debug parser and
actual Headline getters; it performs no Context, household preference, HTTP,
provider, service, article-open or process timezone/Locale operation.

Both accepted native parents retire and owned scratch is removed without a
cleanup fault. The first actual compiler attempt succeeds with **3
Java 8 obsolescence warnings**, without a deprecation note. Pre-execution author and
admission inspection history remain separately retained. Only normal cleanup
is observed; external runner failure and timeout cleanup remain unverified.

Native parsing acceptance is **debug only**. The isolated optimized parser and
Headline getters are unavailable to this fixture; zero optimized parse calls
and no added keep rules. API28, actual feed fetching/cache behavior, real
provider documents, browser launching and physical News UI remain unverified.
Relative links, MIME policy, namespaces, xml:base and broader malformed XML
handling retain their existing scope. A correct returned URL does not establish
successful browser or provider admission.

Exact signed optimized **1.119/code113** is installed and fully hashed in Family
Area and Living Area. SHA-256:
`a2062892d83bd4bf774629ca51ce2f885505ec6865a8fc18481c1e142da7e29a`
(25,879,086 bytes), package `com.immortal.launcher.debug`; signer matches
the retained 1.118 rollback. Both helpers exit zero and request the screensaver.
The retained rollback is not executed for this unit.

Complete captured screensaver, settings/profile, layout and source maps match
preflight; room names and Dev Mode remain. **Eleven named preference states per room match: Family has ten byte-identical files and one unchanged absence; Living has seven byte-identical files and four unchanged absences, including the news preference file in both rooms.**
Strict postflight passes without an exception, household preference edit or
restore. Family font, accessibility, original Chrome task, resumed frame, awake
display and full installed SHA remain.

The coordinator inspected Family's actual display: Black screensaver with large tan 3:33 clock and Tuesday, November 3 date; small bug icon at bottom right. Only the current clock frame is visible.
No physical news, configured feed or browser acceptance is inferred. Living
visual acceptance remains open. Normal diagnostic and logcat lines=1500
requests return HTTP200 in both rooms. Actual outputs contain **1,642
Family and 1,707 Living lines**, with zero fatal or ANR markers.
These are limited positive observations, not complete runtime health, live
feed/provider or negative authentication acceptance. Original registry hashes
match; only the owned copied registry is removed.

Public manifest remains **1.83/code77**; its guard rejects local 1.119/code113
with expected exit 1. Native CLI and phone, HA, authentication, push and
publication boundaries remain. Original independent audit clock
**11:21:15–11:51:15 UTC** includes discovery, implementation, gates, delivery,
documentation, cleanup and commit; it is not reset. The broader goal stays
active. **95 tasks, 60 delivered, 25 locally verified, 3 approval, 6 parked and
1 queued**. All 94 prior task objects and their complete raw suffix remain.
Default Weather and all boundaries remain except the current room version.

Unselected Alpaca fallback, Converter future-cache and current Todo/Notes
source-only hypotheses share this clock without implementation or a new slot.
Earlier Aurora, ISS, SMB, Sleep, Back, Sky and other held or exhausted findings
retain their original clocks. Documentation and roadmap rendering acceptance
are recorded separately in the structured proof.

Structured proof:
`mocks/portal-widget-gallery-20261102/evidence/news-atom-article-link-verification.json`.

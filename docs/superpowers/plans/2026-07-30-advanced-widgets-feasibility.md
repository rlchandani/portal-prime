# Advanced Widgets — Feasibility & Design (2026-07-30)

Scope: the "Bigger — real integration / new backend" widget set the user asked for.
Research done via parallel codebase audits; this doc is for review BEFORE any code.

## ✅ APPROVED 2026-07-30 — decisions locked
- Build all 6 widgets + cleanup, in the sequenced order below.
- **Secret storage: plain SharedPreferences** (match existing MqttConfig precedent; no new dep).
- **Alpaca feed: `iex`** (free Basic key, real-time, IEX-only thin tape). Widget notes the source honestly.
- Alpaca key/secret + HA URL/token supplied by user.

## ✅ SHIPPED 2026-07-30 — all delivered
Cleanup + 6 widgets built, unit-tested, committed. Full suite: 351 tests, 0 failures; debug APK assembles.
- **Http.kt / HttpCache.kt** — shared fetch (header map, defaultable UA/timeouts, 2xx + errorStream) + TTL cache.
- **Crypto** — CryptoConfig/CryptoFeed/CryptoSettingsActivity, KIND_CRYPTO. CryptoFeedTest (5).
- **To-Do** — TodoStore (kept separate from NotesConfig)/ImmortalTodoWidget/TodoSettingsActivity, KIND_TODO. TodoStoreTest (5).
- **News** — NewsConfig/NewsFeed (hand-rolled RSS+Atom, no XmlPullParser)/NewsSettingsActivity, KIND_NEWS. NewsFeedTest (6).
- **Stocks** — AlpacaConfig/AlpacaClient/StocksConfig/ImmortalStocksWidget/StocksSettingsActivity, KIND_STOCKS. AlpacaClientTest (6).
- **Options scanner** — OptionsScanConfig/OptionsScanner (delta-magnitude filter + OCC decode)/OptionsSettingsActivity, KIND_OPTIONS. OptionsScannerTest (6).
- **HA control** — HaConfig/HaClient (REST+token)/ImmortalHaWidget (optimistic toggle tiles)/HaSettingsActivity, KIND_HA. HaClientTest (5).
- Widget->editor reachable via each widget's tap; new *Configs exempted in the settings tripwire.

## How a custom widget is added (shared cost, all of these)
Non-AppWidget "Immortal widgets" wire in via ~5 touchpoints (HomeWidgetStore + HomeActivity):
KIND_ const, picker entry, title case, picker glyph, `ImmortalWidgetContent` dispatch, + the
composable (render via `ImmortalWidgetShell`, subscribe to a store listener). Persistence/spans/
JSON are generic. So the *widget shell* is always S; the cost is the **backend behind it**.

Reusable templates: `Weather.kt:252` (httpGet), `HomeActivity.kt:1722` (produceState 30min/1min refresh),
`CameraConfig.kt` / `CountdownConfig.kt` (JSON-array user-list store), `CountdownSettingsActivity` (editor),
`ImmortalTimersWidget` (store+listener widget), `MaControl`/`MaWebSocket` (token-auth external service).

---

## 1. Home Assistant control (lights / scenes / climate)  — L
- **Today:** MQTT is PUBLISH-ONLY — Portal announces *itself* to HA; it subscribes only to its own
  `immortal/<id>/+/set`. NO reading/controlling external HA entities. QuickControlsWidget tiles just
  *launch the HA app*.
- **Path:** HA REST (`GET /api/states`, `POST /api/services/<domain>/<service>`) + optional WebSocket
  (`state_changed`) for live push. Mirror `MaControl`+`MaWebSocket` (token auth, WS commands).
- **New pieces:** HaConfig (URL+token+TLS), HaClient (REST), optional HaWebSocket, state store/poll loop,
  entity-discovery + per-entity config UI, control widget (replaces QuickControlsWidget tiles), optimistic UI.
- **BLOCKER:** user must supply HA base URL + long-lived token. Cleartext LAN http needs net-sec-config.
- **Verdict:** genuine new subsystem, well-scaffolded by existing templates. Highest value (real smart-home
  control on the Portal) but the largest + gated on user setup.

## 2. Notes / To-Do  — M, unblocked
- **Today:** NotesConfig = ONE sticky note + ONE voice memo (single strings). No list/checklist model anywhere.
- **New pieces:** TodoStore (list of `TodoItem(id,text,done,createdMs)`, JSON like HomeWidgetStore, listener),
  KIND_NOTES wiring, ImmortalNotesWidget (checklist w/ add + check-off), migrate old single note → first item.
- **Verdict:** clean, safe, local-only (no network/permissions). The "obvious good" build. Voice-memo stays
  single-instance (out of scope).

## 3. Crypto prices  — S, truly keyless  ⭐ cleanest
- **Source:** CoinGecko free API, NO key. `simple/price?ids=bitcoin,ethereum&vs_currencies=usd&include_24hr_change=true`
  → flat JSON. Generous limits; 5–10 min refresh + TTL cache is safe.
- **New pieces:** CryptoFeed (`fetch(ids)→List<Coin>`), CryptoConfig (coin-id list, mirror CameraConfig),
  CryptoSettingsActivity, ImmortalCryptoWidget. All follow existing patterns.
- **Verdict:** ship-first candidate — keyless, flat JSON, low risk.

## 4. News headlines  — M, keyless via RSS
- **Source:** RSS/Atom feeds (keyless). NOT NewsAPI (needs key + bans prod use). Matches the app's
  "paste a public URL" model (like CalendarFeed ICS).
- **New pieces:** NewsFeed (`fetch(url)→List<Headline>` + XmlPullParser reader, ~200 lines RSS+Atom),
  NewsConfig (feed-URL list), NewsSettingsActivity, ImmortalNewsWidget.
- **Risk:** XML parsing is net-new (app only does JSON + hand-rolled ICS); feeds vary (RSS/Atom/CDATA).
  Follow CalendarFeed's "skip-what-you-can't-parse, never crash" discipline.
- **Verdict:** feasible + keyless, moderate because of XML.

## 5. Stocks (Alpaca) — S, BYO key  ✅ decided
- **Source:** Alpaca Market Data API (user is providing key/secret). Two headers on every request:
  `APCA-API-KEY-ID` + `APCA-API-SECRET-KEY` (no OAuth). Base host `https://data.alpaca.markets`.
  Market-data access is governed by data plan, NOT paper/live — same key works either way; Basic = free.
- **Endpoint:** `GET /v2/stocks/snapshots?symbols=AAPL,MSFT&feed=iex` → object keyed by symbol.
  `latestTrade.p` = last, `dailyBar.c` / `prevDailyBar.c` → **compute change yourself** (no change field).
- **Free-tier reality:** Basic key → `feed=iex` (IEX-only, real-time thin tape) or `feed=delayed_sip`
  (full market, 15-min delayed). `sip` (real-time consolidated) needs Algo Trader Plus ($99/mo). For a
  glanceable widget, `delayed_sip` is the honest default. Rate limit 200 req/min — non-issue at daily cadence.
- **New pieces:** AlpacaClient (shared, header auth on Http.kt), StocksConfig (ticker list + key/secret,
  mirror CameraConfig + a secrets screen), StocksSettingsActivity, ImmortalStocksWidget.
- **Verdict:** clean S now that the key is provided. Only network-widget that stores a secret →
  use EncryptedSharedPreferences; market-data keys are read-only (no order placement) so blast radius is small.

## 5b. Options scanner (0.2–0.3 delta) — M, BYO key  ✅ decided, feasible
- **Crux resolved:** Alpaca computes greeks + IV server-side via Black-Scholes and returns them per contract.
  `GET /v1beta1/options/snapshots/{ticker}` → `snapshots[SYMBOL].greeks.delta` + `.impliedVolatility`.
  **No self-computed Black-Scholes** — the widget just filters `0.20 <= delta <= 0.30` client-side.
- **Server-side narrowing:** `?feed=indicative&type=call&expiration_date_gte=<soon>&expiration_date_lte=<window>&limit=1000`
  → follow `next_page_token`. Pull tens of contracts, not the whole chain.
- **Free-tier:** greeks/IV available on Basic via `feed=indicative` (15-min delayed, indicative quotes).
  `feed=opra` (real-time) needs Algo Trader Plus — a daily scanner does NOT need it.
- **New pieces:** OptionsScanConfig (ticker list + delta band + expiry window), reuse AlpacaClient,
  OptionsSettingsActivity, ImmortalOptionsWidget (rows: contract, delta, IV, bid/ask; parse strike/expiry
  from OCC symbol).
- **Risks:** (1) `greeks`/`impliedVolatility` nullable per contract → null-check or contracts silently drop;
  (2) `v1beta1` is a beta namespace → isolate behind AlpacaClient; (3) set expectations: free = 15-min delayed.
- **Verdict:** M (pagination + OCC-symbol parse + null-checks). No math to build — that's what kept it off L.

## 6. Presence / room-activity  — S to wire, but signal-limited  ⚠️
- **Today:** PresenceHub is a good live observable (present/absent, sinceMs, confident) — BUT the signal is
  a dream/sleep *proxy* (no camera/mic access on Portal). In the DEFAULT always-on frame mode, `confident`
  is false → widget mostly shows "Unknown". Only meaningful in presence-following mode, and even then it's
  "panel saw activity," not true occupancy.
- **Verdict:** would be thin/gimmicky by default. Only worth it if it (a) shows the confidence caveat
  honestly, or (b) reads authoritative HA occupancy (which folds into widget #1, pushing it to M/L).

---

## Recommended sequencing (DECIDED set — build all)
0. **Cleanup (S)** — extract `Http.kt` (shared httpGet + header support) + TTL cache helper FIRST;
   every widget below depends on it. Presence dropped (gimmicky by default).
1. **Crypto (S)** — cleanest keyless win, proves fetch+config+widget pattern on the new Http.kt.
2. **Notes/To-Do (M)** — local, safe, no network; new TodoStore + checklist widget.
3. **News (M)** — keyless RSS; adds XmlPullParser (net-new XML path).
4. **Stocks (S)** — Alpaca quotes; introduces AlpacaClient + EncryptedSharedPreferences secret store.
5. **Options scanner (M)** — reuses AlpacaClient; delta-band filter on server-narrowed chain.
6. **HA control (L)** — the big one; real smart-home control; gated on user URL+token (user confirmed).

## Cross-cutting cleanup (item 0, do first)
Extract the duplicated `httpGet` (~6 copies) into one `Http.kt` — must support **request headers**
(Alpaca needs `APCA-API-KEY-ID`/`APCA-API-SECRET-KEY`) as well as the existing User-Agent/timeout pattern.
Add a shared SharedPreferences TTL cache helper (Weather/IssPasses pattern) so every network widget
survives rate limits + offline (renders last value + "as of HH:MM").

## Secret handling (Stocks/Options/HA) — DECISION NEEDED
API secret + HA token live on-device. Two options:
- **(A) Match existing precedent — plain SharedPreferences.** `MqttConfig` already stores the MQTT
  password as plaintext prefs; the app has no `androidx.security-crypto` dependency today. Consistent,
  zero new deps, but the secret is readable by root/backup extraction.
- **(B) EncryptedSharedPreferences** (`androidx.security-crypto`). Stronger at-rest, but a new dependency
  and a divergence from the MQTT pattern (arguably we'd then want to migrate MQTT too).
- **Recommendation:** (A) for consistency now, since these are personal single-user devices and Alpaca
  market-data keys are **read-only** (no order placement → limited blast radius). Flag (B) as a later
  hardening pass across all secrets. Regardless: never hardcode, never log secrets, recommend the user
  mint a market-data-scoped Alpaca key. **← confirm A vs B before Stocks build.**
</content>

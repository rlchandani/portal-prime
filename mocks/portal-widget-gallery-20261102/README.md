# Immortal widget studio — review iteration 01

Design review only. No personal data, real service commands, external assets or native release.

From the repository:

```sh
python3 -m http.server 8891 --bind 127.0.0.1 --directory mocks/portal-widget-gallery-20261102
```

Open <http://127.0.0.1:8891/>. Selections persist in the URL and support browser Back/Forward.
Use the Portal view to inspect the proposed layout. Android and iOS are design references,
not native builds. Browser dimensions are review dimensions, not measured Portal dp.

## Component contract

- `catalog.json`: one record per built-in kind plus the installed-provider category.
  `implemented` means the state branch exists in inspected source; all preview layouts are
  proposed. State fixtures have no connection to live data or accounts.
- `app.js`: state/size/theme selection, preview rendering, local demo actions and action
  explanation dialog. No auto-refresh or network calls beyond fetching the bundled catalog.
- `style.css`: approved background/card tokens, adaptive layout, 48 px controls, font scaling,
  reduced motion and focus styles. Light-theme positive text uses `#216F4D` to meet contrast;
  the approved neutral surface colors stay unchanged.
- `research.html`: source links, design rationale and validation/release gates.

Useful sizes reflect available information. The alarm has one size; Options has medium and
large; installed providers have one illustrative size with actual dimensions provider-owned.
Other widgets show different density at small/medium/large.

Timer controls preserve the selected sample duration through pause/resume. To-do toggles one
sample task. Home controls toggle a sample value. Other actions explain intended behavior.
The calendar refresh demo uses “Show sample result” to finish; it does not contact a feed.

## Native handoff

See `docs/superpowers/specs/2026-11-02-portal-widget-gallery-design.md`,
`docs/portal-widget-inventory-2026-11-02.md` and
`docs/design/portal-widget-settings-map-2026-11-02.md`.
Design acceptance, native build/tests, Family Area measurements and release verification remain
separate gates. A browser check is not evidence of native performance or provider compatibility.

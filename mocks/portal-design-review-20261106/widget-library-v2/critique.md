# Add a widget, v2 (2026-11-07)

**Baseline used.** A 2 × 4 slot grid on every page with free placement and gaps allowed. Sizes come in whole slots only: 1×1, 2×1, 1×2 and 2×2. Photo memory is 2×2 on page 1. Margins are 24 dp and the card gap is 12 dp. There is no Favorite apps heading and no All apps link, and the page dots sit under the widgets. In Customize, each card has a six-dot grip plus Move / Size / Settings / Remove.

## The flow
1. **Customize.** Every empty slot shows a quiet dashed "+". That slot is the entry point.
2. **Choose.** One sheet shows the seven everyday widgets as real, live previews at slot size, with no copy. A small 4×2 map in the title shows which slot you tapped. The eighth tile, "More", reveals the six retire-candidates and Android app widgets.
3. **Place and size.** The sheet goes away. The widget appears in the actual page, in the tapped slot, outlined in green, and everything else on the page dims. A floating bar shows four shape glyphs (1×1, 2×1, 1×2, 2×2). Shapes that can't fit around that slot are greyed out. Tapping a shape reflows the preview in place.
4. **Add.** The widget drops in with a short green ring. Customize stays open so you can keep arranging.

## What changed from v1, and why
- **The catalog tab is gone, along with 15 × 3 specimens and the long text.** The library *is* the catalog. You learn what a widget shows by seeing it, and its sizes by trying them on the real page.
- **You choose where before what.** Tapping an empty slot answers "where" immediately. That makes "does it fit?" something the app works out, not something you need to know. The library can't offer a size that won't fit.
- **The size step happens on Home, not in a dialog.** v1 showed four previews in a sheet. v2 shows one preview in the place it will live, which is easier to judge.
- **Five widget kinds sit under "More"** (Today, Quick controls, News, Stocks, Crypto), plus Options and Android app widgets. Nothing is removed. They're just quieter.
- **No explanatory notes on screen.** The minimap, the green outline and the greyed shapes carry the meaning.

## Considered and rejected
- A global "Add widget" button that picks a free slot for you. It adds a decision the slot already makes. Keep it only as the fallback when a page is full: the trailing page dot becomes "+ new page" (not built here).
- Drag from the library onto the page. It's elegant on a phone, but on a wall-mounted 1920 dp touch screen it's a long, error-prone gesture. Tap, then tap, is more dependable.

All data is sample. Photos are bundled fallbacks. No commands are sent.

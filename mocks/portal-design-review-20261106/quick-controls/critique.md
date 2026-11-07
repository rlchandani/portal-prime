# Quick controls setup: critique

**Verdict: approve with changes.** The structural decision is right: both widget entry points go to one page, which links to the existing dashboard owner and the existing Apps tab instead of adding new preferences. The page that results from that decision isn't finished yet. It explains itself where it should simply show the answer.

## What works
- One destination for both entry points. Today the heading opens Display and Customize → Settings opens Quick buttons; neither one controls this widget. That fix matters more than anything visual.
- No parallel preference system. `home_dashboard.url` stays the only owner, and `HomeDashboardConfig.normalizeUrl` stays authoritative.
- Inventory is read-only and honest. A saved address "does not confirm connection or sign-in" is the right thing to know, even though it shouldn't be a paragraph.

## What's wrong (ranked)
1. **Four tiles that do one thing.** Lights, Scenes, Climate and Home each call `launch(haPackage)` (`HomeActivity.kt:3016–3019`). A lightbulb tile labelled "Lights" looks like a switch. A family member taps it expecting the lamp to change and gets an app launch. The proposal *explains* this in a footnote. Explaining a dishonest object doesn't make it honest. (Screenshot `home-dark-current-tiles.png`: the five-tile grid makes this obvious.)
2. **The preview comes last.** "On Home" sits below two cards and three hint paragraphs, below the fold at 2160×1440. The thing you are setting up should be the first thing you see.
3. **Prose stands in for state.** Three hint paragraphs (precedence, sign-in, shared state, availability timing) restate what the rows could say in five words each. Every sentence the person has to parse is a design decision left unmade.
4. **Status vocabulary is inconsistent and doesn't help decide.** "Configured", "Available" and "Not available" describe the system. The person wants to know whether it is *on my Home*. A Home Assistant app that is "Available" but unused (because a dashboard is saved) shows green, which is misleading.
5. **"Manage apps" as a permanent row.** It's a dead end when nothing is missing. It should appear only when something can be installed.
6. **Palette is cool blue-grey (#14161b) with a white/blue accent.** That reads as a system utility, not a household object. Outlined cards add visual noise.

## Redesign (see `index.html`)
- **Page title "Quick controls"** (drop "setup"). Under it, **one live sentence that is the status**: "Home shows Hey Jarvis and your Home dashboard."
- **Two columns, no scrolling at 1080×720 logical.** On the left is the live widget preview, using the same renderer as Home. On the right are three source rows: Home dashboard (taps through to existing page `ha`), Jarvis, Home Assistant app. Each row has one status word (*In use / Not needed / Not installed / Set up / Checking*) and one short reason. Only *In use* gets the accent dot.
- **"Open Apps"** appears only when Jarvis or a needed HA app is missing.
- **Tiles that leave Home carry an "opens elsewhere" glyph**, so the dashboard and HA tiles read as doors, not switches. Jarvis doesn't carry it.
- **Empty widget**: "Add your home dashboard, or install Jarvis or Home Assistant." plus one button that opens this page. This also fixes the current copy, which omits the dashboard.
- **Checking**: a saved dashboard shows immediately. App slots hold a quiet outlined placeholder, not the empty-state message (which today flashes because discovery starts from `emptySet()`).
- **Palette evolved**: warm graphite (#171513 / #25221e) and warm off-white light theme, one lamp-amber accent (#e4b86c dark, #8d5d10 light), filled planes instead of outlined cards. The shell geometry (two panes, history pill, row heights) is unchanged.
- **Phone (390 px remote page)**: the sidebar is hidden, there is one column, and the preview comes first. Checked in a 390 px frame.

## Recommendation (product change, needs a decision)
**Replace the four HA app tiles with one "Home Assistant · Opens the app" tile.** It's the same intent, one honest object, and half the grid. The mock defaults to this. The review panel can switch back to "Four tiles (current)" for comparison. If per-room control is wanted, that already exists in a different place: `ImmortalHaWidget` toggles pinned entities over the HA REST API. Quick controls should point there rather than imitate it.

Also needs a decision: should the widget heading open this page (recommended) rather than Display?

Mock notes: all device states are simulated (URL params / the "Simulated device" pill). Taps show "Simulated." toasts. The font is browser system-ui, not native Roboto.

# Immortal UX research and browser prototypes

Open `index.html` through the local server for the comparison workspace. Open `prototype.html` for a full-screen prototype and `research.html` for the complete readable report. `RESEARCH.md` is the editable source report.

```bash
python3 -m http.server 8765 --bind 127.0.0.1 --directory ~/portal-prime/mocks/ux-research-2026-10
```

Then visit `http://127.0.0.1:8765/`. If a server is already serving the parent `mocks/` directory, use `http://127.0.0.1:8765/ux-research-2026-10/`.

No build tool, npm package, CDN, account, or network photo service is needed. Each prototype also opens directly as a local HTML file; the comparison iframe navigation metadata works best over HTTP.

Use the comparison sidebar to choose Everyday, Gallery, or Control; choose a screen; and test connected, offline, or first-use states. Buttons inside the prototype are interactive. Escape returns home or closes a dialog.

The suite is a design study, not an Android implementation. Some secondary choices demonstrate navigation only. Settings, connection details, app installation, and household controls are all local simulations. Use dummy source credentials. Edits last until the page reloads.

The photo assets are copies of the project’s bundled fallback images. `assets/icons.js` is a copy of the pre-existing mock icon library. Production app code and previous mocks were not edited.

The report cites 18 online sources and connects their guidance to the inspected codebase. Native acceptance on Portal hardware remains necessary after choosing a design.

# Portal+ design review 01

Status: **superseded by [complete review 02](../portal-plus-complete/README.md)**.
The original representative mock and screenshots are preserved for comparison.
Android implementation has not started.

This is the detailed follow-up to the [18-source UX research](../ux-research-2026-10/research.html): an iPad-inspired landscape structure with iPhone-style grouped settings, calibrated to the owner's connected Portal+.

Open `index.html` through a local HTTP server. From the repository root:

```sh
python3 -m http.server 8765 --bind 127.0.0.1 --directory mocks
```

Then open http://127.0.0.1:8765/portal-plus-signoff/.

- `index.html`: interactive review, all 22 screens, eight settings categories, theme and household-state controls.
- `native.html`: fixed 1920 × 1280 logical device canvas. At a 2160 × 1440 browser viewport it scales exactly 1.125×.
- `gallery.html`: reference screenshots at the Portal's physical resolution.
- `contract.html`: readable design specification, screen map, feasibility evidence, and implementation handoff.
- `tokens.js`, `native.css`, `app.js`: exact assets, geometry, styling, and illustrated interactions.
- `reference/manifest.json`: screenshot identities, URLs, dimensions, and SHA-256 hashes.
- `VALIDATION.md`: verification evidence and limits.

State is in memory. Reload resets it. Photo-source tests, downloads, server status, and household controls are simulated; the prototype makes no requests to those services and does not contact or change the Portal. Use dummy credentials. The phone address, storage, album counts, events, and household data are sample content.

Review Home, Customize home, the eight Settings categories, and the source → connection → albums journey. Try dark settings, offline, and first use. Some settings and tool dialogs illustrate a pattern rather than every field of the existing editor; the screen map states this explicitly.

**Required after approval:** preserve the current repository state and user work, then create a new `codex/portal-plus-ipad-ux` branch before Android edits. Do not implement or install until the owner signs off. Preserve every existing preference, integration, configured widget, and signing identity.

References freeze this revision; they are browser images, not evidence that Android fidelity is already proven. Native delivery requires a comparison on the connected Portal+ and correction or reapproval of visible differences.

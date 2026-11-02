# PWA tab branding — bounded delivery

Start 2026-11-02 09:13:42 UTC; hard stop 09:43:42 UTC including restoration.

Observed installed 1.69 HTML has no explicit favicon. The browser requests protected `/favicon.ico` (401), although `/remote/app-icon?size=64` already serves the public PNG (200).

Use one explicit 64 × 64 PNG icon link in RemoteHtml. Preserve the manifest, Apple touch icon, LAN protections and authenticated management routes. No icon route, client polling or storage changes. Version 1.70 / code 64 is a local unpublished iteration.

Run the existing local release readiness checker against the accepted 1.69 artifact: bundled JavaScript/Python, fresh JVM suite, optimized APK/R8/lint, metadata and signing. No new string-mirroring test for this low-impact HTML change.

Install once on Family, verify exact APK readback and actual paired/unpaired browser icon/auth behavior at mobile width. Deploy the same final artifact once to Living, verify identity and unchanged saved profile/screensaver/layout on both. Restore Family screensaver if interrupted. Record evidence and commit locally; no GitHub or public release.

Independent sidecar prepares a separate companion emulator QA plan without changing devices, app code or pairing. Emulator checks do not substitute for a physical Pixel.

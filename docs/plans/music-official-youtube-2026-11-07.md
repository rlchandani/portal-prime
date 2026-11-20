# Music player v3 — official YouTube route (approved 2026-11-07)

Decision: replace the scraped music.youtube.com WebView with Google-official pieces.
No user-agent spoofing, no embedded Google sign-in, no DOM scraping.

## Pieces
1. **Sign-in (QR):** OAuth 2.0 device flow (`/device/code`, poll `/token`), scope `youtube.readonly`.
   Portal shows QR of the verification URL + the user code. Refresh tokens; revoke on sign-out.
   Multiple accounts: one refresh token each; switch with a tap.
   Tokens: noBackupFilesDir, encrypted, excluded from fleet backups/snapshots, never logged.
2. **Library:** `playlists.list?mine=true`, `playlistItems.list`, liked videos (`LL` via channels
   relatedPlaylists.likes; try `LM` but treat as optional), `subscriptions.list?mine=true` as artists.
   Cache ≤ 30 days (API policy).
3. **Search:** `search.list` type=video, videoCategoryId=10, videoEmbeddable=true, videoSyndicated=true.
   100 calls/day default (separate bucket) → search on submit only, cache results, show remaining
   budget state; request a quota extension.
4. **Playback:** official IFrame Player API in a WebView with a valid https origin/Referer,
   player visible ≥ 200×200, no overlays on it, no audio-only/background play. Our native queue drives
   `loadVideoById`; errors 101/150 (embed disabled) / age-restricted → auto-skip with a toast.
5. **Accounts UI:** signed-in account chip, Switch account, Add account, Sign out (revoke + delete).

## Known losses (told to the user)
Saved albums, mixes/radio, lyrics, history, likely ads even with Premium, screen-off playback,
some embed-blocked tracks.

## User setup (blocking device sign-in only)
Google Cloud project → enable YouTube Data API v3 → OAuth consent (External, youtube.readonly,
add household test users) → OAuth client type "TVs and Limited Input devices" → publish
"In production" (unverified, <100 users; avoids 7-day token expiry). Client id/secret go into
local.properties (never committed; repo is public).

## Order
A. Spike: IFrame player in the Portal WebView (autoplay, origin/Referer, error 153/150 handling).
B. Mock (UX) of the new flow → user approval.
C. Build behind the existing MusicTransport contract; remove YtMusicBridge scraping after parity.
D. Device acceptance on Family; then Living.

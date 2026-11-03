# Direct video redirects: remaining transport boundary

Research only; no production video patch or native streaming verdict.
The original observation is **01:52:39 UTC on 3 November 2026**, with its
**02:22:39 UTC** deadline retained. This record does not start a new clock.

`PhotoFrameController.showRemoteVideo` passes an uncached source URI and source
headers to `SlideshowVideoView`. Its background owner calls
`MediaPlayer.setDataSource(context, uri, headers)`. Cached videos use local files.
The platform path bypasses `PhotoSourceRedirects`; the delivered 1.93 helper
protects video prefetch, not this direct stream.

Primary Android 9 and Android 10 `MediaHTTPConnection` sources were retrieved
with normal certificate verification and inspected:

- [Android 9 source](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-9.0.0_r1/media/java/android/media/MediaHTTPConnection.java)
- [Android 10 source](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-10.0.0_r1/media/java/android/media/MediaHTTPConnection.java)

Both provide the internal `android-allow-cross-domain-redirect` switch. Turning
it off controls host/protocol comparisons, but those checks do not compare ports.
The source applies supplied headers on each connection and permits twenty
redirects. A switch-only change would therefore leave the credential-origin
boundary incomplete. This is a source conclusion, not a claim that these exact
framework revisions match Meta firmware or that a real key was exposed.

A complete design would keep network reads under the shared redirect policy
through an app-owned ranged `MediaDataSource`, or finish a protected cache
download before handing a local file to the player. The ranged approach needs
size/range/seek, cancellation, concurrency and playback tests; cache-first needs
an acceptable first-play latency and storage tradeoff. The current background
player, muted playback, generation ownership and position retention must remain.
No unverified shortcut is being installed to change those behaviors.

This streaming item remains parked. Native platform redirects, TLS, actual
provider playback and latency have not been tested for it. Pixel remains off
limits, and this research makes no changes to Home Assistant, firmware
configuration, source credentials or user files.

Source fingerprints for resumption:

- Android 9 SHA-256:
  `327a06df31204b66a55b34525c13bb42fb41ee580bcca1f43ee6904a43f924fc`
- Android 10 SHA-256:
  `2b7c8e6047281ae26ce2203ea82047968646f906e2e7dfa8962855274fd711f8`

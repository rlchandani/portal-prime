# Portal+ custom home dashboard

The custom dashboard replaces the installed Home Assistant companion app as the
ordinary smart-home entry point. Home's widget, Apps, Quick controls and Connections
settings open the configured address in the Portal browser, preserving its existing
sign-in.

## Implementation

- `HomeDashboardConfig` persists an address in `immortal_home_dashboard`. The
  `home_dashboard` registry domain exposes one `url` string setting to the device
  and phone remote. It accepts complete HTTP(S) addresses, rejects malformed and
  non-string input, and supports clearing the address to disable the shortcut.
- `HomeDashboardActivity` supplies a stable component identity. The regular launcher
  paths invoke its browser helper directly. The Portal's `org.chromium.chrome`
  package is preferred; the default browser handles the address when it is absent.
  The intent requests reuse using `Browser.EXTRA_APPLICATION_ID`; tab reuse has
  not been separately measured.
- The launcher creates no embedded WebView and copies no browser credentials.
  Home renders a lightweight dashboard launcher card and bypasses the old native
  Home Assistant API polling producer when an address is configured.
- Native API configuration remains available in Advanced. Clearing the dashboard
  address restores the legacy widget behavior.
- The configured household address and dashboard screenshots remain private and
  are not bundled into the app or this document.

## Validation

Validated on the connected Portal+ over Wi-Fi ADB, using the non-debuggable benchmark
variant of `com.immortal.launcher.debug`.

- Debug, benchmark and instrumentation APK builds succeeded.
- All 718 JVM tests passed, with no failures, errors or skipped tests.
- Device smoke testing passed all nine assertions: persisted address, companion
  app absent, custom Home card, Apps tile, settings address row, and signed-in
  dashboard navigation from Home, Apps, Quick controls and Settings.
- The companion package `io.homeassistant.companion.android.minimal` was uninstalled.
  The device was left displaying the signed-in custom dashboard.

The smoke test checked navigation and rendered dashboard content without operating
household devices. Dashboard rendering speed, browser tab reuse and household
controls were not benchmarked or exercised. The performance change established here
is removal of redundant native API polling while the custom dashboard is configured;
the browser owns dashboard rendering.

Installed benchmark APK SHA-256:
`e6aac0f4d1215052cc125b0628d0199b29819658894ff377dcafd78fb32f8284`.

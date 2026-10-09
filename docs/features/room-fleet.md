# Room fleet management

Use the existing Immortal Fleet Agent to manage provisioned Portals over the local
Wi-Fi. Each Portal retains its own bearer token, room name and phone pairing.
The agent starts on boot; it does not require a working Wi-Fi ADB connection.

`room-fleet.py` adds authenticated address refresh and verified deployment to the
native `fleetctl`. It does not provision devices, modify system roles or run a
background monitor.

## Set up the host

Keep device records in the ignored `provisioning/fleet/` directory. Restrict that
directory to mode `0700` and each JSON file to `0600`; never commit records or
put bearer tokens in command arguments.

Build the native CLI when a suitable binary is not already available:

```bash
mkdir -p build/fleet
rustc -O provisioning/fleet.rs -o build/fleet/fleetctl
python3 provisioning/room-fleet.py --help
```

The helper defaults to the repository's `build/fleet/fleetctl` and
`provisioning/fleet/` paths. Set `IMMORTAL_FLEET_CLI` and `IMMORTAL_FLEET_DIR`, or
pass `--cli` and `--registry` before the command, to use another existing host
installation. A runnable copy can also live in `build/fleet/room-fleet.py`.

## Target a room or the fleet

```bash
python3 provisioning/room-fleet.py status
python3 provisioning/room-fleet.py info
python3 provisioning/room-fleet.py screensaver get --device "Family Area"
python3 provisioning/room-fleet.py calendar get --device "Living Area"
```

Commands default to all registered rooms; an exact room name or serial selects
one device.

If DHCP changes a Portal's address, refresh registered rooms through mDNS:

```bash
python3 provisioning/room-fleet.py refresh
```

Refresh resolves only registered room names, then authenticates `/info` using
the existing token and requires the exact friendly name before replacing the
cached IP and port. The token and other registry fields are preserved. Discovery
requires macOS and working multicast on the same LAN.

## Deploy a local build

Use an APK signed with the same key as the installed app:

```bash
python3 provisioning/room-fleet.py deploy /path/to/app-debug.apk
python3 provisioning/room-fleet.py deploy /path/to/app-debug.apk --device "Living Area"
```

The default package is `com.immortal.launcher.debug`; pass `--package` for a
different package. The existing developer-mode install flow pauses the official
self-updater so the customized build is retained.

Android replaces the process serving HTTP when Immortal updates itself. A lost
reply therefore requires verification, not an assumption of success. Self-update
verification checks the newly installed APK's location or timestamp and its
SHA256 against the uploaded artifact. Other app installs require a successful
installer response. A failure on one room does not conceal the other room's
result, and incomplete verification returns a failure exit code.

## Reboots and scope

Keep power connected and both Portals on the same Wi-Fi. The fleet service
returns automatically after a full reboot. Provisioning tests on two API 29
Portal+ devices measured approximately 38 seconds to authenticated fleet access,
then verified unattended app installation using fleet HTTP alone.

The fleet agent is an application management channel, not a replacement for a
full privileged ADB shell. Wi-Fi ADB stopped listening after those full reboots;
fleet installs, configuration and the paired phone remote continued working.

Portable Immortal layout and preferences can be copied while excluding fleet
identity, phone sessions, native Android widget IDs and active timers. Shared
photo paths, timestamps and contents should be preserved for slideshow framing
cache reuse. Browser sign-ins and native Meta account identities remain specific
to each device.

## Check the host helper

```bash
python3 provisioning/test_room_fleet.py
```

The tests fake discovery, HTTP and CLI calls; they never contact or mutate a
Portal. Device reboot and installation checks must still be performed on the
actual hardware.

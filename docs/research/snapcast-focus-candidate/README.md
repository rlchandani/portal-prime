# Snapcast focus source candidate — not a deployed fix

See [the feasibility and integration plan](../../music-receiver-focus-plan.md) for the exact public source revision, release/native hashes, licensing and every open runtime gate. These patches and host checks are preserved for review; no receiver APK was built or installed. Fourteen host logic checks passed, three original-source regressions failed as expected, and an API-35 Java check used AndroidX/project stubs. None establishes audible focus behavior on Portal.

Apply the patches only to Snapdroid commit `28529b546c48b7eeec5f0ae90b7ec330c4828dcb`. The optional native-binary overlay requires the exact matching public arm64 executable documented in the plan; no executable or Android API jar is bundled here. Full build, signing/migration, launcher adapter, actual Calls/Jarvis handoff and source/license completeness remain unverified.

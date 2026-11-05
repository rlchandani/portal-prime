# Generated Immich album identity probe (source only)

Root owns compilation, frozen DEX/JAR, isolated native app_process invocation and cleanup. Compile every generated probe class (including nested connection/stream/handler classes) against Android jar; D8 all class files. Main: ImmichAlbumIdentityProbe EXACT_OWNED_APK OWNED_DEX_DIRECTORY. APK production ImmichSource must originate from the exact DexClassLoader. Public listAlbums(String,String) is the only production entrypoint; Album public getters serialize actual results. No private parser/helper reflection, Context, preferences, providers or owner storage.

Process-global URLStreamHandlerFactory is one-shot and must run in an isolated fresh process, never a household application process. It returns a nondelegating handler for every protocol, permits only http://fixture.invalid/api/albums, and supplies finite generated in-memory HttpURLConnection bytes/status. Every other open is denied. Request method/header/open/stream closure/disconnection recorded per case. Generated key only. No sockets, DNS, actual provider or network request. A pre-existing factory or classloader origin mismatch is setup failure, not product RED.

Eight immutable literal expected cases: preserve valid neighbors around nonobjects; reject missing/null/non-string IDs; reject blank IDs; duplicate IDs retain first details and stable order; all-invalid is empty; valid Unicode plus omitted name/count defaults; malformed top-level null; HTTP503 null. Predictions are five product failures and three controls, but actual Android JSONObject coercion determines baseline. Same Java/expectations/DEX for before and candidate; only embedded/source APK differs. Probe writes one JSON line; root stores output and byte/hash freezes. Android runtime/native execution is not performed by author. No live Immich, connection picker, cache/source selection, Home coroutine, cancellation or transport-redirection acceptance.

Original2026-11-05T01:10:43Z target01:25:43 hard01:40:43 retained. Prior asset admission slice remains separate.

Root compiled all five generated classes and ran the frozen probe against both
exact debug APKs on Family API29. Baseline: five failures and three controls;
candidate: eight passes. Both owned native paths retired. The verification
receipt records APK, Java, DEX/JAR and driver hashes.

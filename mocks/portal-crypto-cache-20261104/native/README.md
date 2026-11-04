# Generated native crypto cache fixture

`CryptoActivity.java` loads the supplied debug APK through a framework-parent
DexClassLoader, then calls its actual public `CryptoFeed.fetch` on a background
thread. The APK's real `Http`, `HttpCache`, decoder, Android JSON and
SharedPreferences execute. A process-local HTTPS handler admits only the exact
generated Bitcoin request and supplies finite bytes or an offline exception.
The fixture requests no permissions and has no INTERNET permission.

Every case uses preferences owned by this fixture package. No launcher preferences,
provider responses, credentials or household media are read. The context rejects
unexpected preference names. Checks cover returned labels/prices, raw cache value
and timestamp, later offline fallback, and request/disconnect counts. The native
quote checks do not compare every quote field or prove durable storage after restart.

Build with Java 8 source/target against Android's framework jar, D8 with min API24,
and AAPT2 using this manifest. Package `classes.dex` and the exact debug APK as
`assets/source.apk`, zipalign, then sign with the ordinary fixture debug key.
Install only to the explicitly selected Family Portal and launch
`com.immortal.cryptofixture/.CryptoActivity`. Read its terminal report from
`/sdcard/Android/data/com.immortal.cryptofixture/files/report.json`, then uninstall
the owned fixture. Check the source hash and package absence.

The same frozen Java on Family API29 gives five failures/five passing controls
against baseline debug 1.131, then ten passing cases against candidate debug 1.132.
The `red/` and `green/` directories retain reports, metadata, build logs and cleanup
receipts. These are generated transport observations, without live CoinGecko,
mounted Home UI, API28 or optimized runtime acceptance.

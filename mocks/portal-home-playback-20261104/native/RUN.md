The coordinator owns every device operation. Place classes.dex and the exact selected debug APK (as source.apk) in one private owned shell scratch directory, with an empty writable opt directory. Then run in that device shell:

```sh
CLASSPATH=<scratch>/classes.dex app_process /system/bin HomeFreshnessProbe <scratch>/source.apk <scratch>/opt
```

Require exit0 and terminal JSON ok:true, candidatePass6, candidateCases6, equivalentBaselineFailures4, baselineControls2, apkClassloaderIdentity:true and uiThreadRetired:true. Bind the actual pushed APK hash separately; this runner does not hash the APK. Remove the whole owned scratch after execution. No fixture installation is needed.

The candidate factory, hub, snapshot, callback interface and Kotlin Function1 must belong to the isolated APK DexClassLoader. Baseline is an explicitly equivalent generated captured-update listener, not the original Compose function. HandlerThread/Handler/Looper are real; worker control uses a joined generated publisher, while late-callback regressions use controlled callback ordering. There are zero context, session, network, household or installed-app-state calls; shell permissions still exist. No physical Home UI, optimized, audio/provider or broad acceptance follows.

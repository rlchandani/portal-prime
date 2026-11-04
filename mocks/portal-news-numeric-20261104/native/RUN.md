# Generated native News parser checks

Build with `bash build.sh <Android SDK>` using Android36 tools. The probe loads a
supplied debug Immortal APK through an isolated DexClassLoader and calls the
existing NewsFeed cleanup/parser methods and Headline getters. Its second argument
is an owned ART optimization directory.

The coordinator ran it through app_process on the explicitly verified Family
Portal/API29, with baseline1.138 and candidate1.139 debug APKs in a unique temporary
directory. Both parser processes exit0; baseline reports8 failures/4 controls and
candidate passes12. The wrapper's subsequent retirement observation had a shell
quoting error after successful deletion. A corrected read-only direct-shell
absence check passes. No fixture application is installed.

Only generated strings are used; no fetch/context/preferences/network/UI path is
called. This does not establish optimized runtime, API28, mounted News UI, live
feeds/cache/article opening or physical Living acceptance. The source/output/APK
bindings and setup-error distinction are recorded in ../evidence/verification.json.

# Cinema Letterbox: quiet margins and a slim frame

Optimized **1.140/code134** is installed and fully hashed in both rooms:
**25,886,291 bytes**, SHA256
`75a78dda335343418cb45bcb40faf7e3fa0f178539bfd24196efed4df32af40a`.

Cinema's black top/bottom bars and available side margins now carry the existing
sparse dark doodles. A thin warm-white edge follows the actual photo rectangle.
The five added CSS lines preserve the black base, fitted-photo geometry, existing
panorama crop, grain, motion, dissolve and timers. Mode JavaScript is byte-identical.
Three normalized appearance traces reproduce the baseline exactly; the existing
Cinema golden changes only after that comparison. The preview adds Cinema and
its fixed grain asset to the existing allowlists.

All **4,414 JVM tests/461 suites**, **363 slideshow checks**, **743 application
JavaScript checks**, debug/optimized/R8 and vital lint pass. Four generated
Family/API29 scenes from the exact embedded candidate show doodles in every
top/bottom bar, available side margins and a visible photo edge. All eight
eligible fitted-photo corners pass; panorama retains its existing cover crop.
Independent review reproduces the pixel counts and frame thresholds.

The native fixture initially assumed a literal 1px computed width; Android's
1.125 density quantizes it to 0.888889 CSS pixels, one physical pixel. A subsequent
capture ran before the photo painted. The accepted fixture binds the actual
photo URL, request count and visible layers, then waits for WebView's compositor
callback before PixelCopy. Original corner and frame pixel thresholds remain.
These failed attempts and wrapper/setup corrections are retained and excluded;
they caused no launcher installation. The temporary fixture and process are absent.

Each room received one install invocation. Living's uncertain acknowledgment
was reconciled through six read-only observations and a complete APK readback,
without retry. All **43 Family/30 Living preference files** and four complete maps match.
Family font/accessibility/original Chromium/awake state and resumed frame remain. Both rooms return to their configured photo frame;
saved Shuffle/fit remains, so installing Cinema support does not establish that
Cinema is the currently selected Shuffle style.

| Verified | Still separate |
| --- | --- |
| Four generated direct Cinema scenes on Family/API29 | Production scheduler, native clock, resize, API28, Living visual acceptance |
| Matte/frame pixels; fitted corners; requested motion style | Motion trajectory and hardware performance |
| Exact installed APK and saved configuration in both rooms | All other photo styles and whole-product acceptance |

Original **08:26:59–08:56:59 UTC November4** clock includes discovery, failed
fixture attempts, delivery and closure; target15minutes, receipt elapsed **18.08minutes**. Earlier parked scopes
and clocks remain. No phone, source-media transfer, provider/account, push or
publication. Public release remains **1.83/code77**. Whole-product work stays active.

Structured proof, generated images and portable fixture:
`mocks/portal-cinema-matte-20261104/`.

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.activity.ComponentActivity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** An actual external app window, retained in the test APK's separate :pin_fixture process. */
class PinFixtureActivity : ComponentActivity() {
    private var speedResultsFixture: SpeedResultsFixtureHost? = null
    private var session: PinFixtureSession? = null
    private var web: WebView? = null
    private var dreamButton: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.hasExtra(SpeedResultsFixtures.NONCE)) {
            val nonce = intent.getStringExtra(SpeedResultsFixtures.NONCE).orEmpty()
            if (!Regex("[a-f0-9]{32}").matches(nonce)) { finish(); return }
            speedResultsFixture = SpeedResultsFixtureHost(this, nonce).also { it.show(intent) }
            return
        }
        // Instrumentation supplies a provider-admitted nonce. Fleet's ordinary launcher intent
        // gets its own fresh test-only session; it cannot take over another admitted run.
        val requestedNonce = intent.getStringExtra(EXTRA_NONCE)
        val fromLauncher = intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
        val nonce = requestedNonce ?: if (fromLauncher) PinFixtureSessions.beginLauncher() else null
        val admitted = PinFixtureSessions.attach(nonce, this)
        if (admitted == null) {
            finish() // Never remove another fixture run's task on an unadmitted launch.
            return
        }
        intent.putExtra(EXTRA_NONCE, nonce)
        session = admitted
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val browser = WebView(this).apply {
            settings.javaScriptEnabled = true
            // The fixed, user-approved server is only for a manual Fleet MAIN/LAUNCHER run.
            settings.blockNetworkLoads = admitted.mode != "launcher"
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.databaseEnabled = false
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (admitted.mode != "launcher") return null // blockNetworkLoads enforces offline mode.
                    val uri = request.url
                    if (uri.scheme == "http" &&
                        uri.host == "192.168.0.175" && uri.port == 8767) return null
                    return WebResourceResponse("text/plain", "UTF-8", 403, "Fixture origin only",
                        emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String) = true
            }
        }
        web = browser
        val root = FrameLayout(this)
        root.addView(browser, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT))
        val startDream = Button(this).apply {
            text = "Start system screensaver"
            textSize = 22f
            isAllCaps = false
            setOnClickListener {
                synchronized(admitted) { admitted.systemDreamButtonClicks++ }
                try {
                    // Ordinary explicit app launch: no shell, DreamManager API, settings write,
                    // browser intent:// trick, production test endpoint or document reload.
                    startActivity(Intent().setComponent(ComponentName("com.android.systemui",
                        "com.android.systemui.Somnambulator")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    synchronized(admitted) { admitted.systemDreamLaunchError = null }
                } catch (error: RuntimeException) {
                    synchronized(admitted) { admitted.systemDreamLaunchError = error.javaClass.simpleName }
                }
            }
        }
        dreamButton = startDream
        root.addView(startDream, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
            (72 * resources.displayMetrics.density).toInt()))
        root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val width = right - left
            val height = bottom - top
            if (width > 0 && height > 0) {
                val params = startDream.layoutParams as FrameLayout.LayoutParams
                val desiredWidth = (width * 0.90f).toInt()
                val desiredHeight = (height * 0.12f).toInt()
                val desiredLeft = (width * 0.05f).toInt()
                val desiredTop = (height * 0.02f).toInt()
                if (params.width != desiredWidth || params.height != desiredHeight ||
                    params.leftMargin != desiredLeft || params.topMargin != desiredTop) {
                    params.width = desiredWidth
                    params.height = desiredHeight
                    params.leftMargin = desiredLeft
                    params.topMargin = desiredTop
                    startDream.layoutParams = params
                }
            }
        }
        setContentView(root)
        if (admitted.mode == "launcher") {
            // Fixed generated fixture server, explicitly authorized for the manual Portal run.
            // Its own UUID/form/counter/beacons provide evidence without ADB or provider discovery.
            browser.loadUrl(MANUAL_FIXTURE_URL)
        } else {
            // Instrumentation remains entirely offline with its explicit per-run nonce.
            browser.loadDataWithBaseURL("about:blank", generatedPage(admitted.formValue),
                "text/html", "UTF-8", null)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        speedResultsFixture?.let { it.apply(intent); return }
        session?.let { synchronized(it) { it.newIntents++ } }
        // Deliberately do not reload: any second launch still fails the lifecycle assertion.
    }

    override fun onResume() {
        super.onResume()
        session?.let { synchronized(it) { it.resumed = true } }
    }

    override fun onPause() {
        session?.let { synchronized(it) { it.resumed = false } }
        super.onPause()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        session?.let {
            synchronized(it) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) it.touchDowns++
                if (event.actionMasked == MotionEvent.ACTION_UP) it.touchUps++
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onDestroy() {
        session?.let { PinFixtureSessions.destroyed(it, this) }
        web?.destroy()
        web = null
        speedResultsFixture = null
        dreamButton = null
        super.onDestroy()
    }

    /** Runs only on fixture main; the returned JSON is the live generated document's state. */
    internal fun inspectDocument(reply: (Bundle) -> Unit) {
        val current = checkNotNull(session)
        val browser = web
        fun nativeState(): Bundle = current.nativeState().apply {
            dreamButton?.let { button ->
                val location = IntArray(2).also(button::getLocationOnScreen)
                putInt("dreamButtonLeft", location[0])
                putInt("dreamButtonTop", location[1])
                putInt("dreamButtonWidth", button.width)
                putInt("dreamButtonHeight", button.height)
                putFloat("dreamButtonX", location[0] + button.width / 2f)
                putFloat("dreamButtonY", location[1] + button.height / 2f)
                putBoolean("dreamButtonVisible", button.isAttachedToWindow && button.isShown)
            }
        }
        if (browser == null || isFinishing || isDestroyed) {
            reply(nativeState())
            return
        }
        browser.evaluateJavascript("window.pinFixtureState ? window.pinFixtureState() : null") { raw ->
            val result = nativeState()
            if (raw != null && raw != "null") {
                val document = JSONObject(raw)
                val location = IntArray(2).also(browser::getLocationOnScreen)
                val widthCss = document.optDouble("viewportWidth", 0.0)
                val heightCss = document.optDouble("viewportHeight", 0.0)
                val button = document.optJSONObject("button")
                if (widthCss > 0 && heightCss > 0 && button != null) {
                    result.putFloat("tapX", (location[0] +
                        button.getDouble("x") * browser.width / widthCss).toFloat())
                    result.putFloat("tapY", (location[1] +
                        button.getDouble("y") * browser.height / heightCss).toFloat())
                    result.putBoolean("tapInsideFixture", button.getDouble("x") in 0.0..widthCss &&
                        button.getDouble("y") in 0.0..heightCss)
                }
                result.putString("document", document.toString())
                result.putBoolean("pageReady", document.optBoolean("ready") &&
                    browser.isAttachedToWindow && browser.isShown)
            }
            result.putBoolean("windowFocused", hasWindowFocus())
            reply(result)
        }
    }

    private fun generatedPage(value: String): String = """
        <!doctype html><html><head><meta name="viewport"
        content="width=device-width,initial-scale=1,user-scalable=no">
        <style>
        html { background:#162033; color:#fff; font:24px sans-serif; }
        body { margin:0; height:6400px; background:linear-gradient(#162033,#416280); }
        h1 { position:fixed; left:5%; top:2%; font-size:28px; }
        input { position:fixed; left:5%; top:20%; width:80%; font-size:24px; }
        button { position:fixed; left:25%; top:45%; width:50%; height:96px;
                 background:#edc05d; color:#162033; font-size:24px; touch-action:manipulation; }
        output { position:fixed; left:5%; right:5%; top:65%; font-size:16px;
                 white-space:pre-wrap; overflow-wrap:anywhere; pointer-events:none; }
        </style></head><body><h1>Generated pinned-app fixture</h1>
        <input id="form" aria-label="Generated fixture form" autocomplete="off">
        <button id="counter" type="button">Generated click counter: 0</button>
        <output id="state" aria-label="Generated fixture state"></output>
        <script>
        (function () {
          // Generated in JavaScript on every document boot, independently of the Activity UUID.
          var uuid = 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
            var r = Math.floor(Math.random()*16); return (c==='x' ? r : (r&3)|8).toString(16);
          });
          var clicks=0, ready=false, form=document.getElementById('form');
          var counter=document.getElementById('counter');
          form.value=${JSONObject.quote(value)};
          function report() {
            document.getElementById('state').textContent='Document UUID: '+uuid+
              '\nForm value: '+form.value+'\nScroll: '+window.scrollX+', '+window.scrollY+
              '\nGenerated DOM clicks: '+clicks;
          }
          counter.onclick=function() { clicks++; counter.textContent='Generated click counter: '+clicks; report(); };
          form.addEventListener('input',report);
          window.addEventListener('scroll',report);
          window.pinFixtureState=function() {
            var r=counter.getBoundingClientRect();
            return { ready:ready, documentUuid:uuid, formValue:form.value,
              scrollX:window.scrollX, scrollY:window.scrollY, clicks:clicks,
              viewportWidth:window.innerWidth, viewportHeight:window.innerHeight,
              button:{x:r.left+r.width/2,y:r.top+r.height/2} };
          };
          requestAnimationFrame(function() { window.scrollTo(0,733);
            requestAnimationFrame(function() { ready=true; report(); }); });
        }());
        </script></body></html>
    """.trimIndent()

    internal companion object {
        const val EXTRA_NONCE = "pin_fixture_nonce"
        private const val MANUAL_FIXTURE_URL = "http://192.168.0.175:8767/fixture/device"
    }
}

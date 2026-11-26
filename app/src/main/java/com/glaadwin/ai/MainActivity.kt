package com.glaadwin.ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.util.TypedValue
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var statusLabel: TextView
    private lateinit var wakeToggleBtn: Button
    private var pendingWebPermission: PermissionRequest? = null
    private var startWakeAfterPermission: Boolean = false

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val micGranted = results[Manifest.permission.RECORD_AUDIO] == true || hasMicPermission()

            val webReq = pendingWebPermission
            pendingWebPermission = null
            if (webReq != null) {
                if (micGranted) webReq.grant(webReq.resources) else webReq.deny()
            }

            if (startWakeAfterPermission) {
                startWakeAfterPermission = false
                if (micGranted) {
                    startWakeWordService()
                } else {
                    statusLabel.text = "Mic permission needed"
                }
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#040914"))
        }

        // Handle Android 16 edge-to-edge status & navigation bars cleanly
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#081224"))
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }

        statusLabel = TextView(this).apply {
            text = "Hey Glaadwin: OFF"
            setTextColor(Color.parseColor("#22d3ee"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        wakeToggleBtn = Button(this).apply {
            text = "Start Wake"
            isAllCaps = false
            setTextColor(Color.parseColor("#040914"))
            setBackgroundColor(Color.parseColor("#22d3ee"))
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { toggleWakeWord() }
        }

        val exitBtn = Button(this).apply {
            text = "Exit"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1e293b"))
            setPadding(dp(12), dp(4), dp(12), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8)
            }
            setOnClickListener { exitAssistantCompletely() }
        }

        controlBar.addView(statusLabel)
        controlBar.addView(wakeToggleBtn)
        controlBar.addView(exitBtn)

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#040914"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        root.addView(
            controlBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(webView)
        setContentView(root)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(GlaadwinBridge(), "GlaadwinAndroid")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                val host = url.host.orEmpty()

                if (host == "appassets.androidplatform.net" ||
                    host.endsWith(".firebaseapp.com") ||
                    host.endsWith(".web.app")
                ) {
                    return false
                }

                openExternalUrl(url)
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val needsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    if (!needsAudio) {
                        request.deny()
                        return@runOnUiThread
                    }

                    if (hasMicPermission()) {
                        request.grant(request.resources)
                    } else {
                        pendingWebPermission = request
                        requestRequiredPermissions()
                    }
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val tempWebView = WebView(this@MainActivity)
                tempWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        openExternalUrl(request.url)
                        v.destroy()
                        return true
                    }
                }
                transport.webView = tempWebView
                resultMsg.sendToTarget()
                return true
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    moveTaskToBack(true)
                }
            }
        })

        requestRequiredPermissions()

        val startFile = resolveStartAssetFile()
        webView.loadUrl("https://appassets.androidplatform.net/assets/${Uri.encode(startFile)}")

        handleWakeIntent(intent)
        refreshWakeUi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Pause background Vosk mic while app is open so HTML voice commands work cleanly
        if (WakeWordService.isRunning) {
            sendServiceAction(WakeWordService.ACTION_PAUSE)
        }
        refreshWakeUi()
    }

    override fun onPause() {
        super.onPause()
        // Resume "Hey Glaadwin" listening when user leaves the app
        if (WakeWordService.isRunning) {
            sendServiceAction(WakeWordService.ACTION_RESUME)
        }
    }

    private fun handleWakeIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(WakeWordService.EXTRA_WAKE_TRIGGERED, false) == true) {
            statusLabel.text = "Awake! Ready for command"
            webView.postDelayed({
                webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('glaadwin-wake'));",
                    null
                )
            }, 300L)
        }
    }

    private fun toggleWakeWord() {
        if (WakeWordService.isRunning) {
            sendServiceAction(WakeWordService.ACTION_STOP)
            WakeWordService.isRunning = false
            refreshWakeUi()
        } else {
            if (hasMicPermission()) {
                startWakeWordService()
            } else {
                startWakeAfterPermission = true
                requestRequiredPermissions()
            }
        }
    }

    private fun startWakeWordService() {
        val intent = Intent(this, WakeWordService::class.java).apply {
            action = WakeWordService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        WakeWordService.isRunning = true
        // Pause immediately while user is still inside the app; it resumes on minimize
        sendServiceAction(WakeWordService.ACTION_PAUSE)
        refreshWakeUi()
    }

    private fun sendServiceAction(actionName: String) {
        val intent = Intent(this, WakeWordService::class.java).apply {
            action = actionName
        }
        startService(intent)
    }

    private fun refreshWakeUi() {
        if (WakeWordService.isRunning) {
            statusLabel.text = "Hey Glaadwin: ON (active when minimized)"
            wakeToggleBtn.text = "Stop Wake"
        } else {
            statusLabel.text = "Hey Glaadwin: OFF"
            wakeToggleBtn.text = "Start Wake"
        }
    }

    private fun exitAssistantCompletely() {
        if (WakeWordService.isRunning) {
            sendServiceAction(WakeWordService.ACTION_STOP)
            WakeWordService.isRunning = false
        }
        finishAndRemoveTask()
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestRequiredPermissions() {
        val needed = mutableListOf<String>()
        if (!hasMicPermission()) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotif = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasNotif) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            permissionsLauncher.launch(needed.toTypedArray())
        }
    }

    private fun resolveStartAssetFile(): String {
        val files = assets.list("") ?: emptyArray()
        if ("index.html" in files) return "index.html"
        return files.firstOrNull { it.endsWith(".html", ignoreCase = true) } ?: "index.html"
    }

    private fun openExternalUrl(uri: Uri) {
        try {
            CustomTabsIntent.Builder().build().launchUrl(this, uri)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: Exception) {
            }
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    inner class GlaadwinBridge {
        @JavascriptInterface
        fun isAndroidApp(): Boolean = true

        @JavascriptInterface
        fun startWakeWord() {
            runOnUiThread {
                if (!WakeWordService.isRunning) toggleWakeWord()
            }
        }

        @JavascriptInterface
        fun stopWakeWord() {
            runOnUiThread {
                if (WakeWordService.isRunning) toggleWakeWord()
            }
        }

        @JavascriptInterface
        fun closeApp() {
            runOnUiThread { exitAssistantCompletely() }
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}

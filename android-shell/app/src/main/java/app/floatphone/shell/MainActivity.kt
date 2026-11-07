package app.floatphone.shell

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        const val VERSION = "1.0.0"
        /** 网页版地址；替换为你自己的 Netlify / Vercel 部署域名 */
        const val SITE_URL = "https://delightful-sopapillas-a8b9d5.netlify.app"
        /** Deep link / Push 唤起时传递的直达 URL */
        const val EXTRA_OPEN_URL = "app.floatphone.shell.OPEN_URL"
    }

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingWebPermissionRequest: android.webkit.PermissionRequest? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        PushService.start(this)
    }

    private val webPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val req = pendingWebPermissionRequest ?: return@registerForActivityResult
        pendingWebPermissionRequest = null
        if (grants.values.all { it }) {
            val supported = req.resources.filter { webResourcePermissions(it).isNotEmpty() }
            req.grant(supported.toTypedArray())
        } else {
            req.deny()
        }
    }

    private fun webResourcePermissions(resource: String): List<String> = when (resource) {
        android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE -> listOf(Manifest.permission.RECORD_AUDIO)
        android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE -> listOf(Manifest.permission.CAMERA)
        else -> emptyList()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            userAgentString = "${userAgentString} FloatShell/$VERSION"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

        webView.addJavascriptInterface(ShellBridge(), "AndroidShell")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val scheme = url.scheme ?: return false
                // 站内导航留在壳里；http(s) 外链和自定义协议（shortcuts:// 等）交给系统
                if (scheme == "http" || scheme == "https") {
                    if (url.host == Uri.parse(SITE_URL).host) return false
                    return runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, url)); true
                    }.getOrDefault(true)
                }
                return runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, url)); true
                }.getOrDefault(true)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 页面加载完成后，从 IndexedDB (AiPhoneKvDB) 提取个人云配置同步给原生推送服务
                injectPushCheck()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                val supported = request.resources.filter { webResourcePermissions(it).isNotEmpty() }
                if (supported.isEmpty()) { request.deny(); return }
                val missing = supported.flatMap { webResourcePermissions(it) }
                    .distinct()
                    .filter { ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) { request.grant(supported.toTypedArray()); return }
                if (pendingWebPermissionRequest != null) { request.deny(); return }
                pendingWebPermissionRequest = request
                webPermissionLauncher.launch(missing.toTypedArray())
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                filePathCallback?.onReceiveValue(emptyArray())
                filePathCallback = callback
                return runCatching {
                    fileChooserLauncher.launch(params.createIntent()); true
                }.getOrElse {
                    filePathCallback = null; false
                }
            }
        }

        // 备份导出等下载：交给系统下载管理器，落到公共下载目录
        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            runCatching {
                if (url.startsWith("blob:") || url.startsWith("data:")) {
                    // blob/data 由页面内 JS 触发的 a[download] 处理；提示用户等待
                    Toast.makeText(this, "正在导出...", Toast.LENGTH_SHORT).show()
                    return@DownloadListener
                }
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    addRequestHeader("User-Agent", userAgent)
                    addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType),
                    )
                }
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                Toast.makeText(this, "已开始下载到「下载」目录", Toast.LENGTH_SHORT).show()
            }
        })

        // 物理返回键处理：优先通过 JS 查找并点击网页顶层返回按钮；根界面双击退出防误触
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            private var lastBackTime = 0L

            override fun handleOnBackPressed() {
                val jsBack = """
                    (function() {
                        const selectors = [
                            'button[aria-label="返回"]',
                            'button[aria-label="关闭"]',
                            'button.back-btn',
                            '[data-action="back"]',
                            'button:has(svg.lucide-chevron-left)',
                            'button:has(svg.lucide-arrow-left)'
                        ];
                        for (let s of selectors) {
                            try {
                                const el = document.querySelector(s);
                                if (el && el.offsetParent !== null) {
                                    el.click();
                                    return true;
                                }
                            } catch(e) {}
                        }
                        const buttons = Array.from(document.querySelectorAll('button, a'));
                        for (let i = buttons.length - 1; i >= 0; i--) {
                            const b = buttons[i];
                            if (b.offsetParent !== null) {
                                const text = b.innerText?.trim();
                                if (text === '‹' || text === '返回' || text === '<') {
                                    b.click();
                                    return true;
                                }
                            }
                        }
                        return false;
                    })();
                """.trimIndent()

                webView.evaluateJavascript(jsBack) { handled ->
                    if (handled == "true") return@evaluateJavascript
                    if (webView.canGoBack()) {
                        webView.goBack()
                        return@evaluateJavascript
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastBackTime < 2000) {
                        moveTaskToBack(true)
                    } else {
                        lastBackTime = now
                        Toast.makeText(this@MainActivity, "再按一次退出小手机", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })

        // 冷启动带深链（如来电接听）直接加载目标；否则加载首页
        webView.loadUrl(consumeOpenUrl(intent) ?: SITE_URL)
        ensurePushService()
    }

    /** singleTask：App 已在运行时（如全屏来电页接听）通过 onNewIntent 送达深链 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val target = consumeOpenUrl(intent) ?: return
        // SPA 已加载：loadUrl 到同页 hash 只触发 hashchange，不会整页重载
        webView.loadUrl(target)
    }

    private fun consumeOpenUrl(intent: Intent?): String? {
        val target = intent?.getStringExtra(EXTRA_OPEN_URL) ?: return null
        intent.removeExtra(EXTRA_OPEN_URL)
        return target.takeIf { it.startsWith(SITE_URL) }
    }

    private fun ensurePushService() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            perms.add(Manifest.permission.RECORD_AUDIO)
        }
        if (perms.isNotEmpty()) {
            notifPermissionLauncher.launch(perms.toTypedArray())
        } else {
            PushService.start(this)
        }
    }

    /**
     * 从网页的 IndexedDB (AiPhoneKvDB -> entries) 中读取个人云配置
     * `ai_phone_cloud_backup_config_v1` 并同步给原生 PushService，
     * 同时将 `push_account_subscribed_v1` 设为已订阅，防止网页端拦截报错“请先打开离线推送”。
     */
    private fun injectPushCheck() {
        val js = """
            (function() {
                if (window.__shellPushSyncTimer) return;
                function syncFromIdb() {
                    try {
                        const req = indexedDB.open('AiPhoneKvDB');
                        req.onsuccess = function(e) {
                            const db = e.target.result;
                            if (!db.objectStoreNames.contains('entries')) return;
                            const tx = db.transaction('entries', 'readwrite');
                            const store = tx.objectStore('entries');
                            const getBackup = store.get('ai_phone_cloud_backup_config_v1');
                            getBackup.onsuccess = function() {
                                const row = getBackup.result;
                                if (row && row.value) {
                                    if (window.AndroidShell && window.AndroidShell.syncPushConfig) {
                                        window.AndroidShell.syncPushConfig(row.value);
                                    }
                                    // 同步放行网页端的“账号已订阅”门控阀门
                                    store.put({
                                        key: 'push_account_subscribed_v1',
                                        value: JSON.stringify({ subscribed: true, checkedAt: Date.now() })
                                    });
                                }
                            };
                        };
                    } catch(err) {}
                }
                syncFromIdb();
                window.__shellPushSyncTimer = setInterval(syncFromIdb, 15000);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        webView.destroy()
        super.onDestroy()
    }

    /** 暴露给网页的原生桥（网页侧可用 window.AndroidShell 特性检测壳环境）。 */
    inner class ShellBridge {
        @JavascriptInterface
        fun getVersion(): String = VERSION

        /** 打开本应用的系统设置页（引导用户关电池限制、开自启动）。 */
        @JavascriptInterface
        fun openAppSettings() {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        /** 请求忽略电池优化（保活关键一步）。 */
        @SuppressLint("BatteryLife")
        @JavascriptInterface
        fun requestIgnoreBatteryOptimization() {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        /** 供网页在单机模式下直接将个人云配置在手机内存内同步给原生推送服务 */
        @JavascriptInterface
        fun syncPushConfig(rawJson: String) {
            PushService.updateConfigFromWeb(this@MainActivity, rawJson)
        }
    }
}

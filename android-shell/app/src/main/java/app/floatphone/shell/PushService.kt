package app.floatphone.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 推送前台服务：不依赖 Google 服务的自建长连接。
 *
 * 原理：
 * 1. 单机个人云模式：从网页 IndexedDB (AiPhoneKvDB) 同步拿到个人云的
 *    Supabase 地址与密钥，向个人云网关 `/functions/v1/ai-phone-push?action=subscribe`
 *    注册 `shell:owner` 订阅，并用 OkHttp WebSocket 直连 Supabase Realtime
 *    订阅 `shellpush:owner` 频道。
 * 2. 站点多用户模式：用 WebView 里已登录的站点 Cookie 调接口拿到配置，
 *    订阅 `shellpush:<userId>`。
 */
class PushService : Service() {

    companion object {
        private const val CH_KEEPALIVE = "shell_keepalive"
        private const val CH_MESSAGES = "shell_messages"
        private const val CH_CALLS = "shell_calls"
        private const val NOTIF_FG_ID = 1
        private const val PREF_NAME = "shell_push_prefs"
        private var running = false
        private var activeService: PushService? = null

        fun start(context: Context) {
            if (running) return
            val intent = Intent(context, PushService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        /** 供 MainActivity 从网页 IndexedDB 读取到个人云配置时动态写入并重连 */
        fun updateConfigFromWeb(context: Context, rawJson: String) {
            runCatching {
                val obj = JSONObject(rawJson)
                val url = obj.optString("supabaseUrl").ifEmpty { obj.optString("url") }.trim().trimEnd('/')
                val key = obj.optString("anonKey").ifEmpty { obj.optString("key") }.trim()
                // 个人云 gateway.mjs 中 OWNER_ID 固定为 "owner"
                val userId = obj.optString("userId").ifEmpty { "owner" }
                if (url.isNotEmpty() && key.isNotEmpty()) {
                    val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    val oldUrl = sp.getString("supabase_url", "")
                    val oldKey = sp.getString("anon_key", "")
                    if (url != oldUrl || key != oldKey) {
                        sp.edit()
                            .putString("supabase_url", url)
                            .putString("anon_key", key)
                            .putString("user_id", userId)
                            .apply()
                        activeService?.reconnectWithLocalConfig()
                    }
                }
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var socket: WebSocket? = null
    private var stopped = false
    private var msgSeq = 2
    private var notifId = 100
    private var shellSubRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        activeService = this
        createChannels()
        startForeground(NOTIF_FG_ID, buildKeepAliveNotification("等待连接…"))
        thread(name = "shell-push-loop") { connectionLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        stopped = true
        running = false
        activeService = null
        socket?.cancel()
        super.onDestroy()
    }

    fun reconnectWithLocalConfig() {
        shellSubRegistered = false
        socket?.cancel()
    }

    // ── 连接循环：拿配置 → 连 WS → 断线退避重连 ──
    private fun connectionLoop() {
        var backoffSec = 5L
        while (!stopped) {
            val config = fetchConfig()
            if (config == null) {
                updateKeepAlive("等待个人云配置同步…")
                sleepSec(10); continue
            }
            updateKeepAlive("已连接，等待角色消息")
            val closedNormally = runSocket(config)
            if (stopped) break
            updateKeepAlive("连接断开，重连中…")
            sleepSec(if (closedNormally) 3 else backoffSec)
            backoffSec = (backoffSec * 2).coerceAtMost(120)
            if (closedNormally) backoffSec = 5
        }
    }

    private data class PushConfig(val supabaseUrl: String, val anonKey: String, val userId: String)

    /** 优先读本地个人云配置（单机模式）；若无则借 WebView Cookie 调站点接口获取（多用户模式）。 */
    private fun fetchConfig(): PushConfig? = runCatching {
        // 1. 优先读取从 IndexedDB (AiPhoneKvDB) 同步过来的个人云配置
        val sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val localUrl = sp.getString("supabase_url", null)
        val localKey = sp.getString("anon_key", null)
        val localUser = sp.getString("user_id", "owner") ?: "owner"
        if (!localUrl.isNullOrEmpty() && !localKey.isNullOrEmpty()) {
            val cleanUrl = localUrl.trimEnd('/')
            registerPersonalCloudShellSubscription(cleanUrl, localKey, localUser)
            return@runCatching PushConfig(cleanUrl, localKey, localUser)
        }

        // 2. 本地暂无时，尝试原版站点 Cookie 接口
        val cookie = CookieManager.getInstance().getCookie(MainActivity.SITE_URL) ?: return null

        fun getJson(path: String): JSONObject? {
            val request = Request.Builder()
                .url("${MainActivity.SITE_URL}$path")
                .header("Cookie", cookie)
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                return JSONObject(response.body?.string() ?: return null)
            }
        }

        val me = getJson("/api/auth/me") ?: return null
        val userId = me.optJSONObject("account")?.optString("id").orEmpty()
        if (userId.isEmpty()) return null
        val online = getJson("/api/online/config") ?: return null
        if (!online.optBoolean("configured")) return null
        val url = online.optString("supabaseUrl")
        val key = online.optString("anonKey")
        if (url.isEmpty() || key.isEmpty()) return null
        registerShellSubscription(cookie, userId)
        PushConfig(url.trimEnd('/'), key, userId)
    }.getOrNull()

    /**
     * 在个人云网关（/functions/v1/ai-phone-push?action=subscribe）注册一条合成推送订阅（endpoint = shell:owner）。
     * 这样 push-generate.mjs 发现 hasShellSub 为 true 时，就会往 realtime:shellpush:owner 广播离线消息与语音来电。
     */
    private fun registerPersonalCloudShellSubscription(supabaseUrl: String, key: String, userId: String) {
        if (shellSubRegistered) return
        runCatching {
            val body = JSONObject()
                .put("endpoint", "shell:$userId")
                .put(
                    "keys",
                    JSONObject().put("p256dh", "shell").put("auth", "shell"),
                )
                .toString()
                .toRequestBody("application/json".toMediaType())

            // 先尝试通过个人云网关 action=subscribe 写入
            val gwRequest = Request.Builder()
                .url("$supabaseUrl/functions/v1/ai-phone-push?action=subscribe")
                .header("apikey", key)
                .header("Authorization", "Bearer $key")
                .header("x-ai-phone-service-key", key)
                .post(body)
                .build()
            val gwOk = client.newCall(gwRequest).execute().use { response ->
                response.isSuccessful
            }
            if (gwOk) {
                shellSubRegistered = true
                return@runCatching
            }

            // 兜底：直接向 PostgREST push_subscriptions 表 upsert
            val restBody = JSONArray().put(
                JSONObject()
                    .put("endpoint", "shell:$userId")
                    .put("user_id", userId)
                    .put("p256dh", "shell")
                    .put("auth", "shell")
                    .put("fail_count", 0),
            ).toString().toRequestBody("application/json".toMediaType())

            val restRequest = Request.Builder()
                .url("$supabaseUrl/rest/v1/push_subscriptions?on_conflict=endpoint")
                .header("apikey", key)
                .header("Authorization", "Bearer $key")
                .header("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(restBody)
                .build()
            client.newCall(restRequest).execute().use { response ->
                if (response.isSuccessful) shellSubRegistered = true
            }
        }
    }

    /**
     * 在站点注册一条合成推送订阅（endpoint = shell:<userId>）。
     * 作用是让离线消息排期的"账号已订阅"门控放行，并让服务端知道
     * 要往 shellpush 频道广播；服务端不会对它做 Web Push 投递。
     */
    private fun registerShellSubscription(cookie: String, userId: String) {
        if (shellSubRegistered) return
        runCatching {
            val body = JSONObject()
                .put("endpoint", "shell:$userId")
                .put(
                    "keys",
                    JSONObject().put("p256dh", "shell").put("auth", "shell"),
                )
                .toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${MainActivity.SITE_URL}/api/push/subscribe")
                .header("Cookie", cookie)
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) shellSubRegistered = true
            }
        }
    }

    /** 跑一条 WebSocket 直到断开；返回是否属于正常关闭。 */
    private fun runSocket(config: PushConfig): Boolean {
        val wsUrl = config.supabaseUrl.replaceFirst("http", "ws") +
            "/realtime/v1/websocket?apikey=${config.anonKey}&vsn=1.0.0"
        val topic = "realtime:shellpush:${config.userId}"
        val lock = Object()
        var normal = false
        var done = false

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val join = JSONObject()
                    .put("topic", topic)
                    .put("event", "phx_join")
                    .put("ref", "1")
                    .put(
                        "payload",
                        JSONObject().put(
                            "config",
                            JSONObject()
                                .put("broadcast", JSONObject().put("self", false))
                                .put("presence", JSONObject().put("key", "")),
                        ),
                    )
                webSocket.send(join.toString())
                // Phoenix 心跳（OkHttp pingInterval 是 TCP 层，这里是协议层）
                thread(name = "shell-push-heartbeat") {
                    while (!done && !stopped) {
                        sleepSec(25)
                        if (done || stopped) break
                        runCatching {
                            webSocket.send(
                                JSONObject()
                                    .put("topic", "phoenix")
                                    .put("event", "heartbeat")
                                    .put("payload", JSONObject())
                                    .put("ref", (msgSeq++).toString())
                                    .toString(),
                            )
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val msg = JSONObject(text)
                    if (msg.optString("event") != "broadcast") return
                    val payload = msg.optJSONObject("payload") ?: return
                    if (payload.optString("event") != "notify") return
                    val body = payload.optJSONObject("payload") ?: return
                    val title = body.optString("title").ifEmpty { "小手机" }
                    val text2 = body.optString("body").ifEmpty { "有新消息" }
                    // 来电：全屏来电通知（任何一步失败回落普通通知，主路不受影响）
                    if (body.optString("kind") == "call") {
                        val shown = runCatching {
                            showIncomingCallNotification(
                                body.optString("characterName").ifEmpty { title },
                                body.optString("sessionId"),
                                body.optLong("callTs", System.currentTimeMillis()),
                            )
                        }.isSuccess
                        if (shown) return
                    }
                    showMessageNotification(title, text2)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                normal = true
                synchronized(lock) { done = true; lock.notifyAll() }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(lock) { done = true; lock.notifyAll() }
            }
        }

        socket = client.newWebSocket(
            Request.Builder().url(wsUrl).build(),
            listener,
        )
        synchronized(lock) {
            while (!done && !stopped) runCatching { lock.wait(30_000) }
        }
        socket?.cancel()
        socket = null
        return normal
    }

    // ── 通知 ──
    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CH_KEEPALIVE, "后台连接", NotificationManager.IMPORTANCE_MIN).apply {
                description = "维持角色消息接收通道（可在此关闭常驻通知的显示）"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CH_MESSAGES, "角色消息", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "角色发来的离线消息"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CH_CALLS, "角色来电", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "角色打来的语音电话（只振动，不响铃）"
                setSound(null, null)
                enableVibration(false) // 振动由 CallAlert 循环控制，渠道自带的一次性振动关掉
            },
        )
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildKeepAliveNotification(text: String): Notification =
        NotificationCompat.Builder(this, CH_KEEPALIVE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("小手机")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    private fun updateKeepAlive(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_FG_ID, buildKeepAliveNotification(text))
    }

    /**
     * 全屏来电通知：锁屏/熄屏直接弹 IncomingCallActivity，亮屏时是带
     * 接听/拒接按钮的 heads-up。振动循环 + 55s 超时未接由 CallAlert 管。
     */
    private fun showIncomingCallNotification(characterName: String, sessionId: String, callTs: Long) {
        val fullScreen = Intent(this, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(IncomingCallActivity.EXTRA_SESSION_ID, sessionId)
            putExtra(IncomingCallActivity.EXTRA_CHARACTER_NAME, characterName)
            putExtra(IncomingCallActivity.EXTRA_CALL_TS, callTs)
        }
        val fullScreenPending = PendingIntent.getActivity(
            this, 60, fullScreen,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun buildAction(actionName: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
            this, code,
            Intent(this, CallActionReceiver::class.java).apply {
                action = actionName
                putExtra(CallActionReceiver.EXTRA_SESSION_ID, sessionId)
                putExtra(CallActionReceiver.EXTRA_CALL_TS, callTs)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CH_CALLS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(characterName)
            .setContentText("语音来电…")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPending, true)
            .setContentIntent(fullScreenPending)
            .addAction(0, "拒接", buildAction(CallActionReceiver.ACTION_DECLINE, 61))
            .addAction(0, "接听", buildAction(CallActionReceiver.ACTION_ANSWER, 62))
            .build()
        getSystemService(NotificationManager::class.java).notify(CallAlert.NOTIF_CALL_ID, notification)
        CallAlert.start(this, sessionId, characterName) {
            // 超时未接：收场 + 换一条"未接来电"普通通知（正文消息本来就会进聊天）
            CallAlert.stop(this)
            runCatching { showMissedCallNotification(characterName) }
        }
    }

    private fun showMissedCallNotification(characterName: String) {
        val notification = NotificationCompat.Builder(this, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(characterName)
            .setContentText("未接来电")
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        getSystemService(NotificationManager::class.java).notify(CallAlert.NOTIF_MISSED_ID, notification)
    }

    private fun showMessageNotification(title: String, body: String) {
        val notification = NotificationCompat.Builder(this, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        getSystemService(NotificationManager::class.java).notify(notifId++, notification)
        if (notifId > 400) notifId = 100
    }

    private fun sleepSec(sec: Long) {
        runCatching { Thread.sleep(sec * 1000) }
    }
}

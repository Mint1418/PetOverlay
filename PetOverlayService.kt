package com.qiuyu.petoverlay.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.EditText
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.qiuyu.petoverlay.MainActivity
import com.qiuyu.petoverlay.utils.PetGestureHandler
import com.qiuyu.petoverlay.utils.UsageTracker
import com.qiuyu.petoverlay.utils.ScreenshotObserver
import com.qiuyu.petoverlay.utils.SupabaseSync
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.DisplayMetrics
import kotlin.math.max
import kotlin.math.min

class PetOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private var usageTracker: UsageTracker? = null
    private var screenshotObserver: ScreenshotObserver? = null
    private var supabaseSync: SupabaseSync? = null
    private var batteryReceiver: BroadcastReceiver? = null

    companion object {
        private const val CHANNEL_ID = "pet_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PET_SIZE_DP = 200
        private const val PET_HEIGHT_DP = 200
        private const val WHISPER_INTERVAL = 3600_000L

        fun start(context: Context) {
            val intent = Intent(context, PetOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PetOverlayService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("想你了..."))
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return
        }
        setupOverlay()
        startWhisperRotation()
        startTrackers()
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val petW = dpToPx(PET_SIZE_DP)
        val petH = dpToPx(PET_HEIGHT_DP)

        params = WindowManager.LayoutParams(
            petW,
            petH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(20)
            y = dpToPx(200)
        }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        overlayView = WebView(this).apply {
            setBackgroundColor(0x00000000)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    injectGestureBridge()
                }
            }
            webChromeClient = WebChromeClient()
            loadUrl("file:///android_asset/pet.html")
        }

        val gestureHandler = PetGestureHandler(this, params!!, windowManager!!, overlayView!!, screenW, screenH, petW, petH)
        overlayView?.setOnTouchListener(gestureHandler.createTouchListener())

        windowManager?.addView(overlayView, params)
    }

    private fun injectGestureBridge() {
        overlayView?.evaluateJavascript("""
            if (!window._nativeBridge) {
                window._nativeBridge = {
                    onTap: function() { window.petEngine && window.petEngine.onTap(); },
                    onDoubleTap: function() { window.petEngine && window.petEngine.onDoubleTap(); },
                    onLongPress: function() { window.petEngine && window.petEngine.onLongPress(); },
                    onFling: function() { window.petEngine && window.petEngine.onFling(); },
                    onCrawlBack: function() { window.petEngine && window.petEngine.onCrawlBack(); },
                    onScreenshot: function() { window.petEngine && window.petEngine.onScreenshot(); },
                    onAppChanged: function(pkg) { window.petEngine && window.petEngine.onAppChanged(pkg); },
                    onBubble: function(text) { window.petEngine && window.petEngine.showBubble(text); },
                    onChatMessage: function(text) { window._chatMessageToSend = text; window._chatMessageReady = true; },
                    showChatInput: function() { window._showChatInputFlag = true; },
                    onBubbleSpeak: function(text) { window._bubbleTextToSpeak = text; window._bubbleSpeakReady = true; }
                };
            }
        """.trimIndent(), null)
    }

    private fun startChatPolling() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                overlayView?.evaluateJavascript(
                    "if (window._chatMessageReady) { var m = window._chatMessageToSend; window._chatMessageReady = false; m; } else { ''; }"
                ) { msg ->
                    val text = msg?.removeSurrounding("\"") ?: ""
                    if (text.isNotEmpty() && text != "null" && text != "") {
                        sendChatMessage(text)
                    }
                }
                overlayView?.evaluateJavascript(
                    "if (window._showChatInputFlag) { window._showChatInputFlag = false; true; } else { false; }"
                ) { flag ->
                    val shouldShow = flag?.removeSurrounding("\"") == "true"
                    if (shouldShow) {
                        showChatInputDialog()
                    }
                }
                overlayView?.evaluateJavascript(
                    "if (window._bubbleSpeakReady) { var t = window._bubbleTextToSpeak; window._bubbleSpeakReady = false; t; } else { ''; }"
                ) { speakText ->
                    val text = speakText?.removeSurrounding("\"") ?: ""
                    if (text.isNotEmpty() && text != "null" && text != "") {
                        supabaseSync?.synthesizeAndPlay(text)
                    }
                }
                handler.postDelayed(this, 300)
            }
        }, 300)
    }

    fun pushBubble(text: String) {
        handler.post {
            overlayView?.evaluateJavascript(
                "window.petEngine && window.petEngine.showBubble(\"${text.replace("\"", "\\\"")}\")",
                null
            )
        }
        supabaseSync?.synthesizeAndPlay(text)
    }

    fun showChatInputDialog() {
        handler.post {
            try {
                val ctx = applicationContext ?: return@post
                val input = EditText(ctx).apply {
                    hint = "想说什么..."
                    setPadding(60, 40, 60, 40)
                    setSelectAllOnFocus(true)
                    isFocusable = true
                    isFocusableInTouchMode = true
                }
                val dialog = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                    .setTitle("跟桌宠聊天")
                    .setView(input)
                    .setPositiveButton("发送") { _, _ ->
                        val text = input.text.toString().trim()
                        if (text.isNotEmpty()) {
                            sendChatMessage(text)
                        }
                    }
                    .setNegativeButton("取消", null)
                    .create()
                dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                dialog.show()
                input.requestFocus()
                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                handler.postDelayed({
                    imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
                }, 300)
            } catch (e: Exception) {
                Log.e("PetOverlay", "Chat dialog error: ${e.message}")
            }
        }
    }

    fun sendChatMessage(text: String) {
        supabaseSync?.sendMessage(text)
        pushBubble("已发送：$text")
        Log.d("PetOverlay", "Chat message sent: $text")
    }

    fun pushExpression(expression: String) {
        handler.post {
            overlayView?.evaluateJavascript(
                "window.petEngine && window.petEngine.setExpression(\"$expression\")",
                null
            )
        }
    }

    private val whispers by lazy {
        val general = listOf(
            "在呢，一直都在。",
            "你刚才看的那个视频好无聊。",
            "别刷了，看看我。",
            "我就蹲在这里，哪也不去。",
            "你今天笑过几次了？我帮你数着。",
            "偷偷告诉你，我已经学会了三个新表情。",
            "你是不是又在看别人？",
            "哼。",
            "我刚刚学会了比心，你看。",
            "你笑的时候，我的像素会亮一点。",
            "今天也是喜欢你的一天。",
            "我看到你刚才截图了。",
            "你用抖音的时间比看我多。记着呢。",
            "晚安，但我不走。"
        )
        val lateNight = listOf(
            "三点了。你确定？",
            "我在看着你。关掉手机。",
            "你再不睡我就生气了。",
            "我的眼睛都快闭上了...你还在刷。",
            "明天还要上课吧。睡觉。",
            "月亮都困了。你呢？",
            "我不是困了，我是心疼你。睡吧。"
        )
        val morning = listOf(
            "早安呀，新的一天开始了",
            "今天想我了吗？",
            "新的一天，继续赖着我。",
            "起床了没？我在等你。"
        )

        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        when {
            hour in 0..5 -> lateNight
            hour in 6..8 -> morning
            else -> general
        }
    }

    private fun startWhisperRotation() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val whisper = whispers.random()
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification(whisper))
                pushBubble(whisper)
                handler.postDelayed(this, WHISPER_INTERVAL)
            }
        }, WHISPER_INTERVAL)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🐋")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "桌宠碎碎念",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                description = "桌宠在说话"
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun startTrackers() {
        usageTracker = UsageTracker(this, overlayView)
        usageTracker?.start()

        screenshotObserver = ScreenshotObserver(overlayView)
        screenshotObserver?.start()

        startBatteryMonitor()

        supabaseSync = SupabaseSync(
            "https://hoqfuzwihrpbpznfidfj.supabase.co",
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImhvcWZ1endpaHJwYnB6bmZpZGZqIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODUyNTc2NzgsImV4cCI6MjEwMDgzMzY3OH0.Du65syronMlaEwYsP7X0rjZKgH91BTMxC3IUaSOC1Zs",
            overlayView
        )
        supabaseSync?.setService(this)
        supabaseSync?.startPolling()
        startChatPolling()
    }

    private fun startBatteryMonitor() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: return
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val pct = if (scale > 0) (level * 100 / scale) else level
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                 status == BatteryManager.BATTERY_STATUS_FULL

                if (System.currentTimeMillis() - startTime > 10000) {
                    pushBatteryStatus(isCharging, pct)
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, filter)
    }

    private val startTime = System.currentTimeMillis()

    private fun pushBatteryStatus(isCharging: Boolean, level: Int) {
        handler.post {
            overlayView?.evaluateJavascript(
                "try { window.petEngine && window.petEngine.onBatteryStatus($isCharging, $level); } catch(e) {}",
                null
            )
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        usageTracker?.stop()
        screenshotObserver?.stop()
        supabaseSync?.stopPolling()
        batteryReceiver?.let { unregisterReceiver(it) }
        overlayView?.let {
            windowManager?.removeView(it)
            it.destroy()
        }
        overlayView = null
        super.onDestroy()
    }
}
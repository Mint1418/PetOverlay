package com.qiuyu.petoverlay.utils

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView

class UsageTracker(
    private val context: Context,
    private val webView: WebView?
) {
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false

    fun start() {
        isRunning = true
        handler.post(object : Runnable {
            override fun run() {
                if (!isRunning) return
                val foregroundPkg = getForegroundPackage()
                if (foregroundPkg != null && foregroundPkg != context.packageName) {
                    webView?.evaluateJavascript(
                        "try { window.petEngine && window.petEngine.onAppChanged(\"$foregroundPkg\"); } catch(e) {}",
                        null
                    )
                }
                handler.postDelayed(this, 3000)
            }
        })
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun getForegroundPackage(): String? {
        return try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val currentTime = System.currentTimeMillis()
            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                currentTime - 1000 * 60,
                currentTime
            )
            if (stats != null) {
                var recentPkg: String? = null
                var recentTime = 0L
                for (usageStats in stats) {
                    if (usageStats.lastTimeUsed > recentTime) {
                        recentTime = usageStats.lastTimeUsed
                        recentPkg = usageStats.packageName
                    }
                }
                recentPkg
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("UsageTracker", "Failed to get foreground app", e)
            null
        }
    }
}
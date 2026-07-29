package com.qiuyu.petoverlay.utils

import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import java.io.File

class ScreenshotObserver(
    private val webView: WebView?
) {
    private val handler = Handler(Looper.getMainLooper())
    private val observers = mutableListOf<FileObserver>()
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true

        // 监听常见的截图目录
        val paths = listOf(
            "/sdcard/Pictures/Screenshots",
            "/sdcard/DCIM/Screenshots",
            "/storage/emulated/0/Pictures/Screenshots",
            "/storage/emulated/0/DCIM/Screenshots"
        )

        for (path in paths) {
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) {
                try {
                    val observer = object : FileObserver(path, FileObserver.CLOSE_WRITE) {
                        override fun onEvent(event: Int, filePath: String?) {
                            if (filePath == null) return
                            if (filePath.endsWith(".png") || filePath.endsWith(".jpg")) {
                                handler.post {
                                    webView?.evaluateJavascript(
                                        "try { window.petEngine && window.petEngine.onScreenshot(); } catch(e) {}",
                                        null
                                    )
                                }
                            }
                        }
                    }
                    observer.startWatching()
                    observers.add(observer)
                    Log.d("ScreenshotObserver", "Watching: $path")
                } catch (e: Exception) {
                    Log.e("ScreenshotObserver", "Failed to watch $path", e)
                }
            }
        }

        if (observers.isEmpty()) {
            Log.w("ScreenshotObserver", "No screenshot directories found")
        }
    }

    fun stop() {
        isRunning = false
        for (observer in observers) {
            try {
                observer.stopWatching()
            } catch (e: Exception) {
                // ignore
            }
        }
        observers.clear()
    }
}
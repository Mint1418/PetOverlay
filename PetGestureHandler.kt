package com.qiuyu.petoverlay.utils

import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import com.qiuyu.petoverlay.service.PetOverlayService
import kotlin.math.abs

class PetGestureHandler(
    private val service: PetOverlayService,
    private val params: WindowManager.LayoutParams,
    private val windowManager: WindowManager,
    private val webView: WebView,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val petWidth: Int,
    private val petHeight: Int
) {
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var isFlinging = false

    private val gestureDetector = GestureDetector(webView.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            service.pushBubble("💕")
            webView.evaluateJavascript(
                "try { window.petEngine && window.petEngine.onDoubleTap(); } catch(e) {}",
                null
            )
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            webView.evaluateJavascript(
                "try { window.petEngine && window.petEngine.onLongPress(); } catch(e) {}",
                null
            )
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            val dx = e2.rawX - (e1?.rawX ?: e2.rawX)
            val dy = e2.rawY - (e1?.rawY ?: e2.rawY)
            if (abs(dx) > 200 && abs(velocityX) > 500) {
                isFlinging = true
                val targetX: Int
                val targetY: Int
                if (dx > 0) {
                    targetX = screenWidth - petWidth
                } else {
                    targetX = -petWidth / 2
                }
                targetY = params.y + (dy * 0.5f).toInt()
                animateFling(targetX, targetY.coerceIn(0, screenHeight - petHeight))
                return true
            }
            return false
        }
    })

    private fun animateFling(targetX: Int, targetY: Int) {
        webView.evaluateJavascript(
            "try { window.petEngine && window.petEngine.onFling(); } catch(e) {}",
            null
        )

        val startX = params.x
        val startY = params.y
        val steps = 20
        var step = 0

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post(object : Runnable {
            override fun run() {
                step++
                if (step > steps || isFlinging == false) {
                    isFlinging = false
                    params.x = targetX.coerceIn(-petWidth / 4, screenWidth - petWidth * 3 / 4)
                    params.y = targetY.coerceIn(0, screenHeight - petHeight)
                    windowManager.updateViewLayout(webView, params)

                    // 爬回来
                    webView.evaluateJavascript(
                        "try { window.petEngine && window.petEngine.onCrawlBack(); } catch(e) {}",
                        null
                    )
                    animateCrawlBack()
                    return
                }
                val progress = step.toFloat() / steps
                params.x = (startX + (targetX - startX) * progress).toInt()
                params.y = (startY + (targetY - startY) * progress).toInt()
                windowManager.updateViewLayout(webView, params)
                handler.postDelayed(this, 30)
            }
        })
    }

    private fun animateCrawlBack() {
        val startX = params.x
        val startY = params.y
        val targetX = 20
        val targetY = 200
        val steps = 60
        var step = 0

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post(object : Runnable {
            override fun run() {
                step++
                if (step > steps) {
                    params.x = targetX
                    params.y = targetY
                    windowManager.updateViewLayout(webView, params)
                    return
                }
                val t = step.toFloat() / steps
                // ease-out
                val ease = 1 - (1 - t) * (1 - t)
                params.x = (startX + (targetX - startX) * ease).toInt()
                params.y = (startY + (targetY - startY) * ease).toInt()
                windowManager.updateViewLayout(webView, params)
                handler.postDelayed(this, 30)
            }
        })
    }

    fun createTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    isFlinging = false
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isDragging = true
                        params.x = (initialX + dx).coerceIn(0, screenWidth - petWidth)
                        params.y = (initialY + dy).coerceIn(0, screenHeight - petHeight)
                        windowManager.updateViewLayout(webView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging && !isFlinging) {
                        webView.evaluateJavascript(
                            "try { window.petEngine && window.petEngine.onTap(); } catch(e) {}",
                            null
                        )
                    }
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }
}
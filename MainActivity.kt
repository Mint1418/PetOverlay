package com.qiuyu.petoverlay

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.qiuyu.petoverlay.service.PetOverlayService

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_OVERLAY = 1001
        private const val REQUEST_CODE_NOTIFICATION = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 不用布局，直接检查权限并启动服务
        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        // 1. 悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("桌宠需要悬浮窗权限才能显示在屏幕上")
                .setPositiveButton("去授权") { _, _ ->
                    val intent = android.content.Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                    startActivityForResult(intent, REQUEST_CODE_OVERLAY)
                }
                .setNegativeButton("退出") { _, _ -> finish() }
                .setCancelable(false)
                .show()
            return
        }

        // 2. Android 13+ 通知权限
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_CODE_NOTIFICATION
                )
                return
            }
        }

        // 3. 权限齐全，启动服务
        startPetService()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_OVERLAY) {
            if (Settings.canDrawOverlays(this)) {
                startPetService()
            } else {
                Toast.makeText(this, "悬浮窗权限被拒绝，无法启动桌宠", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_NOTIFICATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startPetService()
            } else {
                // 通知权限被拒绝，仍然可以启动服务（只是没有通知）
                startPetService()
            }
        }
    }

    private fun startPetService() {
        PetOverlayService.start(this)
        Toast.makeText(this, "桌宠已启动", Toast.LENGTH_SHORT).show()
        finish() // 启动后关闭Activity
    }
}
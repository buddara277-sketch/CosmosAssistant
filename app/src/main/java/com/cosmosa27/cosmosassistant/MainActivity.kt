package com.cosmosa27.cosmosassistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }
        layout.addView(TextView(this).apply {
            text = "COSMOS ASSISTANT\n\nРазреши отображение шара поверх других приложений и доступ к микрофону."
            textSize = 18f
        })

        layout.addView(Button(this).apply {
            text = "Разрешить отображение поверх приложений"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                } else startOverlay()
            }
        })
        layout.addView(Button(this).apply {
            text = "Запустить / показать шар"
            setOnClickListener { startOverlay() }
        })
        layout.addView(Button(this).apply {
            text = "Убрать шар"
            setOnClickListener { stopService(Intent(this@MainActivity, OverlayService::class.java)) }
        })
        setContentView(layout)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 11)
        }
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Сначала разреши отображение поверх приложений", Toast.LENGTH_LONG).show()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Нужно разрешение на микрофон", Toast.LENGTH_LONG).show()
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 11)
            return
        }
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        Toast.makeText(this, "Cosmos Assistant запущен", Toast.LENGTH_SHORT).show()
        finish()
    }
}

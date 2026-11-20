
package com.cosmosa27.cosmosassistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
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
            text = "COSMOS ASSISTANT\n\n" +
                "Твой голосовой помощник.\n\n" +
                "Скажи «Космос», чтобы обратиться к помощнику."
            textSize = 18f
        })

        layout.addView(Button(this).apply {
            text = "Разрешить отображение поверх приложений"

            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                } else {
                    startOverlay()
                }
            }
        })

        layout.addView(Button(this).apply {
            text = "Запустить / показать шар"

            setOnClickListener {
                startOverlay()
            }
        })

        layout.addView(Button(this).apply {
            text = "Убрать шар"

            setOnClickListener {
                stopService(
                    Intent(this@MainActivity, OverlayService::class.java)
                )
            }
        })

        setContentView(layout)

        requestMissingPermissions()
    }

    private fun requestMissingPermissions() {
        val permissions = mutableListOf<String>()

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.READ_CONTACTS)
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), 12)
        }
    }

    private fun startOverlay() {

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Сначала разреши отображение поверх приложений",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(
                this,
                "Нужно разрешение на микрофон",
                Toast.LENGTH_SHORT
            ).show()

            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                11
            )
            return
        }

        val intent = Intent(this, OverlayService::class.java)

        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        Toast.makeText(
            this,
            "Cosmos Assistant запущен",
            Toast.LENGTH_SHORT
        ).show()

        finish()
    }
}

package com.cosmosa27.cosmosassistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var modelStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 40)
        }

        layout.addView(TextView(this).apply {
            text = "COSMOS ASSISTANT\n\nТвой голосовой помощник.\nСкажи «Космос», чтобы обратиться к помощнику."
            textSize = 18f
        }, matchWrap())

        modelStatus = TextView(this).apply {
            textSize = 14f
            setPadding(0, 28, 0, 12)
            text = "Локальная модель: проверка..."
        }
        layout.addView(modelStatus, matchWrap())

        layout.addView(Button(this).apply {
            text = "Выбрать GGUF-модель"
            setOnClickListener { chooseModelFile() }
        }, matchWrap())

        layout.addView(Button(this).apply {
            text = "Загрузить сохранённую модель"
            setOnClickListener { loadSavedModel() }
        }, matchWrap())

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
        }, matchWrap())

        layout.addView(Button(this).apply {
            text = "Запустить / показать шар"
            setOnClickListener { startOverlay() }
        }, matchWrap())

        layout.addView(Button(this).apply {
            text = "Убрать шар"
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
            }
        }, matchWrap())

        setContentView(layout)
        requestMissingPermissions()

        // Если GGUF уже импортировали раньше, загружаем его при открытии приложения.
        QwenAssistant.loadSaved(this) { success, message ->
            modelStatus.text = if (success) "Локальная модель готова: $message" else "Локальная модель: $message"
        }
    }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 12 }

    private fun chooseModelFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/x-gguf"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQUEST_MODEL_FILE)
        } catch (_: Exception) {
            Toast.makeText(this, "Не удалось открыть выбор файла", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Используется совместимый результат выбора файла для Activity без AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MODEL_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        modelStatus.text = "Импортирую и загружаю GGUF-модель. Это может занять несколько минут..."
        QwenAssistant.importAndLoad(this, uri) { success, message ->
            modelStatus.text = if (success) "Локальная модель готова: $message" else "Ошибка модели: $message"
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun loadSavedModel() {
        modelStatus.text = "Загружаю сохранённую модель..."
        QwenAssistant.loadSaved(this) { success, message ->
            modelStatus.text = if (success) "Локальная модель готова: $message" else "Локальная модель: $message"
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun requestMissingPermissions() {
        val permissions = mutableListOf<String>()

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_CONTACTS)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), 12)
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Сначала разреши отображение поверх приложений", Toast.LENGTH_LONG).show()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Нужно разрешение на микрофон", Toast.LENGTH_SHORT).show()
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 11)
            return
        }

        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        Toast.makeText(this, "Cosmos Assistant запущен", Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        private const val REQUEST_MODEL_FILE = 21
    }
}

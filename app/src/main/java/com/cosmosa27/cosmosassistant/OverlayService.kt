package com.cosmosa27.cosmosassistant

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import android.provider.Settings
import java.util.Locale
import kotlin.math.abs

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var orb: View? = null
    private var speech: SpeechRecognizer? = null
    private var listening = false
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var longPressTriggered = false
    private val handler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        longPressTriggered = true
        startListening()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Cosmos Assistant")
            .setContentText("Плавающий помощник активен")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showOrb()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun showOrb() {
        if (orb != null || !Settings.canDrawOverlays(this)) return
        val view = OrbView(this)
        orb = view
        val params = WindowManager.LayoutParams(
            dp(66), dp(66),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(18); y = dp(220)
        }
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    longPressTriggered = false
                    handler.postDelayed(longPressRunnable, 350)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) {
                        handler.removeCallbacks(longPressRunnable)
                        params.x = startX + dx; params.y = startY + dy
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (longPressTriggered) {
                        if (listening) stopListening()
                    } else if (abs(event.rawX - downX) < dp(8) && abs(event.rawY - downY) < dp(8)) {
                        showTextEntry()
                    }
                    true
                }
                else -> true
            }
        }
        windowManager.addView(view, params)
    }

    private fun showTextEntry() {
        val container = FrameLayout(this)
        val edit = EditText(this).apply {
            hint = "Напиши команду…"
            textSize = 17f
            singleLine = true
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val panel = LinearLayoutCompat(this)
        panel.orientation = android.widget.LinearLayout.HORIZONTAL
        panel.setPadding(dp(12), dp(8), dp(8), dp(8))
        panel.background = GradientDrawable().apply {
            setColor(Color.rgb(24, 31, 48)); cornerRadius = dp(22).toFloat()
            setStroke(dp(1), Color.rgb(80, 130, 210))
        }
        val send = TextView(this).apply {
            text = "➤"; textSize = 24f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0)
        }
        panel.addView(edit, android.widget.LinearLayout.LayoutParams(0, dp(54), 1f))
        panel.addView(send, android.widget.LinearLayout.LayoutParams(dp(48), dp(54)))
        container.addView(panel)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, dp(78),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP; y = dp(90) }
        send.setOnClickListener {
            val command = edit.text.toString().trim()
            if (command.isNotEmpty()) AssistantEngine.execute(this, command)
            try { windowManager.removeView(container) } catch (_: Exception) {}
        }
        windowManager.addView(container, p)
        edit.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Распознавание речи недоступно на устройстве", Toast.LENGTH_LONG).show()
            return
        }
        try {
            if (speech == null) speech = SpeechRecognizer.createSpeechRecognizer(this)
            speech?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { Toast.makeText(this@OverlayService, "Слушаю…", Toast.LENGTH_SHORT).show() }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) { listening = false }
                override fun onResults(results: Bundle?) {
                    listening = false
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!text.isNullOrBlank()) AssistantEngine.execute(this@OverlayService, text)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            listening = true
            speech?.startListening(intent)
        } catch (e: Exception) {
            listening = false
            Toast.makeText(this, "Не удалось включить микрофон", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopListening() {
        try { speech?.stopListening() } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "Cosmos Assistant", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { speech?.destroy() } catch (_: Exception) {}
        try { orb?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        orb = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null

    companion object { private const val CHANNEL = "cosmos_assistant_channel" }
}

private class OrbView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f; val r = width * .43f
        paint.shader = RadialGradient(cx - r*.28f, cy - r*.32f, r*1.55f,
            intArrayOf(Color.rgb(220,250,255), Color.rgb(35,170,255), Color.rgb(30,65,160), Color.rgb(12,18,55)),
            floatArrayOf(0f,.28f,.72f,1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2).toFloat()
        paint.color = Color.argb(190, 140, 225, 255)
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(90, 255, 255, 255)
        canvas.drawOval(cx-r*.55f, cy-r*.68f, cx+r*.05f, cy-r*.28f, paint)
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

private class LinearLayoutCompat(context: Context) : android.widget.LinearLayout(context)

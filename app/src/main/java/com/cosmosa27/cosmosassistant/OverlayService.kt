
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
import android.speech.tts.TextToSpeech
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.provider.Settings
import android.media.AudioManager
import android.media.ToneGenerator
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

class OverlayService : Service(), TextToSpeech.OnInitListener {
    private lateinit var windowManager: WindowManager
    private var orb: OrbView? = null
    private var entryWindow: View? = null
    private var speech: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var listening = false
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var longPressTriggered = false
    private val handler = Handler(Looper.getMainLooper())
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 45) }
    private val dimRunnable = Runnable { orb?.setIdle(true) }
    private val longPressRunnable = Runnable {
        longPressTriggered = true
        playTone(true)
        startListening()
    }

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Cosmos Assistant")
            .setContentText("Космический помощник активен")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true).build()
        startForeground(1, notification)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showOrb()

        // Загружаем ранее выбранную модель Qwen
        QwenAssistant.loadSaved(this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale("ru", "RU")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun showOrb() {
        if (orb != null || !Settings.canDrawOverlays(this)) return
        val view = OrbView(this)
        orb = view
        val params = WindowManager.LayoutParams(
            dp(70), dp(70), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(18); y = dp(220) }
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    wakeOrb(); downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y; longPressTriggered = false
                    handler.postDelayed(longPressRunnable, 420); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt(); val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) {
                        handler.removeCallbacks(longPressRunnable)
                        params.x = startX + dx; params.y = startY + dy
                        windowManager.updateViewLayout(view, params)
                    }; true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (longPressTriggered) {
                        if (listening) stopListening()
                    } else if (abs(event.rawX - downX) < dp(8) && abs(event.rawY - downY) < dp(8)) {
                        if (entryWindow != null) closeTextEntry() else showTextEntry()
                    }
                    scheduleDim(); true
                }
                else -> true
            }
        }
        windowManager.addView(view, params)
        scheduleDim()
    }

    private fun showTextEntry() {
        if (entryWindow != null) return
        playTone(true)
        val root = FrameLayout(this)
        val stars = StarPanel(this)
        root.addView(stars, FrameLayout.LayoutParams(-1, -1))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.argb(238, 5, 12, 34)); cornerRadius = dp(25).toFloat()
                setStroke(dp(2), Color.rgb(50, 190, 255))
            }
            elevation = dp(12).toFloat()
        }
        val edit = EditText(this).apply {
            hint = "Скажи или напиши команду…"; setHintTextColor(Color.rgb(145, 177, 205))
            setTextColor(Color.WHITE); textSize = 16f; setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(10), dp(8), dp(8), dp(8))
            background = null
        }
        val send = SendOrbView(this)
        panel.addView(edit, LinearLayout.LayoutParams(0, dp(52), 1f))
        panel.addView(send, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, dp(100),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP; y = dp(82) }
        entryWindow = root
        windowManager.addView(root, lp)
        fun submit() {
            val command = edit.text.toString().trim()
            if (command.isNotEmpty()) runCommand(command)
            closeTextEntry()
        }
        send.setOnClickListener { submit() }
        edit.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        edit.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeTextEntry() {
        val window = entryWindow ?: return
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        window.findFocus()?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
        try { windowManager.removeView(window) } catch (_: Exception) {}
        entryWindow = null
        playTone(false)
        scheduleDim()
    }

    // Передаём команду Qwen и озвучиваем его ответ
    private fun runCommand(command: String) {
        QwenAssistant.ask(this, command) { response ->
            if (response.isNotBlank()) speak(response)
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cosmos-response")
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Распознавание речи недоступно", Toast.LENGTH_LONG).show(); return
        }
        try {
            if (speech == null) speech = SpeechRecognizer.createSpeechRecognizer(this)
            speech?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { Toast.makeText(this@OverlayService, "Слушаю…", Toast.LENGTH_SHORT).show() }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) { listening = false; playTone(false) }
                override fun onResults(results: Bundle?) {
                    listening = false; playTone(false)
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!text.isNullOrBlank()) runCommand(text)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            listening = true
            speech?.startListening(intent)
        } catch (_: Exception) {
            listening = false; Toast.makeText(this, "Не удалось включить микрофон", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopListening() { try { speech?.stopListening() } catch (_: Exception) {} }
    private fun wakeOrb() { handler.removeCallbacks(dimRunnable); orb?.setIdle(false) }
    private fun scheduleDim() { handler.removeCallbacks(dimRunnable); handler.postDelayed(dimRunnable, 9000) }
    private fun playTone(activate: Boolean) {
        try { tone.startTone(if (activate) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK, 105) } catch (_: Exception) {}
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
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        try { tone.release() } catch (_: Exception) {}
        try { entryWindow?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        try { orb?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        entryWindow = null; orb = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
    companion object { private const val CHANNEL = "cosmos_assistant_channel" }
}

private class OrbView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var idle = false
    private val animator = android.animation.ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 14000; repeatCount = android.animation.ValueAnimator.INFINITE
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }; start()
    }
    fun setIdle(value: Boolean) { idle = value; alpha = if (idle) .43f else 1f; invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f; val r = width * .39f
        canvas.save(); canvas.rotate(phase, cx, cy)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2).toFloat(); paint.color = Color.argb(125, 80, 210, 255)
        canvas.drawOval(cx-r*1.18f, cy-r*.42f, cx+r*1.18f, cy+r*.42f, paint)
        canvas.drawOval(cx-r*.42f, cy-r*1.18f, cx+r*.42f, cy+r*1.18f, paint)
        canvas.restore()
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(cx-r*.3f, cy-r*.34f, r*1.55f,
            intArrayOf(Color.WHITE, Color.rgb(105,225,255), Color.rgb(22,123,230), Color.rgb(8,20,75)),
            floatArrayOf(0f,.22f,.66f,1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, paint); paint.shader = null
        paint.color = Color.argb(125, 255,255,255)
        canvas.drawOval(cx-r*.58f, cy-r*.72f, cx+r*.02f, cy-r*.34f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2).toFloat(); paint.color = Color.argb(210,125,235,255)
        canvas.drawCircle(cx, cy, r, paint); paint.style = Paint.Style.FILL
        for (i in 0..5) {
            val a = Math.toRadians((phase + i*60).toDouble())
            paint.color = Color.argb(170, 120,230,255)
            canvas.drawCircle(cx + kotlin.math.cos(a).toFloat()*r*1.22f, cy + kotlin.math.sin(a).toFloat()*r*1.22f, 1.6f * resources.displayMetrics.density, paint)
        }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }
}

private class SendOrbView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val r = width.coerceAtMost(height) * .43f; val x = width/2f; val y = height/2f
        p.shader = RadialGradient(x-r*.3f,y-r*.35f,r*1.5f,intArrayOf(Color.WHITE,Color.rgb(65,205,255),Color.rgb(10,80,190)),null,Shader.TileMode.CLAMP)
        canvas.drawCircle(x,y,r,p); p.shader = null
        p.color = Color.WHITE; p.textSize = r*1.05f; p.textAlign = Paint.Align.CENTER
        canvas.drawText("➤",x,y+r*.34f,p)
    }
}

private class StarPanel(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stars = List(85) { Triple(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()*1.5f+.5f) }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(3,8,25))
        p.shader = RadialGradient(width*.75f,height*.35f,width*.8f,intArrayOf(Color.argb(95,20,80,150),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),p); p.shader=null
        p.color = Color.argb(205,205,230,255)
        stars.forEach { (x,y,r) -> canvas.drawCircle(x*width,y*height,r,p) }
        p.style=Paint.Style.STROKE; p.strokeWidth=2f; p.color=Color.argb(210,40,165,255)
        canvas.drawRoundRect(1f,1f,width-1f,height-1f,dp(25).toFloat(),dp(25).toFloat(),p); p.style=Paint.Style.FILL
    }
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}

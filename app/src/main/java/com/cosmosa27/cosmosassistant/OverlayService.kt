
package com.cosmosa27.cosmosassistant

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.provider.Settings
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

class OverlayService : Service(), TextToSpeech.OnInitListener {

    private lateinit var windowManager: WindowManager
    private lateinit var audioManager: AudioManager

    private var orb: OrbView? = null
    private var entryWindow: View? = null
    private var speech: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    private var listening = false
    private var destroyed = false
    private var commandInProgress = false
    private var ttsSpeaking = false
    private var ttsReady = false
    private var wakeWordEnabled = true
    private var activeWakeRecognition = false
    private var permissionWarningShown = false

    private var speechSession = 0
    private var commandSession = 0
    private var utteranceCounter = 0

    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var longPressTriggered = false

    private val handler = Handler(Looper.getMainLooper())

    private val tone by lazy {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 45)
    }

    private val dimRunnable = Runnable {
        orb?.setIdle(true)
    }

    private val wakeRestartRunnable = Runnable {
        startWakeWordListening()
    }

        private val speechTimeout = Runnable {
        if (listening) {
            val wasWakeRecognition = activeWakeRecognition

            listening = false
            speechSession++

            try {
                speech?.cancel()
            } catch (_: Exception) {
            }

            // В фоновом режиме ожидания не подаём сигнал окончания.
            if (!wasWakeRecognition) {
                playTone(false)
            }

            if (wasWakeRecognition) {
                scheduleWakeListening(1200)
            } else {
                Toast.makeText(
                    this,
                    "Время ожидания речи истекло",
                    Toast.LENGTH_SHORT
                ).show()

                scheduleWakeListening(800)
            }
        }
    }

    private val longPressRunnable = Runnable {
        longPressTriggered = true
        playTone(true)
        startListening()
    }

    override fun onCreate() {
        super.onCreate()

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        tts = TextToSpeech(this, this)

        createNotificationChannel()

        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Cosmos Assistant")
            .setContentText("Ожидаю слово «Космос»")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        startForeground(1, notification)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        showOrb()


        // Запускаем ожидание ключевого слова после создания сервиса.
        scheduleWakeListening(1800)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("ru", "RU"))

            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                Toast.makeText(
                    this,
                    "Русский язык TTS недоступен",
                    Toast.LENGTH_LONG
                ).show()
            }

            ttsReady = true

            tts?.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {

                    override fun onStart(utteranceId: String?) {
                        handler.post {
                            ttsSpeaking = true
                            handler.removeCallbacks(wakeRestartRunnable)
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        handler.post {
                            ttsSpeaking = false
                            releaseAudioFocus()
                            scheduleWakeListening(700)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        handler.post {
                            ttsSpeaking = false
                            releaseAudioFocus()
                            scheduleWakeListening(900)
                        }
                    }

                    override fun onError(
                        utteranceId: String?,
                        errorCode: Int
                    ) {
                        handler.post {
                            ttsSpeaking = false
                            releaseAudioFocus()
                            scheduleWakeListening(900)
                        }
                    }
                }
            )

            scheduleWakeListening(1200)
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int = START_STICKY

    // ---------------------------------------------------------
    // ФОНОВОЕ ОЖИДАНИЕ СЛОВА «КОСМОС»
    // ---------------------------------------------------------

    private fun scheduleWakeListening(delay: Long = 900) {
        handler.removeCallbacks(wakeRestartRunnable)

        if (destroyed || !wakeWordEnabled) return

        handler.postDelayed(wakeRestartRunnable, delay)
    }

    private fun startWakeWordListening() {
        if (destroyed ||
            !wakeWordEnabled ||
            listening ||
            commandInProgress ||
            ttsSpeaking ||
            entryWindow != null
        ) {
            return
        }

        if (!hasMicrophonePermission()) {
            if (!permissionWarningShown) {
                permissionWarningShown = true

                Toast.makeText(
                    this,
                    "Для голосового управления разреши доступ к микрофону",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        startSpeechRecognition(wakeMode = true)
    }

    private fun hasMicrophonePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkSelfPermission(
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun handleWakeWordResult(recognizedText: String) {
        val normalized = recognizedText
            .lowercase(Locale("ru", "RU"))
            .replace('ё', 'е')
            .trim()

        val wakeIndex = normalized.indexOf("космос")

        if (wakeIndex < 0) {
            scheduleWakeListening(700)
            return
        }

        // Берём всё, что пользователь произнёс после слова активации.
        val command = normalized
            .substring(wakeIndex + "космос".length)
            .trim()
            .trim(',', '.', '!', '?', ':', ';')

        wakeOrb()
        playTone(true)

        if (command.isNotBlank()) {
            runCommand(command)
        } else {
            // Если сказали только «Космос», включаем обычное
            // распознавание команды.
            handler.postDelayed({
                if (!destroyed && !commandInProgress) {
                    startListening()
                }
            }, 350)
        }
    }

    // ---------------------------------------------------------
    // ПЛАВАЮЩАЯ СФЕРА
    // ---------------------------------------------------------

    private fun showOrb() {
        if (orb != null || !Settings.canDrawOverlays(this)) return

        val view = OrbView(this)
        orb = view

        val params = WindowManager.LayoutParams(
            dp(70),
            dp(70),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(18)
            y = dp(220)
        }

        view.setOnTouchListener { _, event ->

            when (event.action) {

                MotionEvent.ACTION_DOWN -> {
                    wakeOrb()

                    downX = event.rawX
                    downY = event.rawY

                    startX = params.x
                    startY = params.y

                    longPressTriggered = false

                    handler.postDelayed(longPressRunnable, 420)

                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()

                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) {
                        handler.removeCallbacks(longPressRunnable)

                        params.x = startX + dx
                        params.y = startY + dy

                        try {
                            windowManager.updateViewLayout(view, params)
                        } catch (_: Exception) {
                        }
                    }

                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {

                    handler.removeCallbacks(longPressRunnable)

                    if (longPressTriggered) {
                        if (listening) stopListening()
                    } else if (
                        abs(event.rawX - downX) < dp(8) &&
                        abs(event.rawY - downY) < dp(8)
                    ) {
                        if (entryWindow != null) {
                            closeTextEntry()
                        } else {
                            showTextEntry()
                        }
                    }

                    scheduleDim()
                    true
                }

                else -> true
            }
        }

        windowManager.addView(view, params)
        scheduleDim()
    }

    // ---------------------------------------------------------
    // ВВОД ТЕКСТА
    // ---------------------------------------------------------

    private fun showTextEntry() {
        if (entryWindow != null) return

        // Не оставляем фоновое распознавание работать одновременно
        // с вводом текста.
        cancelCurrentRecognition()

        playTone(true)

        val root = FrameLayout(this)

        val stars = StarPanel(this)
        root.addView(stars, FrameLayout.LayoutParams(-1, -1))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            setPadding(
                dp(12),
                dp(8),
                dp(10),
                dp(8)
            )

            background = GradientDrawable().apply {
                setColor(Color.argb(238, 5, 12, 34))
                cornerRadius = dp(25).toFloat()
                setStroke(dp(2), Color.rgb(50, 190, 255))
            }

            elevation = dp(12).toFloat()
        }

        val edit = EditText(this).apply {
            hint = "Скажи или напиши команду…"
            setHintTextColor(Color.rgb(145, 177, 205))
            setTextColor(Color.WHITE)
            textSize = 16f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(10), dp(8), dp(8), dp(8))
            background = null
        }

        val send = SendOrbView(this)

        panel.addView(
            edit,
            LinearLayout.LayoutParams(0, dp(52), 1f)
        )

        panel.addView(
            send,
            LinearLayout.LayoutParams(dp(48), dp(48))
        )

        root.addView(
            panel,
            FrameLayout.LayoutParams(-1, -2, Gravity.CENTER)
        )

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(100),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = dp(82)
        }

        entryWindow = root
        windowManager.addView(root, lp)

        fun submit() {
            val command = edit.text.toString().trim()

            if (command.isNotEmpty()) {
                runCommand(command)
            }

            closeTextEntry()
        }

        send.setOnClickListener {
            submit()
        }

        edit.setOnEditorActionListener { _, action, _ ->
            if (
                action == EditorInfo.IME_ACTION_SEND ||
                action == EditorInfo.IME_ACTION_DONE
            ) {
                submit()
                true
            } else {
                false
            }
        }

        edit.requestFocus()

        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeTextEntry() {
        val window = entryWindow ?: return

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

        window.findFocus()?.let {
            imm.hideSoftInputFromWindow(it.windowToken, 0)
        }

        try {
            windowManager.removeView(window)
        } catch (_: Exception) {
        }

        entryWindow = null

        playTone(false)
        scheduleDim()
        scheduleWakeListening(900)
    }

    // ---------------------------------------------------------
    // ВЫПОЛНЕНИЕ КОМАНД
    // ---------------------------------------------------------

    private fun runCommand(command: String) {
        if (destroyed || command.isBlank()) return

        if (commandInProgress) {
            Toast.makeText(
                this,
                "Кос ещё выполняет предыдущую команду",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        handler.removeCallbacks(wakeRestartRunnable)

        if (listening) {
            cancelCurrentRecognition()
        }

        commandInProgress = true
        val currentCommandSession = ++commandSession

        try {
            // Единый голосовой маршрут: простые команды обработает Kotlin,
            // сложные Qwen спланирует и передаст исполнителю через CosToolRouter.
            QwenAssistant.ask(this, command) { result ->
                handler.post {
                    if (destroyed || currentCommandSession != commandSession) {
                        return@post
                    }

                    commandInProgress = false
                    speak(result)
                }
            }
        } catch (_: Exception) {
            if (currentCommandSession == commandSession) {
                commandInProgress = false
                speak("Не удалось выполнить команду.")
            }
        }
    }
    // ---------------------------------------------------------
    // УПРАВЛЕНИЕ МУЗЫКОЙ И ОЗВУЧИВАНИЕМ
    // ---------------------------------------------------------

    private fun requestAudioFocus() {
        if (hasAudioFocus) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                val request = AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
                    .setAudioAttributes(attributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(
                        { _ -> },
                        handler
                    )
                    .build()

                audioFocusRequest = request

                hasAudioFocus =
                    audioManager.requestAudioFocus(request) ==
                        AudioManager.AUDIOFOCUS_REQUEST_GRANTED

            } else {

                @Suppress("DEPRECATION")
                hasAudioFocus =
                    audioManager.requestAudioFocus(
                        { },
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                    ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }

        } catch (_: Exception) {
            hasAudioFocus = false
        }
    }

    private fun releaseAudioFocus() {
        if (!hasAudioFocus) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let {
                    audioManager.abandonAudioFocusRequest(it)
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (_: Exception) {
        }

        audioFocusRequest = null
        hasAudioFocus = false
    }

    private fun speak(text: String) {
        if (destroyed || text.isBlank()) {
            scheduleWakeListening(700)
            return
        }

        handler.removeCallbacks(wakeRestartRunnable)

        val engine = tts

        if (engine == null || !ttsReady) {
            scheduleWakeListening(1500)
            return
        }

        // На время ответа не слушаем микрофон.
        if (listening) {
            cancelCurrentRecognition()
        }

        ttsSpeaking = true
        requestAudioFocus()

        try {
            val utteranceId = "cosmos-response-${++utteranceCounter}"

            val result = engine.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                utteranceId
            )

            if (result == TextToSpeech.ERROR) {
                ttsSpeaking = false
                releaseAudioFocus()
                scheduleWakeListening(1000)
            }
        } catch (_: Exception) {
            ttsSpeaking = false
            releaseAudioFocus()
            scheduleWakeListening(1000)
        }
    }

    // ---------------------------------------------------------
    // РАСПОЗНАВАНИЕ РЕЧИ
    // ---------------------------------------------------------

    private fun startListening() {
        if (destroyed || commandInProgress) return

        handler.removeCallbacks(wakeRestartRunnable)

        // Останавливаем фоновое ожидание ключевого слова.
        if (listening && activeWakeRecognition) {
            cancelCurrentRecognition()
        }

        if (ttsSpeaking) {
            try {
                tts?.stop()
            } catch (_: Exception) {
            }

            ttsSpeaking = false
            releaseAudioFocus()
        }

        startSpeechRecognition(wakeMode = false)
    }

    private fun startSpeechRecognition(wakeMode: Boolean) {
        if (destroyed || listening) return

        if (!hasMicrophonePermission()) {
            if (!permissionWarningShown) {
                permissionWarningShown = true

                Toast.makeText(
                    this,
                    "Нет разрешения на использование микрофона",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            if (!wakeMode) {
                Toast.makeText(
                    this,
                    "Распознавание речи недоступно",
                    Toast.LENGTH_LONG
                ).show()
            }

            scheduleWakeListening(3000)
            return
        }

        try {
            if (speech == null) {
                speech = SpeechRecognizer.createSpeechRecognizer(this)
            }

            val currentSession = ++speechSession
            activeWakeRecognition = wakeMode

            speech?.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(params: Bundle?) {
                        if (!isCurrentSpeechSession(currentSession)) return

                        if (!wakeMode) {
                            Toast.makeText(
                                this@OverlayService,
                                "Слушаю…",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    override fun onBeginningOfSpeech() {}

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        if (!isCurrentSpeechSession(currentSession)) return

                        finishListening(currentSession)

                        if (wakeMode) {
                            // В фоновом режиме не показываем тосты
                            // при каждом отсутствии речи.
                            scheduleWakeListening(
                                if (
                                    error == SpeechRecognizer.ERROR_NO_MATCH ||
                                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                                ) 700 else 1600
                            )
                        } else {
                            if (
                                error != SpeechRecognizer.ERROR_NO_MATCH &&
                                error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                            ) {
                                Toast.makeText(
                                    this@OverlayService,
                                    "Не удалось распознать речь",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }

                            scheduleWakeListening(900)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        if (!isCurrentSpeechSession(currentSession)) return

                        finishListening(currentSession)

                        val text = results
                            ?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )
                            ?.firstOrNull()
                            ?.trim()

                        if (wakeMode) {
                            if (!text.isNullOrBlank()) {
                                handleWakeWordResult(text)
                            } else {
                                scheduleWakeListening(700)
                            }
                        } else {
                            if (!text.isNullOrBlank()) {
                                runCommand(text)
                            } else {
                                Toast.makeText(
                                    this@OverlayService,
                                    "Я не расслышал команду",
                                    Toast.LENGTH_SHORT
                                ).show()

                                scheduleWakeListening(900)
                            }
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        if (!wakeMode ||
                            !isCurrentSpeechSession(currentSession)
                        ) return

                        val partial = partialResults
                            ?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )
                            ?.firstOrNull()
                            ?.lowercase(Locale("ru", "RU"))
                            ?.replace('ё', 'е')
                            .orEmpty()

                        // Не запускаем команду по частичному результату:
                        // ждём окончательного распознавания фразы.
                        if (partial.contains("космос")) {
                            wakeOrb()
                        }
                    }

                    override fun onEvent(
                        eventType: Int,
                        params: Bundle?
                    ) {}
                }
            )

            val intent = Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    "ru-RU"
                )

                putExtra(
                    RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                    true
                )

                putExtra(
                    RecognizerIntent.EXTRA_PREFER_OFFLINE,
                    true
                )

                putExtra(
                    RecognizerIntent.EXTRA_MAX_RESULTS,
                    3
                )
            }

            listening = true

            handler.removeCallbacks(speechTimeout)
            handler.postDelayed(speechTimeout, 20000)

            speech?.startListening(intent)

        } catch (_: Exception) {
            listening = false
            speechSession++

            handler.removeCallbacks(speechTimeout)

            if (!wakeMode) {
                Toast.makeText(
                    this,
                    "Не удалось включить микрофон",
                    Toast.LENGTH_SHORT
                ).show()
            }

            scheduleWakeListening(1600)
        }
    }

    private fun isCurrentSpeechSession(session: Int): Boolean {
        return !destroyed &&
            listening &&
            session == speechSession
    }

        private fun cancelCurrentRecognition() {
        handler.removeCallbacks(speechTimeout)

        speechSession++
        listening = false

        try {
            speech?.cancel()
        } catch (_: Exception) {
        }
    }
    private fun finishListening(session: Int) {
        if (session != speechSession || !listening) return

        val wasWakeRecognition = activeWakeRecognition

        listening = false

        handler.removeCallbacks(speechTimeout)

        // Сигнал окончания только после обычного прослушивания команды.
        if (!wasWakeRecognition) {
            playTone(false)
        }
    }

    private fun stopListening() {
        if (!listening) return

        val currentSession = speechSession

        handler.removeCallbacks(speechTimeout)

        try {
            speech?.stopListening()

            handler.postDelayed({
                if (listening && currentSession == speechSession) {
                    cancelCurrentRecognition()
                    playTone(false)
                    scheduleWakeListening(700)
                }
            }, 8000)

        } catch (_: Exception) {
            cancelCurrentRecognition()
            playTone(false)
            scheduleWakeListening(700)
        }
    }

    // ---------------------------------------------------------
    // ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ
    // ---------------------------------------------------------

    private fun wakeOrb() {
        handler.removeCallbacks(dimRunnable)
        orb?.setIdle(false)
    }

    private fun scheduleDim() {
        handler.removeCallbacks(dimRunnable)
        handler.postDelayed(dimRunnable, 9000)
    }

    private fun playTone(activate: Boolean) {
        try {
            tone.startTone(
                if (activate) {
                    ToneGenerator.TONE_PROP_ACK
                } else {
                    ToneGenerator.TONE_PROP_NACK
                },
                105
            )
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL,
                "Cosmos Assistant",
                NotificationManager.IMPORTANCE_LOW
            )

            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun dp(v: Int): Int {
        return (v * resources.displayMetrics.density).toInt()
    }

    // ---------------------------------------------------------
    // ЗАВЕРШЕНИЕ СЕРВИСА
    // ---------------------------------------------------------

    override fun onDestroy() {
        destroyed = true
        wakeWordEnabled = false

        commandInProgress = false
        commandSession++

        listening = false
        speechSession++

        handler.removeCallbacksAndMessages(null)

        try {
            speech?.cancel()
            speech?.destroy()
        } catch (_: Exception) {
        }

        speech = null

        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }

        tts = null
        ttsReady = false

        releaseAudioFocus()

        try {
            tone.release()
        } catch (_: Exception) {
        }

        try {
            entryWindow?.let {
                windowManager.removeView(it)
            }
        } catch (_: Exception) {
        }

        try {
            orb?.let {
                windowManager.removeView(it)
            }
        } catch (_: Exception) {
        }

        entryWindow = null
        orb = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        private const val CHANNEL = "cosmos_assistant_channel"
    }
}

// =============================================================
// КОСМИЧЕСКАЯ СФЕРА
// =============================================================

private class OrbView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var phase = 0f
    private var idle = false

    private val animator =
        android.animation.ValueAnimator.ofFloat(0f, 360f).apply {

            duration = 14000
            repeatCount = android.animation.ValueAnimator.INFINITE

            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }

            start()
        }

    fun setIdle(value: Boolean) {
        idle = value
        alpha = if (idle) .43f else 1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val r = width * .39f

        canvas.save()
        canvas.rotate(phase, cx, cy)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2).toFloat()
        paint.color = Color.argb(125, 80, 210, 255)

        canvas.drawOval(
            cx - r * 1.18f,
            cy - r * .42f,
            cx + r * 1.18f,
            cy + r * .42f,
            paint
        )

        canvas.drawOval(
            cx - r * .42f,
            cy - r * 1.18f,
            cx + r * .42f,
            cy + r * 1.18f,
            paint
        )

        canvas.restore()

        paint.style = Paint.Style.FILL

        paint.shader = RadialGradient(
            cx - r * .3f,
            cy - r * .34f,
            r * 1.55f,
            intArrayOf(
                Color.WHITE,
                Color.rgb(105, 225, 255),
                Color.rgb(22, 123, 230),
                Color.rgb(8, 20, 75)
            ),
            floatArrayOf(0f, .22f, .66f, 1f),
            Shader.TileMode.CLAMP
        )

        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null

        paint.color = Color.argb(125, 255, 255, 255)

        canvas.drawOval(
            cx - r * .58f,
            cy - r * .72f,
            cx + r * .02f,
            cy - r * .34f,
            paint
        )

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2).toFloat()
        paint.color = Color.argb(210, 125, 235, 255)

        canvas.drawCircle(cx, cy, r, paint)

        paint.style = Paint.Style.FILL

        for (i in 0..5) {
            val a = Math.toRadians((phase + i * 60).toDouble())

            paint.color = Color.argb(170, 120, 230, 255)

            canvas.drawCircle(
                cx + kotlin.math.cos(a).toFloat() * r * 1.22f,
                cy + kotlin.math.sin(a).toFloat() * r * 1.22f,
                1.6f * resources.displayMetrics.density,
                paint
            )
        }
    }

    private fun dp(v: Int): Int {
        return (v * resources.displayMetrics.density).toInt()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}

// =============================================================
// КНОПКА ОТПРАВКИ
// =============================================================

private class SendOrbView(context: Context) : View(context) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val r = width.coerceAtMost(height) * .43f
        val x = width / 2f
        val y = height / 2f

        p.shader = RadialGradient(
            x - r * .3f,
            y - r * .35f,
            r * 1.5f,
            intArrayOf(
                Color.WHITE,
                Color.rgb(65, 205, 255),
                Color.rgb(10, 80, 190)
            ),
            null,
            Shader.TileMode.CLAMP
        )

        canvas.drawCircle(x, y, r, p)
        p.shader = null

        p.color = Color.WHITE
        p.textSize = r * 1.05f
        p.textAlign = Paint.Align.CENTER

        canvas.drawText("➤", x, y + r * .34f, p)
    }
}

// =============================================================
// ЗВЁЗДНЫЙ ФОН
// =============================================================

private class StarPanel(context: Context) : View(context) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    private val stars = List(85) {
        Triple(
            Random.nextFloat(),
            Random.nextFloat(),
            Random.nextFloat() * 1.5f + .5f
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(3, 8, 25))

        p.shader = RadialGradient(
            width * .75f,
            height * .35f,
            width * .8f,
            intArrayOf(
                Color.argb(95, 20, 80, 150),
                Color.TRANSPARENT
            ),
            null,
            Shader.TileMode.CLAMP
        )

        canvas.drawRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            p
        )

        p.shader = null
        p.color = Color.argb(205, 205, 230, 255)

        stars.forEach { (x, y, r) ->
            canvas.drawCircle(x * width, y * height, r, p)
        }

        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        p.color = Color.argb(210, 40, 165, 255)

        canvas.drawRoundRect(
            1f,
            1f,
            width - 1f,
            height - 1f,
            dp(25).toFloat(),
            dp(25).toFloat(),
            p
        )

        p.style = Paint.Style.FILL
    }

    private fun dp(v: Int): Int {
        return (v * resources.displayMetrics.density).toInt()
    }
}

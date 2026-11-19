
package com.cosmosa27.cosmosassistant

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.OpenableColumns
import com.tensai.llamakt.ChatMessage
import com.tensai.llamakt.LlamaEngine
import com.tensai.llamakt.SamplingParams
import com.tensai.llamakt.TokenCallback
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

object QwenAssistant {

    private const val PREFS = "cosmos_qwen"
    private const val MODEL_PATH_KEY = "model_path"
    private const val HISTORY_KEY = "chat_history"
    private const val MODEL_FILE = "qwen-model.gguf"

    private const val CONTEXT_SIZE = 1024
    private const val CPU_THREADS = 4
    private const val MAX_RESPONSE_TOKENS = 96
    private const val MAX_HISTORY_MESSAGES = 6

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var engine: LlamaEngine? = null

    @Volatile
    private var loading = false

    @Volatile
    private var appContext: Context? = null

    private val history = mutableListOf<ChatMessage>()

    private val systemPrompt = """
        Ты Космос — персональный голосовой помощник.
        Всегда говори по-русски, естественно и дружелюбно.
        Отвечай кратко, обычно одним-двумя предложениями.
        Не рассуждай вслух и не добавляй лишних объяснений.
        Если команда непонятна, задай один короткий вопрос.
        Не утверждай, что выполнил действие, если оно не выполнено.
        Ты можешь помогать с планированием и пониманием команд.
    """.trimIndent()

    fun isReady(): Boolean = engine != null

    fun isLoading(): Boolean = loading

    // Импорт выбранной GGUF-модели.
    fun importAndLoad(
        context: Context,
        uri: Uri,
        callback: (Boolean, String) -> Unit
    ) {
        val ctx = context.applicationContext
        appContext = ctx

        if (loading) {
            callbackOnMain(callback, false, "Модель уже загружается. Подожди.")
            return
        }

        executor.execute {
            loading = true

            try {
                val modelsDir = File(ctx.filesDir, "models")

                if (!modelsDir.exists() && !modelsDir.mkdirs()) {
                    throw Exception("Не удалось создать папку модели")
                }

                val destination = File(modelsDir, MODEL_FILE)
                val temporary = File(modelsDir, "$MODEL_FILE.part")

                val expectedSize = getFileSize(ctx, uri)
                val available = StatFs(ctx.filesDir.absolutePath).availableBytes

                if (expectedSize > 0 &&
                    expectedSize + 300L * 1024 * 1024 > available
                ) {
                    throw Exception(
                        "Недостаточно свободной памяти телефона для копирования модели"
                    )
                }

                if (temporary.exists()) temporary.delete()

                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(1024 * 1024)

                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }

                        output.fd.sync()
                    }
                } ?: throw Exception("Не удалось прочитать выбранный файл")

                if (!temporary.exists() || temporary.length() == 0L) {
                    throw Exception("Файл модели пустой")
                }

                val metadata = LlamaEngine.readMetadata(temporary.absolutePath)
                    ?: throw Exception("Файл не распознан как GGUF-модель")

                // Проверка пройдена. Заменяем предыдущую модель.
                if (destination.exists() && !destination.delete()) {
                    throw Exception("Не удалось заменить предыдущую модель")
                }

                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }

                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(MODEL_PATH_KEY, destination.absolutePath)
                    .apply()

                loadModelInternal(destination.absolutePath)

                callbackOnMain(
                    callback,
                    true,
                    "Модель загружена. Архитектура: ${metadata.architecture}, формат: ${metadata.quantLabel}"
                )

            } catch (e: Exception) {
                callbackOnMain(
                    callback,
                    false,
                    "Ошибка загрузки модели: ${e.message ?: "неизвестная ошибка"}"
                )
            } finally {
                loading = false
            }
        }
    }

    // Загрузка ранее сохранённой модели.
    fun loadSaved(
        context: Context,
        callback: ((Boolean, String) -> Unit)? = null
    ) {
        val ctx = context.applicationContext
        appContext = ctx

        if (engine != null) {
            callback?.let {
                callbackOnMain(it, true, "Космос готов к работе")
            }
            return
        }

        if (loading) {
            callback?.let {
                callbackOnMain(it, false, "Модель уже загружается. Подожди.")
            }
            return
        }

        val path = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(MODEL_PATH_KEY, null)

        if (path.isNullOrBlank()) {
            callback?.let {
                callbackOnMain(it, false, "Модель ещё не выбрана")
            }
            return
        }

        executor.execute {
            loading = true

            try {
                val file = File(path)

                if (!file.exists()) {
                    throw Exception("Файл модели не найден")
                }

                loadModelInternal(path)

                callback?.let {
                    callbackOnMain(it, true, "Космос готов к работе")
                }

            } catch (e: Exception) {
                callback?.let {
                    callbackOnMain(
                        it,
                        false,
                        "Не удалось загрузить модель: ${e.message ?: "ошибка"}"
                    )
                }
            } finally {
                loading = false
            }
        }
    }

    // Запуск локальной модели.
    private fun loadModelInternal(path: String) {
        engine?.let {
            try {
                it.free()
            } catch (_: Exception) {
            }
        }

        engine = null

        val newEngine = LlamaEngine()

        try {
            newEngine.load(
                path = path,
                nGpuLayers = 0,
                nCtx = CONTEXT_SIZE,
                nThreads = CPU_THREADS,
                kvCacheType = "q8_0",
                nBatch = 256,
                nUbatch = 128
            )

            engine = newEngine
            loadHistory()

        } catch (e: Exception) {
            try {
                newEngine.free()
            } catch (_: Exception) {
            }

            val detail = LlamaEngine.lastLoadError()

            throw Exception(
                if (detail.isNotBlank()) detail
                else e.message ?: "Ошибка запуска модели"
            )
        }
    }

    // Отправка вопроса модели.
    fun ask(
        context: Context,
        question: String,
        callback: (String) -> Unit
    ) {
        val cleanQuestion = question.trim()

        if (cleanQuestion.isEmpty()) {
            callbackOnMain(callback, "Что ты хотел спросить?")
            return
        }

        appContext = context.applicationContext

        if (engine == null) {
            loadSaved(context) { success, message ->
                if (success) {
                    ask(context, cleanQuestion, callback)
                } else {
                    callback(message)
                }
            }
            return
        }

        executor.execute {
            try {
                val currentEngine = engine
                    ?: throw Exception("Модель ещё не загружена")

                val messages = mutableListOf<ChatMessage>()

                messages.add(ChatMessage("system", systemPrompt))

                synchronized(history) {
                    messages.addAll(history)
                }

                messages.add(ChatMessage("user", cleanQuestion))

                val prompt = currentEngine.formatChat(
                    messages,
                    enableThinking = false
                )

                val generated = StringBuilder()

                currentEngine.resetInterrupt()

                val count = currentEngine.completion(
                    prompt = prompt,
                    params = SamplingParams(
                        nPredict = MAX_RESPONSE_TOKENS,
                        temperature = 0.7f,
                        topK = 30,
                        topP = 0.85f,
                        repeatPenalty = 1.08f,
                        repeatLastN = 64
                    ),
                    callback = TokenCallback { token ->
                        generated.append(token)
                    }
                )

                if (count < 0) {
                    throw Exception("Ошибка генерации ответа")
                }

                val parsed = currentEngine.lastChatResult()?.content.orEmpty()

                val answer = parsed.trim()
                    .ifBlank { generated.toString().trim() }

                if (answer.isBlank()) {
                    throw Exception("Модель не сформировала ответ")
                }

                synchronized(history) {
                    history.add(ChatMessage("user", cleanQuestion))
                    history.add(ChatMessage("assistant", answer))

                    while (history.size > MAX_HISTORY_MESSAGES) {
                        history.removeAt(0)
                    }

                    saveHistory()
                }

                callbackOnMain(callback, answer)

            } catch (e: Exception) {
                callbackOnMain(
                    callback,
                    "Не удалось получить ответ: ${e.message ?: "ошибка"}"
                )
            }
        }
    }

    // Очистка истории.
    fun clearHistory(context: Context) {
        synchronized(history) {
            history.clear()

            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(HISTORY_KEY)
                .apply()
        }
    }

    // Восстановление истории.
    private fun loadHistory() {
        val ctx = appContext ?: return

        synchronized(history) {
            history.clear()

            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(HISTORY_KEY, null)
                ?: return

            try {
                val array = JSONArray(raw)

                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val role = item.optString("role")
                    val content = item.optString("content")

                    if ((role == "user" || role == "assistant") &&
                        content.isNotBlank()
                    ) {
                        history.add(ChatMessage(role, content))
                    }
                }

            } catch (_: Exception) {
                history.clear()
            }
        }
    }

    // Сохранение истории.
    private fun saveHistory() {
        val ctx = appContext ?: return

        val array = JSONArray()

        history.forEach { message ->
            array.put(
                JSONObject()
                    .put("role", message.role)
                    .put("content", message.content)
            )
        }

        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(HISTORY_KEY, array.toString())
            .apply()
    }

    private fun getFileSize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    cursor.getLong(0)
                } else {
                    -1L
                }
            } ?: -1L

        } catch (_: Exception) {
            -1L
        }
    }

    private fun callbackOnMain(
        callback: (String) -> Unit,
        text: String
    ) {
        mainHandler.post {
            callback(text)
        }
    }

    private fun callbackOnMain(
        callback: (Boolean, String) -> Unit,
        success: Boolean,
        text: String
    ) {
        mainHandler.post {
            callback(success, text)
        }
    }
}

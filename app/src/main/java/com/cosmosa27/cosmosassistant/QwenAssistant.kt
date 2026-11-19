
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
import java.util.concurrent.atomic.AtomicBoolean

object QwenAssistant {

    private const val PREFS = "cosmos_qwen"
    private const val MODEL_PATH_KEY = "model_path"
    private const val HISTORY_KEY = "chat_history"

    private const val CONTEXT_SIZE = 1024
    private const val CPU_THREADS = 4
    private const val MAX_RESPONSE_TOKENS = 64
    private const val MAX_HISTORY_MESSAGES = 4

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val loadLock = Any()
    private val loadCallbacks = mutableListOf<(Boolean, String) -> Unit>()

    private val generationInProgress = AtomicBoolean(false)
    private val generationCancelled = AtomicBoolean(false)

    @Volatile
    private var engine: LlamaEngine? = null

    @Volatile
    private var loading = false

    @Volatile
    private var appContext: Context? = null

    private val history = mutableListOf<ChatMessage>()

    private val systemPrompt = """
        Ты Кос — персональный голосовой помощник.
        Всегда говори по-русски.
        Отвечай кратко, естественно и дружелюбно.
        Обычно достаточно одного-двух предложений.
        Не рассуждай вслух.
        Не повторяй вопрос пользователя.

        Ты можешь управлять Android через инструменты.

        Если пользователь просит выполнить действие на телефоне,
        выведи строго одну строку в формате:
        COS_TOOL: {"tool":"название","args":{"параметр":"значение"}}

        Инструменты:

        open_app — открыть приложение. args: name
        open_settings — настройки. args: {}
        back — назад. args: {}
        home — домой. args: {}
        recents — последние приложения. args: {}
        notifications — уведомления. args: {}
        quick_settings — быстрые настройки. args: {}
        read_screen — прочитать экран. args: {}
        click — нажать элемент. args: text
        type_text — ввести текст. args: text, view_id
        scroll_down — прокрутить вниз. args: {}
        scroll_up — прокрутить вверх. args: {}
        search_web — поиск в интернете. args: query
        open_url — открыть ссылку. args: url
        open_map — найти место на карте. args: place
        dial — открыть набор номера. args: number

        Используй только один инструмент за один ответ.
        Не выдумывай результаты выполненных действий.
        Если информации недостаточно, задай короткий вопрос.
        Не вызывай инструменты для обычных информационных вопросов.

        Не отправляй сообщения, не удаляй данные,
        не публикуй материалы, не совершай покупки,
        платежи и денежные переводы самостоятельно.
    """.trimIndent()

    fun isReady(): Boolean = engine != null
    fun isLoading(): Boolean = loading
    fun isBusy(): Boolean = generationInProgress.get()

    /**
     * Останавливает текущую генерацию непосредственно в llama.cpp.
     * Вызывать можно из главного потока.
     */
    fun cancelCurrentGeneration() {
        if (!generationInProgress.get()) return

        generationCancelled.set(true)

        try {
            engine?.interrupt()
        } catch (_: Exception) {
        }
    }

    private fun checkCancelled() {
        if (generationCancelled.get()) {
            throw GenerationCancelledException()
        }
    }

    private class GenerationCancelledException :
        Exception("Генерация отменена")

    // ---------------------------------------------------------
    // ИМПОРТ МОДЕЛИ
    // ---------------------------------------------------------

    fun importAndLoad(
        context: Context,
        uri: Uri,
        callback: (Boolean, String) -> Unit
    ) {
        val ctx = context.applicationContext
        appContext = ctx

        val canStart = synchronized(loadLock) {
            if (loading) {
                false
            } else {
                loading = true
                loadCallbacks.add(callback)
                true
            }
        }

        if (!canStart) {
            callbackOnMain(callback, false, "Модель уже загружается. Подожди.")
            return
        }

        try {
            executor.execute {
                var success = false
                var message = "Не удалось загрузить модель."

                val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val previousPath = prefs.getString(MODEL_PATH_KEY, null)

                try {
                    val modelsDir = File(ctx.filesDir, "models")

                    if (!modelsDir.exists() && !modelsDir.mkdirs()) {
                        throw Exception("Не удалось создать папку моделей")
                    }

                    val sourceName = getDisplayName(ctx, uri)

                    var safeName = sourceName
                        .replace(Regex("[^A-Za-z0-9._-]"), "_")
                        .trim('_')
                        .take(90)

                    if (safeName.isBlank()) {
                        safeName = "qwen-${System.currentTimeMillis()}.gguf"
                    }

                    if (!safeName.endsWith(".gguf", true)) {
                        safeName += ".gguf"
                    }

                    var destination = File(modelsDir, safeName)

                    if (
                        previousPath != null &&
                        destination.absolutePath == File(previousPath).absolutePath
                    ) {
                        val name = destination.nameWithoutExtension
                        destination = File(
                            modelsDir,
                            "$name-${System.currentTimeMillis()}.gguf"
                        )
                    }

                    val temporary = File(modelsDir, ".${destination.name}.part")
                    val expectedSize = getFileSize(ctx, uri)
                    val available = StatFs(ctx.filesDir.absolutePath).availableBytes

                    if (
                        expectedSize > 0 &&
                        expectedSize + 150L * 1024 * 1024 > available
                    ) {
                        throw Exception("Недостаточно свободной памяти для копирования модели")
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
                    } ?: throw Exception("Не удалось прочитать файл")

                    if (!temporary.exists() || temporary.length() == 0L) {
                        throw Exception("Файл модели пустой")
                    }

                    val metadata = LlamaEngine.readMetadata(
                        temporary.absolutePath
                    ) ?: throw Exception("Файл не распознан как GGUF-модель")

                    if (destination.exists() && !destination.delete()) {
                        throw Exception("Не удалось заменить файл модели")
                    }

                    if (!temporary.renameTo(destination)) {
                        temporary.copyTo(destination, overwrite = true)
                        temporary.delete()
                    }

                    loadModelInternal(destination.absolutePath)

                    prefs.edit()
                        .putString(MODEL_PATH_KEY, destination.absolutePath)
                        .apply()

                    success = true
                    message =
                        "Модель загружена. Архитектура: ${metadata.architecture}, " +
                        "формат: ${metadata.quantLabel}"

                } catch (e: Exception) {
                    message = "Ошибка загрузки модели: ${e.message ?: "неизвестная ошибка"}"

                    if (
                        engine == null &&
                        !previousPath.isNullOrBlank() &&
                        File(previousPath).exists()
                    ) {
                        try {
                            loadModelInternal(previousPath)
                            message += ". Предыдущая модель восстановлена."
                        } catch (_: Exception) {
                            message += ". Предыдущую модель тоже не удалось запустить."
                        }
                    }
                } finally {
                    finishLoading(success, message)
                }
            }
        } catch (e: Exception) {
            finishLoading(false, "Не удалось запустить загрузку: ${e.message ?: "ошибка"}")
        }
    }

    // ---------------------------------------------------------
    // ЗАГРУЗКА СОХРАНЁННОЙ МОДЕЛИ
    // ---------------------------------------------------------

    fun loadSaved(
        context: Context,
        callback: ((Boolean, String) -> Unit)? = null
    ) {
        val ctx = context.applicationContext
        appContext = ctx

        if (engine != null) {
            callback?.let {
                callbackOnMain(it, true, "Кос готов к работе")
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

        val shouldStart = synchronized(loadLock) {
            if (callback != null) loadCallbacks.add(callback)

            if (loading) {
                false
            } else {
                loading = true
                true
            }
        }

        if (!shouldStart) return

        try {
            executor.execute {
                var success = false
                var message = "Не удалось загрузить модель."

                try {
                    val file = File(path)
                    if (!file.exists()) throw Exception("Файл модели не найден")

                    loadModelInternal(path)
                    success = true
                    message = "Кос готов к работе"

                } catch (e: Exception) {
                    message = "Не удалось загрузить модель: ${e.message ?: "ошибка"}"
                } finally {
                    finishLoading(success, message)
                }
            }
        } catch (_: Exception) {
            finishLoading(false, "Не удалось запустить загрузку модели")
        }
    }

    private fun finishLoading(success: Boolean, message: String) {
        val callbacks = synchronized(loadLock) {
            loading = false
            val result = loadCallbacks.toList()
            loadCallbacks.clear()
            result
        }

        callbacks.forEach {
            callbackOnMain(it, success, message)
        }
    }

    // ---------------------------------------------------------
    // ЗАПУСК ДВИЖКА
    // ---------------------------------------------------------

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

    // ---------------------------------------------------------
    // ОБРАБОТКА ЗАПРОСОВ
    // ---------------------------------------------------------

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

        val ctx = context.applicationContext
        appContext = ctx

        val memoryResponse = try {
            CosMemory.handleCommand(ctx, cleanQuestion)
        } catch (e: Exception) {
            "Ошибка обращения к памяти: ${e.message ?: "ошибка"}"
        }

        if (memoryResponse != null) {
            callbackOnMain(callback, memoryResponse)
            return
        }

        val actionResponse = try {
            CosCommandExecutor.execute(ctx, cleanQuestion)
        } catch (e: Exception) {
            "Не удалось выполнить команду: ${e.message ?: "ошибка"}"
        }

        if (actionResponse != null) {
            callbackOnMain(callback, actionResponse)
            return
        }

        if (engine == null) {
            loadSaved(ctx) { success, message ->
                if (success) {
                    ask(ctx, cleanQuestion, callback)
                } else {
                    callback(message)
                }
            }
            return
        }

        if (!generationInProgress.compareAndSet(false, true)) {
            callbackOnMain(
                callback,
                "Я ещё выполняю предыдущую задачу. Подожди немного."
            )
            return
        }

        generationCancelled.set(false)

        try {
            executor.execute {
                try {
                    val currentEngine = engine
                        ?: throw Exception("Модель не загружена")

                    checkCancelled()

                    val messages = mutableListOf<ChatMessage>()
                    messages.add(ChatMessage("system", systemPrompt))

                    val memoryContext = CosMemory.contextFor(ctx, cleanQuestion)

                    if (memoryContext.isNotBlank()) {
                        messages.add(ChatMessage("system", memoryContext))
                    }

                    synchronized(history) {
                        messages.addAll(history)
                    }

                    messages.add(ChatMessage("user", cleanQuestion))

                    val prompt = currentEngine.formatChat(
                        messages,
                        enableThinking = false
                    )

                    checkCancelled()

                    val generated = StringBuilder()

                    // Сбрасываем старое прерывание непосредственно
                    // перед началом новой генерации.
                    currentEngine.resetInterrupt()
                    checkCancelled()

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
                            if (!generationCancelled.get()) {
                                generated.append(token)
                            }
                        }
                    )

                    checkCancelled()

                    if (count <= 0) {
                        throw Exception("Модель не завершила генерацию")
                    }

                    val parsed = currentEngine.lastChatResult()
                        ?.content
                        .orEmpty()

                    val answer = parsed.trim().ifBlank {
                        generated.toString().trim()
                    }

                    if (answer.isBlank()) {
                        throw Exception("Модель не сформировала ответ")
                    }

                    checkCancelled()

                    val toolResult = try {
                        CosToolRouter.execute(ctx, answer)
                    } catch (e: Exception) {
                        CosToolRouter.ToolResult(
                            true,
                            "Не удалось выполнить действие: ${e.message ?: "ошибка"}"
                        )
                    }

                    checkCancelled()

                    val finalAnswer =
                        if (toolResult != null && toolResult.handled) {
                            toolResult.response
                        } else {
                            answer
                        }

                    synchronized(history) {
                        checkCancelled()

                        history.add(ChatMessage("user", cleanQuestion))
                        history.add(ChatMessage("assistant", finalAnswer))

                        while (history.size > MAX_HISTORY_MESSAGES) {
                            if (history.size >= 2) {
                                history.removeAt(0)
                                history.removeAt(0)
                            } else {
                                history.clear()
                            }
                        }

                        saveHistory()
                    }

                    checkCancelled()
                    callbackOnMain(callback, finalAnswer)

                } catch (_: GenerationCancelledException) {
                    // Отменённый запрос не озвучиваем и не сохраняем.
                } catch (e: Exception) {
                    if (!generationCancelled.get()) {
                        callbackOnMain(
                            callback,
                            "Не удалось получить ответ: ${e.message ?: "ошибка"}"
                        )
                    }
                } finally {
                    generationCancelled.set(false)
                    generationInProgress.set(false)
                }
            }
        } catch (_: Exception) {
            generationCancelled.set(false)
            generationInProgress.set(false)
            callbackOnMain(callback, "Не удалось запустить обработку запроса.")
        }
    }

    // ---------------------------------------------------------
    // ИСТОРИЯ
    // ---------------------------------------------------------

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

    private fun loadHistory() {
        val ctx = appContext ?: return

        synchronized(history) {
            history.clear()

            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(HISTORY_KEY, null) ?: return

            try {
                val array = JSONArray(raw)

                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val role = item.optString("role")
                    val content = item.optString("content")

                    if (
                        (role == "user" || role == "assistant") &&
                        content.isNotBlank()
                    ) {
                        history.add(ChatMessage(role, content))
                    }
                }

                while (history.size > MAX_HISTORY_MESSAGES) {
                    history.removeAt(0)
                }
            } catch (_: Exception) {
                history.clear()
            }
        }
    }

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

    // ---------------------------------------------------------
    // ФАЙЛЫ
    // ---------------------------------------------------------

    private fun getDisplayName(context: Context, uri: Uri): String {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
            } ?: ""
        } catch (_: Exception) {
            ""
        }
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

    // ---------------------------------------------------------
    // CALLBACK
    // ---------------------------------------------------------

    private fun callbackOnMain(callback: (String) -> Unit, text: String) {
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

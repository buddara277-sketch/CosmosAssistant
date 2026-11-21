
package com.cosmosa27.cosmosassistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.view.KeyEvent

object CosCommandExecutor {

    private sealed class AutomationStep {
        data class OpenApp(val name: String) : AutomationStep()
        data class ClickAny(val targets: List<String>, val description: String) : AutomationStep()
        data class Search(val query: String) : AutomationStep()
        data class TypeText(val text: String) : AutomationStep()
        data class Scroll(val forward: Boolean) : AutomationStep()
        data object Back : AutomationStep()
    }

    /**
     * Выполняет простую цепочку действий в приложении.
     * Обычные одиночные команды по-прежнему обрабатываются методом execute().
     */
    fun executeSequence(
        context: Context,
        command: String,
        onComplete: (String) -> Unit
    ) {
        val steps = parseAutomationSteps(command)

        if (steps == null || steps.size < 2) {
            onComplete(
                execute(context, command)
                    ?: "Не понял команду. Попробуй сказать иначе."
            )
            return
        }

        val openStep = steps.firstOrNull() as? AutomationStep.OpenApp
        val service = CosAccessibilityService.instance
        if (service == null) {
            onComplete(
                "Управление экраном недоступно. Проверь, включена ли служба специальных возможностей Cosmos."
            )
            return
        }

        val launch = if (openStep != null) {
            launchApp(context, openStep.name)
        } else {
            val activePackage = service.getActivePackageName()
            AppLaunchResult(
                "Продолжаю работу в открытом приложении.",
                activePackage.takeIf { it.isNotBlank() }
            )
        }
        if (launch.packageName == null) {
            onComplete(launch.message.ifBlank { "Не удалось определить открытое приложение." })
            return
        }

        val handler = Handler(Looper.getMainLooper())
        val resultMessages = mutableListOf(launch.message)
        var stepIndex = if (openStep != null) 1 else 0
        var finished = false

        fun finish(message: String) {
            if (finished) return
            finished = true
            onComplete(message)
        }

        fun waitForPackage(packageName: String, timeoutMs: Long, done: (Boolean) -> Unit) {
            val startedAt = SystemClock.uptimeMillis()

            val check = object : Runnable {
                override fun run() {
                    if (finished) return

                    val activePackage = service.getActivePackageName()
                    if (activePackage == packageName ||
                        activePackage.startsWith("$packageName.")
                    ) {
                        handler.postDelayed({ done(true) }, 900)
                    } else if (SystemClock.uptimeMillis() - startedAt >= timeoutMs) {
                        done(false)
                    } else {
                        handler.postDelayed(this, 250)
                    }
                }
            }

            handler.post(check)
        }

        fun waitForScreenChange(
            oldVersion: Long,
            oldText: String,
            timeoutMs: Long,
            done: (Boolean) -> Unit
        ) {
            val startedAt = SystemClock.uptimeMillis()

            val check = object : Runnable {
                override fun run() {
                    if (finished) return

                    val changed = service.getScreenVersion() > oldVersion ||
                        service.getScreenText() != oldText

                    if (changed) {
                        handler.postDelayed({ done(true) }, 350)
                    } else if (SystemClock.uptimeMillis() - startedAt >= timeoutMs) {
                        done(false)
                    } else {
                        handler.postDelayed(this, 250)
                    }
                }
            }

            handler.postDelayed(check, 180)
        }

        lateinit var runNext: () -> Unit

        runNext = {
            if (finished) {
                Unit
            } else if (stepIndex >= steps.size) {
                finish("Готово. ${resultMessages.joinToString(" ")}")
            } else {
                when (val step = steps[stepIndex]) {
                    is AutomationStep.OpenApp -> {
                        // В нормальной последовательности приложение открывается первым.
                        stepIndex++
                        runNext()
                    }

                    is AutomationStep.ClickAny -> {
                        val dangerousWords = listOf(
                            "отправить", "удалить", "оплатить", "купить",
                            "перевести", "опубликовать", "подтвердить",
                            "заказать", "платёж", "платеж"
                        )

                        val dangerousTarget = step.targets.any { target ->
                            dangerousWords.any { word ->
                                target.contains(word, ignoreCase = true)
                            }
                        }

                        if (dangerousTarget) {
                            finish(
                                "Это действие требует отдельного подтверждения. " +
                                    "Я не нажимаю такую кнопку автоматически."
                            )
                        } else {
                            val beforeVersion = service.getScreenVersion()
                            val beforeText = service.getScreenText()

                            if (!service.clickAny(step.targets)) {
                                finish(
                                    "Не нашёл на экране: ${step.description}. " +
                                        "Дальше не продолжаю."
                                )
                            } else {
                                waitForScreenChange(
                                    beforeVersion,
                                    beforeText,
                                    5500
                                ) { changed ->
                                    if (!changed) {
                                        finish(
                                            "Нажатие «${step.description}» выполнено, " +
                                                "но переход или изменение экрана не подтвердились."
                                        )
                                    } else {
                                        resultMessages.add("Открыл: ${step.description}.")
                                        stepIndex++
                                        runNext()
                                    }
                                }
                            }
                        }
                    }

                    is AutomationStep.Search -> {
                        val searchFieldTapped = service.clickAny(
                            listOf("поиск", "search")
                        )

                        if (!searchFieldTapped && !service.hasEditableField()) {
                            finish("Не нашёл поле поиска. Дальше не продолжаю.")
                        } else {
                            handler.postDelayed({
                                if (!service.enterTextInFocusedField(step.query)) {
                                    finish("Не удалось ввести запрос «${step.query}».")
                                } else {
                                    val beforeVersion = service.getScreenVersion()
                                    val beforeText = service.getScreenText()
                                    handler.postDelayed({
                                        if (!service.submitSearch()) {
                                            finish("Запрос введён, но не удалось запустить поиск.")
                                        } else {
                                            waitForScreenChange(
                                                beforeVersion,
                                                beforeText,
                                                8000
                                            ) { changed ->
                                                if (!changed) {
                                                    finish("Поиск запущен, но результаты на экране не подтвердились.")
                                                } else {
                                                    resultMessages.add("Выполнил поиск: ${step.query}.")
                                                    stepIndex++
                                                    runNext()
                                                }
                                            }
                                        }
                                    }, 350)
                                }
                            }, if (searchFieldTapped) 450 else 150)
                        }
                    }
                    is AutomationStep.TypeText -> {
                        if (step.text.isBlank()) {
                            finish("Не получил текст для ввода.")
                        } else if (!service.enterTextInFocusedField(step.text)) {
                            finish("Не нашёл активное поле для ввода текста.")
                        } else {
                            resultMessages.add("Ввёл текст.")
                            stepIndex++
                            handler.postDelayed({ runNext() }, 250)
                        }
                    }

                    is AutomationStep.Scroll -> {
                        val moved = if (step.forward) service.scrollForward() else service.scrollBackward()
                        if (!moved) {
                            finish("Не удалось прокрутить текущий экран.")
                        } else {
                            resultMessages.add(if (step.forward) "Прокрутил вниз." else "Прокрутил вверх.")
                            stepIndex++
                            handler.postDelayed({ runNext() }, 450)
                        }
                    }

                    AutomationStep.Back -> {
                        val beforeVersion = service.getScreenVersion()
                        val beforeText = service.getScreenText()
                        val wentBack = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        if (!wentBack) {
                            finish("Не удалось выполнить действие «назад».")
                        } else {
                            waitForScreenChange(beforeVersion, beforeText, 4000) { changed ->
                                if (!changed) {
                                    finish("Команду «назад» отправил, но изменение экрана не подтвердилось.")
                                } else {
                                    resultMessages.add("Вернулся на предыдущий экран.")
                                    stepIndex++
                                    runNext()
                                }
                            }
                        }
                    }
                }
            }
        }

        if (openStep != null) {
            waitForPackage(launch.packageName, 9000) { opened ->
                if (!opened) {
                    finish("Не удалось подтвердить открытие приложения ${openStep.name}.")
                } else {
                    stepIndex = 1
                    runNext()
                }
            }
        } else {
            runNext()
        }
    }

    private data class AppLaunchResult(
        val message: String,
        val packageName: String?
    )

    fun supportsSequence(command: String): Boolean =
        (parseAutomationSteps(command)?.size ?: 0) >= 2

    private fun parseAutomationSteps(command: String): List<AutomationStep>? {
        val text = command.lowercase()
            .replace('ё', 'е')
            .replace(Regex("[.!?;]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val opening = Regex(
            "^(?:открой(?: приложение)?|запусти(?: приложение)?|зайди (?:в|на)|перейди (?:в|на))\\s+(.+?)(?:\\s*,\\s*|\\s+(?:и|потом|затем|после этого)\\s+)(.+)$"
        ).find(text)

        val appName = opening?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
        val remainder = opening?.groupValues?.get(2)?.trim() ?: text
        if (remainder.isBlank()) return null

        val clauses = remainder.split(
            Regex(",\\s*|\\s+(?:и|потом|затем|после этого)\\s+(?=(?:зайди|перейди|открой|найди|поищи|включи|нажми|введи|прокрути|вернись|назад))")
        ).map { clause ->
            clause.trim()
                .removePrefixIgnoreCase("затем ")
                .removePrefixIgnoreCase("потом ")
                .removePrefixIgnoreCase("после этого ")
        }.filter { it.isNotBlank() }
        if (clauses.isEmpty()) return null

        val steps = mutableListOf<AutomationStep>()
        if (appName != null) steps.add(AutomationStep.OpenApp(appName))

        for (clause in clauses) {
            when {
                clause.startsWithAny("зайди в мои треки", "перейди в мои треки", "открой мои треки", "зайди в музыку", "открой музыку", "перейди в музыку") ->
                    steps.add(AutomationStep.ClickAny(
                        listOf("мои треки", "моя музыка", "музыка", "мои аудиозаписи", "music"),
                        "раздел «Моя музыка»"
                    ))

                clause.startsWithAny("найди ", "поищи ", "найди товар ", "поищи товар ", "выполни поиск ") -> {
                    val query = clause.removePrefixIgnoreCase("выполни поиск ")
                        .removePrefixIgnoreCase("найди товар ").removePrefixIgnoreCase("поищи товар ")
                        .removePrefixIgnoreCase("найди ").removePrefixIgnoreCase("поищи ").trim()
                    if (query.isBlank()) return null
                    steps.add(AutomationStep.Search(query))
                }

                clause.startsWithAny("включи радио", "запусти радио") ->
                    steps.add(AutomationStep.ClickAny(
                        listOf("включить радио", "воспроизвести", "слушать", "play", "пуск", "▶", "радио"),
                        "запуск радио"
                    ))

                clause.startsWithAny("включи ", "запусти воспроизведение", "воспроизведи ") -> {
                    val target = clause.removePrefixIgnoreCase("включи ")
                        .removePrefixIgnoreCase("воспроизведи ")
                        .removePrefixIgnoreCase("трек ").removePrefixIgnoreCase("песню ")
                        .trim()
                    if (target.isBlank()) {
                        steps.add(AutomationStep.ClickAny(listOf("play", "воспроизвести", "▶", "пуск"), "воспроизведение"))
                    } else steps.add(AutomationStep.ClickAny(listOf(target, "play", "воспроизвести"), "трек «$target»"))
                }

                clause.startsWithAny("зайди в ", "перейди в ", "открой раздел ", "покажи раздел ", "открой вкладку ") -> {
                    val target = clause.removePrefixIgnoreCase("зайди в ").removePrefixIgnoreCase("перейди в ")
                        .removePrefixIgnoreCase("открой раздел ").removePrefixIgnoreCase("покажи раздел ")
                        .removePrefixIgnoreCase("открой вкладку ").trim()
                    if (target.isBlank()) return null
                    steps.add(AutomationStep.ClickAny(listOf(target), target))
                }

                clause.startsWithAny("нажми ", "нажми кнопку ", "выбери ", "открой пункт ") -> {
                    val target = clause.removePrefixIgnoreCase("нажми кнопку ").removePrefixIgnoreCase("нажми ")
                        .removePrefixIgnoreCase("выбери ").removePrefixIgnoreCase("открой пункт ").trim()
                    if (target.isBlank()) return null
                    steps.add(AutomationStep.ClickAny(listOf(target), target))
                }

                clause.startsWithAny("введи текст ", "напечатай ", "введи ") -> {
                    val value = clause.removePrefixIgnoreCase("введи текст ").removePrefixIgnoreCase("напечатай ")
                        .removePrefixIgnoreCase("введи ").substringBefore(" в поле ")
                        .trim().trim('"', '«', '»')
                    if (value.isBlank()) return null
                    steps.add(AutomationStep.TypeText(value))
                }

                clause.startsWithAny("прокрути вниз", "пролистай вниз", "листани вниз") ->
                    steps.add(AutomationStep.Scroll(true))

                clause.startsWithAny("прокрути вверх", "пролистай вверх", "листани вверх") ->
                    steps.add(AutomationStep.Scroll(false))

                clause == "назад" || clause.startsWith("вернись назад") || clause.startsWith("вернись на предыдущий экран") ->
                    steps.add(AutomationStep.Back)

                else -> return null
            }
        }

        return steps.takeIf { it.size in 2..7 }
    }

    fun execute(context: Context, command: String): String? {
        val original = command.trim()
        val text = normalize(original)

        if (text.isBlank()) return null

        return when {

            // ГРОМКОСТЬ

            text in listOf("громче", "сделай громче", "увеличь громкость", "прибавь звук", "прибавь громкость", "добавь звук", "сделай звук громче") ->
                changeVolume(context, AudioManager.ADJUST_RAISE)

            text in listOf("тише", "сделай тише", "уменьши громкость", "убавь звук", "убавь громкость", "уменьши звук", "сделай звук тише") ->
                changeVolume(context, AudioManager.ADJUST_LOWER)

            text in listOf("выключи звук", "без звука", "убери звук", "отключи звук", "заглуши звук") ->
                changeVolume(context, AudioManager.ADJUST_MUTE)

            text in listOf("включи звук", "верни звук", "восстанови звук", "сними беззвучный режим") ->
                changeVolume(context, AudioManager.ADJUST_UNMUTE)

            text in listOf("максимальная громкость", "громкость на максимум", "звук на максимум", "сделай максимальную громкость") ->
                setMaximumVolume(context)

            Regex("^громкость\\s+\\d{1,3}\\s*(?:процентов|процента|процент|%)?$").matches(text) ->
                setVolumePercent(context, text)

            // МУЗЫКА

            text in listOf("пауза", "поставь на паузу", "останови музыку", "приостанови музыку", "поставь музыку на паузу") ->
                sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE)

            text in listOf("продолжи музыку", "продолжай музыку", "включи музыку", "играй", "возобнови музыку", "продолжи воспроизведение") ->
                sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY)

            text in listOf("следующий трек", "следующая песня", "переключи песню", "включи следующий трек", "дальше") ->
                sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)

            text in listOf("предыдущий трек", "предыдущая песня", "верни предыдущий трек", "включи предыдущую песню") ->
                sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)

            // СИСТЕМНАЯ НАВИГАЦИЯ

            text in listOf("назад", "нажми назад", "вернись назад") ->
                performAccessibilityAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)

            text in listOf("домой", "нажми домой", "на главный экран") ->
                performAccessibilityAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)

            text in listOf("последние приложения", "открой последние приложения", "покажи последние приложения") ->
                performAccessibilityAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
            // КОНТАКТЫ

            text.startsWithAny(
                "найди контакт ",
                "найди номер ",
                "открой контакт "
            ) -> {
                val name = when {
                    text.startsWith("найди контакт ") ->
                        original.substringAfterIgnoreCase("найди контакт ")
                    text.startsWith("найди номер ") ->
                        original.substringAfterIgnoreCase("найди номер ")
                    else ->
                        original.substringAfterIgnoreCase("открой контакт ")
                }

                findContact(context, name, false)
            }

            text.startsWith("позвони ") -> {
                val target = original.substringAfterIgnoreCase("позвони ").trim()

                if (target.any { it.isLetter() }) {
                    findContact(context, target, true)
                } else {
                    dialNumber(context, target)
                }
            }

            // УВЕДОМЛЕНИЯ

    // ЯРКОСТЬ ЭКРАНА

            text in listOf("ярче", "увеличь яркость", "сделай ярче", "яркость выше") ->
                changeBrightness(context, 1)

            text in listOf("темнее", "уменьши яркость", "сделай темнее", "яркость ниже") ->
                changeBrightness(context, -1)

            text in listOf("максимальная яркость", "яркость на максимум", "максимум яркости") ->
                changeBrightness(context, 2)

            text in listOf("минимальная яркость", "яркость на минимум", "минимум яркости") ->
                changeBrightness(context, -2)

            Regex("^яркость\\s+\\d{1,3}\\s*(?:процентов|процента|процент|%)?$").matches(text) ->
                setBrightnessPercent(context, text)
            // УВЕДОМЛЕНИЯ

            text in listOf("прочитай уведомления", "зачитай уведомления", "прочитай последние уведомления", "что нового в уведомлениях") ->
                readNotifications()

            text in listOf("настрой доступ к уведомлениям", "разреши чтение уведомлений", "открой настройки доступа к уведомлениям") ->
                openNotificationAccessSettings(context)
            // СИСТЕМНЫЕ НАСТРОЙКИ

            text in listOf(
                "открой настройки",
                "открой настройки телефона",
                "зайди в настройки",
                "перейди в настройки"
            ) -> openSettings(context)

            text.containsAny("настройки wi fi", "настройки вайфай", "настройки wifi", "включи wi fi", "выключи wi fi", "включи вайфай", "выключи вайфай", "включи wifi", "выключи wifi", "открой wifi", "открой вайфай") ->
                openSystemScreen(context, Settings.ACTION_WIFI_SETTINGS, "Wi-Fi")

            text.containsAny("настройки bluetooth", "настройки блютуз", "включи bluetooth", "выключи bluetooth", "включи блютуз", "выключи блютуз", "открой bluetooth", "открой блютуз") ->
                openSystemScreen(context, Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth")

            text.containsAny("настройки геолокации", "настройки местоположения") ->
                openSystemScreen(context, Settings.ACTION_LOCATION_SOURCE_SETTINGS, "геолокацию")

            text.contains("настройки звука") ->
                openSystemScreen(context, Settings.ACTION_SOUND_SETTINGS, "звук")

            text.contains("настройки экрана") ->
                openSystemScreen(context, Settings.ACTION_DISPLAY_SETTINGS, "экран")

            text.contains("настройки приложений") ->
                openSystemScreen(context, Settings.ACTION_APPLICATION_SETTINGS, "приложения")

            text.contains("специальные возможности") ->
                openSystemScreen(context, Settings.ACTION_ACCESSIBILITY_SETTINGS, "специальные возможности")

            // НАВИГАЦИЯ ПО ТЕЛЕФОНУ

            text in listOf("назад", "вернись назад", "предыдущий экран", "вернись на предыдущий экран") ->
                globalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
                    "Не удалось вернуться назад."
                )

            text in listOf("домой", "на главный экран", "открой главный экран", "перейди домой") ->
                globalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME,
                    "Не удалось открыть главный экран."
                )

            text.containsAny("последние приложения", "список последних приложений") ->
                globalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS,
                    "Не удалось открыть список приложений."
                )

            text.containsAny("шторку уведомлений", "покажи уведомления", "открой уведомления") ->
                globalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
                    "Не удалось открыть уведомления."
                )

            text.containsAny("быстрые настройки", "панель быстрых настроек") ->
                globalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
                    "Не удалось открыть быстрые настройки."
                )

            // РАБОТА ВНУТРИ ПРИЛОЖЕНИЙ

            text.startsWithAny(
                "открой переписку ",
                "открой чат ",
                "перейди в чат "
            ) -> {
                val target = when {
                    text.startsWith("открой переписку ") ->
                        original.substringAfterIgnoreCase("открой переписку ")
                    text.startsWith("открой чат ") ->
                        original.substringAfterIgnoreCase("открой чат ")
                    else ->
                        original.substringAfterIgnoreCase("перейди в чат ")
                }

                clickText(context, target)
            }

            text.startsWithAny(
                "открой раздел ",
                "перейди в раздел ",
                "покажи раздел "
            ) -> {
                val target = when {
                    text.startsWith("открой раздел ") ->
                        original.substringAfterIgnoreCase("открой раздел ")
                    text.startsWith("перейди в раздел ") ->
                        original.substringAfterIgnoreCase("перейди в раздел ")
                    else ->
                        original.substringAfterIgnoreCase("покажи раздел ")
                }

                clickText(context, target)
            }

            text in listOf(
                "что на экране",
                "прочитай экран",
                "прочитай что написано на экране",
                "прочитай сообщения",
                "что написано на экране"
            ) ->
                readScreen()

            text.containsAny("прокрути вниз", "листай вниз", "пролистай вниз", "прокрути страницу вниз") ->
                scroll(context, true)

            text.containsAny("прокрути вверх", "листай вверх", "пролистай вверх", "прокрути страницу вверх") ->
                scroll(context, false)

            text.startsWith("нажми кнопку ") ->
                clickText(context, original.substringAfterIgnoreCase("нажми кнопку "))

            text.startsWith("нажми ") ->
                clickText(context, original.substringAfterIgnoreCase("нажми "))

            text.startsWith("введи текст ") ->
                enterText(context, original)

            // КАРТЫ

            text.startsWithAny(
                "покажи на карте ",
                "найди на карте ",
                "открой карту и найди "
            ) -> {
                val place = when {
                    text.startsWith("покажи на карте ") ->
                        original.substringAfterIgnoreCase("покажи на карте ")
                    text.startsWith("найди на карте ") ->
                        original.substringAfterIgnoreCase("найди на карте ")
                    else ->
                        original.substringAfterIgnoreCase("открой карту и найди ")
                }

                openMap(context, place)
            }

    // ТАЙМЕР

            text.startsWithAny(
                "поставь таймер",
                "запусти таймер",
                "установи таймер",
                "таймер на "
            ) ->
                setTimer(context, text)

            // БУДИЛЬНИК

            text.startsWithAny(
                "поставь будильник",
                "установи будильник",
                "будильник на "
            ) ->
                setAlarm(context, text)

            // БРАУЗЕР И ПОИСК

            text in listOf("открой браузер", "открой интернет", "запусти браузер", "запусти интернет") ->
                openBrowser(context)

            text.startsWithAny(
                "найди в интернете ",
                "поищи в интернете ",
                "поищи в сети ",
                "найди в сети ",
                "загугли ",
                "поищи "
            ) || text.startsWith("найди ") -> {
                val query = when {
                    text.startsWith("найди в интернете ") ->
                        original.substringAfterIgnoreCase("найди в интернете ")
                    text.startsWith("поищи в интернете ") ->
                        original.substringAfterIgnoreCase("поищи в интернете ")
                    text.startsWith("поищи в сети ") ->
                        original.substringAfterIgnoreCase("поищи в сети ")
                    text.startsWith("найди в сети ") ->
                        original.substringAfterIgnoreCase("найди в сети ")
                    text.startsWith("загугли ") ->
                        original.substringAfterIgnoreCase("загугли ")
                    text.startsWith("поищи ") ->
                        original.substringAfterIgnoreCase("поищи ")
                    else ->
                        original.substringAfterIgnoreCase("найди ")
                }

                searchInternet(context, query)
            }

            text.startsWith("открой ссылку ") ->
                openLink(context, original.substringAfterIgnoreCase("открой ссылку "))

            // НАБОР НОМЕРА

            text.startsWithAny("набери номер ", "набери ") -> {
                val number = original.substringAfterIgnoreCase("набери ")
                    .removePrefixIgnoreCase("номер ")
                    .trim()

                dialNumber(context, number)
            }

            // ЧАСЫ

            text in listOf("открой будильник", "открой часы", "запусти часы", "покажи будильник") ->
                openSystemScreen(context, AlarmClock.ACTION_SHOW_ALARMS, "часы")

            // ПРИЛОЖЕНИЯ

            text.startsWithAny("открой приложение ", "запусти приложение ") -> {
                val name = original.substringAfterIgnoreCase("приложение ")
                openApp(context, name)
            }

            text.startsWith("запусти ") ->
                openApp(context, original.substringAfterIgnoreCase("запусти "))

            text.startsWith("открой ") -> {
                val name = original.substringAfterIgnoreCase("открой ").trim()
                if (name.isNotBlank()) openApp(context, name) else null
            }

            // ДЕЙСТВИЯ, ТРЕБУЮЩИЕ ПОДТВЕРЖДЕНИЯ

            text.startsWithAny(
                "отправь сообщение",
                "отправь письмо",
                "удали ",
                "опубликуй ",
                "купи ",
                "оплати ",
                "переведи деньги",
                "переведи средства"
            ) ->
                "Это действие требует отдельного подтверждения. Пока я его не выполняю."

            else -> null
        }
    }

    // ПОИСК КОНТАКТОВ

    private fun findContact(
       context: Context,
       requestedName: String,
       openDialer: Boolean
   ): String {
       val name = requestedName.trim()

       if (name.isBlank()) return "Назови имя контакта."

       if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
           PackageManager.PERMISSION_GRANTED
       ) {
           return "Сначала разреши доступ к контактам в настройках приложения."
       }

       return try {
           val projection = arrayOf(
               ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
               ContactsContract.CommonDataKinds.Phone.NUMBER
           )

           val selection =
               "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"

           val cursor = context.contentResolver.query(
               ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
               projection,
               selection,
               arrayOf("%$name%"),
               "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
           )

           val matches = linkedMapOf<String, Pair<String, String>>()

           cursor?.use {
               val nameIndex = it.getColumnIndexOrThrow(
                   ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
               )
               val numberIndex = it.getColumnIndexOrThrow(
                   ContactsContract.CommonDataKinds.Phone.NUMBER
               )

               while (it.moveToNext() && matches.size < 10) {
                   val contactName = it.getString(nameIndex)?.trim().orEmpty()
                   val phoneNumber = it.getString(numberIndex)?.trim().orEmpty()
                   val numberKey = phoneNumber.filter { char -> char.isDigit() }

                   if (contactName.isNotBlank() && numberKey.isNotBlank()) {
                       matches.putIfAbsent(
                           numberKey,
                           contactName to phoneNumber
                       )
                   }
               }
           }

           if (matches.isEmpty()) {
               return "Не нашёл контакт $name."
           }

           if (matches.size > 1) {
               val options = matches.values.take(4).joinToString("; ") { item ->
                   val lastDigits = item.second.filter { char -> char.isDigit() }.takeLast(4)
                   "${item.first}, номер заканчивается на $lastDigits"
               }

               return "Нашёл несколько совпадений: $options. Уточни, кому именно позвонить."
           }

           val contact = matches.values.first()
           val contactName = contact.first
           val phoneNumber = contact.second

           if (openDialer) {
               val intent = Intent(
                   Intent.ACTION_DIAL,
                   Uri.fromParts("tel", phoneNumber, null)
               ).apply {
                   addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
               }

               context.startActivity(intent)

               "Нашёл $contactName. Открываю номер в звонилке. Проверь его перед вызовом."
           } else {
               "Нашёл $contactName. Номер: $phoneNumber."
           }

       } catch (_: SecurityException) {
           "Нет доступа к контактам. Разреши его в настройках приложения."
       } catch (_: Exception) {
           "Не удалось выполнить поиск контакта."
       }
   }
   // ГРОМКОСТЬ

    private fun changeVolume(context: Context, direction: Int): String {
        return try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direction,
                AudioManager.FLAG_SHOW_UI
            )

            when (direction) {
                AudioManager.ADJUST_RAISE -> "Увеличиваю громкость."
                AudioManager.ADJUST_LOWER -> "Уменьшаю громкость."
                AudioManager.ADJUST_MUTE -> "Выключаю звук."
                else -> "Включаю звук."
            }
        } catch (_: Exception) {
            "Не удалось изменить громкость."
        }
    }

    private fun setMaximumVolume(context: Context): String {
        return try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            audio.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                AudioManager.FLAG_SHOW_UI
            )

            "Устанавливаю максимальную громкость."
        } catch (_: Exception) {
            "Не удалось изменить громкость."
        }
    }


    private fun setVolumePercent(context: Context, command: String): String {
        return try {
            val percent = Regex("\\d{1,3}").find(command)?.value?.toIntOrNull()
                ?: return "Назови громкость от 0 до 100 процентов."
            if (percent !in 0..100) return "Громкость должна быть от 0 до 100 процентов."

            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVolume = (maxVolume * percent / 100f).toInt().coerceIn(0, maxVolume)
            audio.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                targetVolume,
                AudioManager.FLAG_SHOW_UI
            )
            "Устанавливаю громкость на $percent процентов."
        } catch (_: SecurityException) {
            "Система не разрешила изменить громкость."
        } catch (_: Exception) {
            "Не удалось установить громкость."
        }
    }

    // УПРАВЛЕНИЕ МУЗЫКОЙ

    private fun sendMediaKey(context: Context, keyCode: Int): String {
        return try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))

            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PAUSE -> "Ставлю музыку на паузу."
                KeyEvent.KEYCODE_MEDIA_PLAY -> "Продолжаю воспроизведение."
                KeyEvent.KEYCODE_MEDIA_NEXT -> "Следующий трек."
                else -> "Предыдущий трек."
            }
        } catch (_: Exception) {
            "Не удалось управлять воспроизведением."
        }
    }

    // НОРМАЛИЗАЦИЯ ТЕКСТА

    private fun replaceRussianNumbers(value: String): String {
        var result = value.lowercase()
            .replace("полчаса", "30 минут")

        val words = mutableMapOf<String, String>()

        val basicNumbers = listOf(
            "ноль", "один", "два", "три", "четыре",
            "пять", "шесть", "семь", "восемь", "девять",
            "десять", "одиннадцать", "двенадцать", "тринадцать",
            "четырнадцать", "пятнадцать", "шестнадцать",
            "семнадцать", "восемнадцать", "девятнадцать"
        )

        basicNumbers.forEachIndexed { index, word ->
            words[word] = index.toString()
        }

        words["одна"] = "1"
        words["одну"] = "1"
        words["одно"] = "1"
        words["две"] = "2"

        val tens = listOf(
            20 to "двадцать",
            30 to "тридцать",
            40 to "сорок",
            50 to "пятьдесят"
        )

        val units = listOf(
            "", "один", "два", "три", "четыре",
            "пять", "шесть", "семь", "восемь", "девять"
        )

        for ((base, word) in tens) {
            words[word] = base.toString()

            for (unit in 1..9) {
                words["$word ${units[unit]}"] = (base + unit).toString()
            }
        }

        for ((word, number) in words.entries.sortedByDescending { it.key.length }) {
            val pattern = Regex(
                "(?<![\\p{L}])${Regex.escape(word)}(?![\\p{L}])"
            )
            result = pattern.replace(result, number)
        }

        return result
    }
    private fun normalize(value: String): String {
        return value.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}+ ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun String.containsAny(vararg values: String): Boolean =
        values.any { contains(it) }

    private fun String.startsWithAny(vararg values: String): Boolean =
        values.any { startsWith(it) }

    private fun String.substringAfterIgnoreCase(delimiter: String): String {
        val index = indexOf(delimiter, ignoreCase = true)
        return if (index < 0) this else substring(index + delimiter.length)
    }

    private fun String.removePrefixIgnoreCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this

    // СИСТЕМНЫЕ НАСТРОЙКИ

    private fun openSettings(context: Context): String =
        openSystemScreen(context, Settings.ACTION_SETTINGS, "настройки")

    private fun openSystemScreen(
        context: Context,
        action: String,
        name: String
    ): String {
        return try {
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            "Открываю $name."
        } catch (_: Exception) {
            "Не удалось открыть $name."
        }
    }

    // УВЕДОМЛЕНИЯ

    private fun readNotifications(): String {
        val items = CosNotificationService.latestNotifications.take(5)

        if (items.isEmpty()) {
            return "Новых уведомлений пока нет. Проверь, разрешён ли доступ к уведомлениям."
        }

        return "Последние уведомления. " + items.joinToString(". ")
    }

    private fun openNotificationAccessSettings(context: Context): String {
        return try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Открываю настройки доступа к уведомлениям."
        } catch (_: Exception) {
            "Не удалось открыть настройки уведомлений."
        }
    }
    // ЯРКОСТЬ ЭКРАНА

    private fun changeBrightness(context: Context, direction: Int): String {
        if (!Settings.System.canWrite(context)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return "Разреши Космосу изменять системные настройки, затем повтори команду."
        }

        return try {
            val current = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                128
            )

            val value = when (direction) {
                1 -> (current + 26).coerceAtMost(255)
                -1 -> (current - 26).coerceAtLeast(5)
                2 -> 255
                -2 -> 5
                else -> current
            }

            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                value
            )

            "Яркость изменена."
        } catch (_: Exception) {
            "Не удалось изменить яркость экрана."
        }
    }

    private fun setBrightnessPercent(context: Context, command: String): String {
        val match = Regex("(\\d{1,3})").find(command)
            ?: return "Назови яркость в процентах."

        val percent = match.value.toIntOrNull()
            ?: return "Не удалось распознать процент."

        if (percent !in 1..100) {
            return "Укажи значение от 1 до 100 процентов."
        }

        if (!Settings.System.canWrite(context)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return "Разреши Космосу изменять системные настройки, затем повтори команду."
        }

        return try {
            val value = (percent * 255 / 100).coerceAtLeast(1)
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                value
            )
            "Установлена яркость $percent процентов."
        } catch (_: Exception) {
            "Не удалось изменить яркость экрана."
        }
    }
    // ТАЙМЕР

    private fun setTimer(context: Context, command: String): String {
        return try {
            val parsedCommand = replaceRussianNumbers(command)
            val pattern = Regex(
                "(\\d+)\\s*(час(?:а|ов)?|ч|минут(?:а|ы)?|мин|секунд(?:а|ы)?|сек)"
            )

            var totalSeconds = 0L

            for (match in pattern.findAll(parsedCommand)) {
                val value = match.groupValues[1].toLongOrNull() ?: continue
                val unit = match.groupValues[2]

                val multiplier = when {
                    unit.startsWith("час") || unit == "ч" -> 3600L
                    unit.startsWith("мин") -> 60L
                    unit.startsWith("сек") -> 1L
                    else -> 0L
                }

                if (value > 0 && multiplier > 0) {
                    if (value > Int.MAX_VALUE.toLong() / multiplier) {
                        return "Слишком большая продолжительность таймера."
                    }

                    val addition = value * multiplier
                    if (totalSeconds > Int.MAX_VALUE.toLong() - addition) {
                        return "Слишком большая продолжительность таймера."
                    }

                    totalSeconds += addition
                }
            }

            if (totalSeconds <= 0L) {
                return "Укажи время таймера. Например: поставь таймер на пять минут."
            }

            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, totalSeconds.toInt())
                putExtra(AlarmClock.EXTRA_MESSAGE, "Таймер Космос")
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            "Устанавливаю таймер."

        } catch (_: Exception) {
            "Не удалось установить таймер."
        }
    }
    // БУДИЛЬНИК

    private fun setAlarm(context: Context, command: String): String {
        return try {
            val parsedCommand = replaceRussianNumbers(command)

            val timePattern = Regex(
                "\\b(?:на|в)\\s*(\\d{1,2})(?:\\s*[:.]\\s*(\\d{1,2})|\\s+(\\d{1,2}))?"
            )

            val match = timePattern.find(parsedCommand)
                ?: Regex("\\b(\\d{1,2})[:.](\\d{2})\\b").find(parsedCommand)
                ?: Regex("\\b(?:будильник|разбуди)\\D{0,12}(\\d{1,2})\\b").find(parsedCommand)
                ?: return "Назови время будильника. Например: поставь будильник на семь тридцать."

            val hourText = match.groupValues[1]
            val minuteText = match.groupValues.getOrNull(2).orEmpty()
                .ifBlank { match.groupValues.getOrNull(3).orEmpty() }

            var hour = hourText.toIntOrNull()
                ?: return "Не удалось распознать время."
            val minute = minuteText.toIntOrNull() ?: 0

            if (hour !in 0..23 || minute !in 0..59) {
                return "Время указано неправильно."
            }

            when {
                command.contains("вечера") && hour in 1..11 -> hour += 12
                command.contains("дня") && hour in 1..11 -> hour += 12
                command.contains("ночи") && hour == 12 -> hour = 0
                command.contains("утра") && hour == 12 -> hour = 0
            }

            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, "Будильник Космос")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            "Устанавливаю будильник на ${"%02d:%02d".format(hour, minute)}."

        } catch (_: Exception) {
            "Не удалось установить будильник."
        }
    }
    // БРАУЗЕР И ИНТЕРНЕТ

    private fun openBrowser(context: Context): String =
        openLink(context, "https://www.google.com")

    private fun searchInternet(context: Context, query: String): String {
        if (query.isBlank()) return "Что будем искать?"

        return openLink(
            context,
            "https://www.google.com/search?q=${Uri.encode(query)}"
        )
    }

    private fun openLink(context: Context, address: String): String {
        return try {
            val cleanAddress = address.trim()

            if (cleanAddress.isBlank()) {
                return "Не указана ссылка."
            }

            val fullAddress = when {
                cleanAddress.startsWith("https://", ignoreCase = true) ||
                    cleanAddress.startsWith("http://", ignoreCase = true) ->
                    cleanAddress

                else -> "https://$cleanAddress"
            }

            val uri = Uri.parse(fullAddress)

            if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
                return "Не удалось распознать ссылку."
            }

            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            "Открываю ссылку."
        } catch (_: Exception) {
            "Не удалось открыть ссылку."
        }
    }

    // КАРТЫ

    private fun openMap(context: Context, place: String): String {
        if (place.isBlank()) return "Уточни, что найти на карте."

        return try {
            val uri = Uri.parse("geo:0,0?q=${Uri.encode(place)}")

            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            "Ищу на карте: $place."
        } catch (_: Exception) {
            "Не удалось открыть карту."
        }
    }

    // ТЕЛЕФОН

    private fun dialNumber(context: Context, number: String): String {
        val cleanNumber = number.filter { it.isDigit() || it == '+' }

        if (cleanNumber.isBlank()) return "Не распознал номер телефона."

        return try {
            val intent = Intent(
                Intent.ACTION_DIAL,
                Uri.fromParts("tel", cleanNumber, null)
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            "Открываю набор номера. Проверь номер перед звонком."
        } catch (_: Exception) {
            "Не удалось открыть набор номера."
        }
    }

    // ЗАПУСК ПРИЛОЖЕНИЙ

    private fun openApp(context: Context, requestedName: String): String =
        launchApp(context, requestedName).message

    private fun launchApp(context: Context, requestedName: String): AppLaunchResult {
        val name = normalize(requestedName)

        if (name.isBlank()) {
            return AppLaunchResult("Какое приложение открыть?", null)
        }

        return try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(0)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }

            val aliases = (appAliases(name) + name)
                .map { normalize(it) }
                .filter { it.isNotBlank() }
                .distinct()

            fun compact(value: String): String =
                normalize(value).replace(" ", "")

            val labeledApps = apps.map { app ->
                app to normalize(pm.getApplicationLabel(app).toString())
            }

            val exactMatch = labeledApps
                .filter { (_, label) ->
                    label in aliases || aliases.any { compact(it) == compact(label) }
                }
                .sortedBy { (_, label) ->
                    aliases.indexOfFirst { it == label || compact(it) == compact(label) }
                        .let { if (it < 0) Int.MAX_VALUE else it }
                }
                .firstOrNull()

            val matchingApp = exactMatch?.first ?: run {
                val partialMatches = labeledApps.filter { (_, label) ->
                    aliases.any { alias ->
                        alias.length >= 3 &&
                            (label.contains(alias) ||
                                alias.contains(label) ||
                                compact(label).contains(compact(alias)) ||
                                compact(alias).contains(compact(label)))
                    }
                }

                if (partialMatches.size == 1) partialMatches.first().first else null
            }

            if (matchingApp == null) {
                return AppLaunchResult("Не нашёл приложение $requestedName.", null)
            }

            val intent = pm.getLaunchIntentForPackage(matchingApp.packageName)
                ?: return AppLaunchResult("Не удалось открыть $requestedName.", null)

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            AppLaunchResult(
                "Открываю ${pm.getApplicationLabel(matchingApp)}.",
                matchingApp.packageName
            )
        } catch (_: Exception) {
            AppLaunchResult("Не удалось открыть $requestedName.", null)
        }
    }

    // НАЗВАНИЯ И ПСЕВДОНИМЫ ПРИЛОЖЕНИЙ

    private fun appAliases(name: String): List<String> {
        return when (name) {
            "вк", "вконтакте" ->
                listOf("вконтакте", "vk", "vkontakte")

            "телега", "телеграм", "телеграмм", "telegram" ->
                listOf("telegram", "телеграм", "телеграмм")

            "ютуб", "youtube" ->
                listOf("youtube", "ютуб")

            "макс", "max" ->
                listOf("max", "макс")

            "максим", "maxim" ->
                listOf("максим", "maxim")

            "карты", "яндекс карты" ->
                listOf("яндекс карты", "yandex maps", "яндекс навигатор")

            "ридера", "readera" ->
                listOf("readera", "ридера")

            "йота", "yota" ->
                listOf("yota", "йота")

            "космос ноут", "космос ноутбук", "cosmos note" ->
                listOf("космос ноут", "cosmos note", "cosmos notebook")

            "камера" ->
                listOf("камера", "camera")

            "галерея", "галлерея" ->
                listOf("галерея", "gallery", "фото")

            "хром", "гугл хром", "chrome" ->
                listOf("chrome", "google chrome", "гугл хром")

            "озон", "ozon" ->
                listOf("ozon", "озон")

            "совком", "совкомбанк" ->
                listOf("совкомбанк", "sovcombank")

            "впн наружу", "vpn наружу", "наружу" ->
                listOf("vpn наружу", "наружу")

            "сообщения", "смс" ->
                listOf("сообщения", "messages", "google messages")

            "почта", "гугл почта", "gmail" ->
                listOf("gmail", "google mail", "гугл почта", "почта")

            "файлы", "мои файлы" ->
                listOf("файлы", "files", "мои файлы", "file manager")

            "яндекс го", "яндекс такси", "yandex go" ->
                listOf("яндекс go", "yandex go", "яндекс такси")

            "сканер кодов", "qr сканер", "сканер qr" ->
                listOf("сканер кодов", "qr scanner", "qr code scanner", "barcode scanner")

            "рустор", "rustore", "ru store" ->
                listOf("rustore", "ru store", "рустор")

            "погода", "meteored" ->
                listOf("meteored", "погода")

            "guitartuna", "guitar tuna", "гитар туна" ->
                listOf("guitartuna", "guitar tuna")

            "offradio", "оффрадио" ->
                listOf("offradio", "off radio", "оффрадио", "офф радио")

            "deepseek", "дипсик", "дип сик" ->
                listOf("deepseek", "дипсик")

            "гугл плей", "google play", "плей маркет", "плеймаркет" ->
                listOf("google play", "google play store", "play store", "плей маркет")

            "календарь", "заметки", "конвертер валют", "секундомер", "таймер" ->
                listOf("космос ноут", "cosmos note", "cosmos notebook")

            else -> listOf(name)
        }
    }

    // ЧТЕНИЕ ЭКРАНА

    private fun readScreen(): String {
        val service = CosAccessibilityService.instance
            ?: return accessibilityUnavailable()

        val screen = service.getScreenText()

        return if (screen.isBlank()) {
            "На экране не удалось распознать текст."
        } else {
            "На экране написано: ${screen.take(1500)}"
        }
    }

    // НАЖАТИЕ НА ЭЛЕМЕНТЫ

    private fun clickText(context: Context, target: String): String {
        val service = CosAccessibilityService.instance
            ?: return accessibilityUnavailable()

        val cleanTarget = target.trim()

        if (cleanTarget.isBlank()) {
            return "Уточни, на какую кнопку нажать."
        }

        val dangerousWords = listOf(
            "отправить", "удалить", "оплатить", "купить",
            "перевести", "опубликовать", "подтвердить",
            "заказать", "платёж", "платеж"
        )

        if (dangerousWords.any { cleanTarget.lowercase().contains(it) }) {
            return "Это действие требует отдельного подтверждения. Я не нажимаю эту кнопку автоматически."
        }

        return if (service.click(cleanTarget)) {
            "Нажимаю: $cleanTarget."
        } else {
            "Не нашёл кнопку или элемент: $cleanTarget."
        }
    }

    // ВВОД ТЕКСТА

    private fun enterText(context: Context, command: String): String {
        val service = CosAccessibilityService.instance
            ?: return accessibilityUnavailable()

        val prefix = "введи текст "
        val separator = " в поле "

        val content = command.trim().removePrefixIgnoreCase(prefix)
        val index = content.lastIndexOf(separator, ignoreCase = true)

        if (index < 0) {
            return "Укажи текст и идентификатор поля. Например: введи текст привет в поле com.example:id/message"
        }

        val value = content.substring(0, index).trim()
        val viewId = content.substring(index + separator.length).trim()

        if (value.isBlank() || viewId.isBlank()) {
            return "Не хватает текста или идентификатора поля."
        }

        return if (service.enterText(viewId, value)) {
            "Текст введён."
        } else {
            "Не удалось найти поле или ввести текст."
        }
    }

    // ПРОКРУТКА ЭКРАНА

    private fun scroll(context: Context, forward: Boolean): String {
        val service = CosAccessibilityService.instance
            ?: return accessibilityUnavailable()

        val success = if (forward) {
            service.scrollForward()
        } else {
            service.scrollBackward()
        }

        return when {
            success && forward -> "Прокручиваю вниз."
            success -> "Прокручиваю вверх."
            else -> "Не удалось прокрутить экран."
        }
    }

    // СИСТЕМНЫЕ ДЕЙСТВИЯ

    private fun globalAction(action: Int, error: String): String {
        val service = CosAccessibilityService.instance
            ?: return accessibilityUnavailable()

        return if (service.performGlobalAction(action)) {
            "Выполняю."
        } else {
            error
        }
    }

    private fun performAccessibilityAction(action: Int): String {
        val service = CosAccessibilityService.instance
            ?: return accessibilityUnavailable()

        return if (service.performGlobalAction(action)) {
            "Выполняю."
        } else {
            "Не удалось выполнить действие."
        }
    }
    private fun accessibilityUnavailable(): String =
        "Сначала включи Cosmos в настройках специальных возможностей Android."
}






package com.cosmosa27.cosmosassistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object CosCommandExecutor {

    fun execute(context: Context, command: String): String? {
        val original = command.trim()
        val text = normalize(original)

        if (text.isBlank()) return null

        return when {

            // СИСТЕМНЫЕ НАСТРОЙКИ

            text in listOf(
                "открой настройки",
                "открой настройки телефона",
                "зайди в настройки",
                "перейди в настройки"
            ) -> openSettings(context)

            text.containsAny("настройки wi fi", "настройки вайфай", "настройки wifi") ->
                openSystemScreen(context, Settings.ACTION_WIFI_SETTINGS, "Wi-Fi")

            text.containsAny("настройки bluetooth", "настройки блютуз") ->
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

            // НАВИГАЦИЯ

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

            // РАБОТА С ЭКРАНОМ

            text in listOf("что на экране", "прочитай экран", "прочитай что написано на экране") ->
                readScreen()

            text.containsAny("прокрути вниз", "листай вниз", "пролистай вниз", "прокрути страницу вниз") ->
                scroll(context, true)

            text.containsAny("прокрути вверх", "листай вверх", "пролистай вверх", "прокрути страницу вверх") ->
                scroll(context, false)

            text.startsWith("нажми кнопку ") ->
                clickText(context, original.substringAfter("нажми кнопку ", ignoreCase = true))

            text.startsWith("нажми ") ->
                clickText(context, original.substringAfter("нажми ", ignoreCase = true))

            text.startsWith("введи текст ") ->
                enterText(context, original)

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
                        original.substringAfter("найди в интернете ", ignoreCase = true)
                    text.startsWith("поищи в интернете ") ->
                        original.substringAfter("поищи в интернете ", ignoreCase = true)
                    text.startsWith("поищи в сети ") ->
                        original.substringAfter("поищи в сети ", ignoreCase = true)
                    text.startsWith("найди в сети ") ->
                        original.substringAfter("найди в сети ", ignoreCase = true)
                    text.startsWith("загугли ") ->
                        original.substringAfter("загугли ", ignoreCase = true)
                    text.startsWith("поищи ") ->
                        original.substringAfter("поищи ", ignoreCase = true)
                    else ->
                        original.substringAfter("найди ", ignoreCase = true)
                }

                searchInternet(context, query)
            }

            text.startsWith("открой ссылку ") ->
                openLink(context, original.substringAfter("открой ссылку ", ignoreCase = true))

            text.startsWithAny("покажи на карте ", "найди на карте ", "открой карту и найди ") -> {
                val place = when {
                    text.startsWith("покажи на карте ") ->
                        original.substringAfter("покажи на карте ", ignoreCase = true)
                    text.startsWith("найди на карте ") ->
                        original.substringAfter("найди на карте ", ignoreCase = true)
                    else ->
                        original.substringAfter("открой карту и найди ", ignoreCase = true)
                }
                openMap(context, place)
            }

            // ТЕЛЕФОН

            text.startsWithAny("позвони ", "набери номер ", "набери ") -> {
                val number = original.substringAfter(" ").removePrefix("номер ").trim()
                dialNumber(context, number)
            }

            // ЧАСЫ

            text in listOf("открой будильник", "открой часы", "запусти часы", "покажи будильник") ->
                openCategory(context, Intent.CATEGORY_APP_CLOCK, "часы")

            // ПРИЛОЖЕНИЯ

            text.startsWithAny("открой приложение ", "запусти приложение ") -> {
                val name = original.substringAfter("приложение ", ignoreCase = true)
                openApp(context, name)
            }

            text.startsWith("запусти ") ->
                openApp(context, original.substringAfter("запусти ", ignoreCase = true))

            text.startsWith("открой ") -> {
                val name = original.substringAfter("открой ", ignoreCase = true).trim()
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

    private fun openBrowser(context: Context): String =
        openLink(context, "https://www.google.com")

    private fun searchInternet(context: Context, query: String): String {
        if (query.isBlank()) return "Что будем искать?"
        return openLink(context, "https://www.google.com/search?q=${Uri.encode(query)}")
    }

    private fun openLink(context: Context, address: String): String {
        return try {
            val uri = Uri.parse(address.trim())

            if (uri.scheme !in listOf("http", "https")) {
                return "Можно открывать только ссылки HTTP или HTTPS."
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

    private fun dialNumber(context: Context, number: String): String {
        val cleanNumber = number.filter { it.isDigit() || it == '+' }

        if (cleanNumber.isBlank()) return "Не распознал номер телефона."

        return try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Открываю набор номера. Проверь номер перед звонком."
        } catch (_: Exception) {
            "Не удалось открыть набор номера."
        }
    }

    private fun openCategory(
        context: Context,
        category: String,
        name: String
    ): String {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(category)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Открываю $name."
        } catch (_: Exception) {
            "Не удалось открыть $name."
        }
    }

    private fun openApp(context: Context, requestedName: String): String {
        val name = requestedName.trim().lowercase()

        if (name.isBlank()) return "Какое приложение открыть?"

        return try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(0)

            val matchingApp = apps
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .sortedBy {
                    val label = pm.getApplicationLabel(it).toString().lowercase()
                    if (label == name) 0 else 1
                }
                .firstOrNull {
                    pm.getApplicationLabel(it).toString()
                        .lowercase().contains(name)
                }

            if (matchingApp == null) return "Не нашёл приложение $requestedName."

            val intent = pm.getLaunchIntentForPackage(matchingApp.packageName)
                ?: return "Не удалось открыть $requestedName."

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            "Открываю ${pm.getApplicationLabel(matchingApp)}."
        } catch (_: Exception) {
            "Не удалось открыть $requestedName."
        }
    }

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

    private fun clickText(context: Context, target: String): String {
        val service = CosAccessibilityService.instance
            ?: return accessibilityUnavailable()

        val cleanTarget = target.trim()
        if (cleanTarget.isBlank()) return "Уточни, на какую кнопку нажать."

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

    private fun String.removePrefixIgnoreCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this

    private fun scroll(context: Context, forward: Boolean): String {
        val service = CosAccessibilityService.instance
            ?: return accessibilityUnavailable()

        val success = if (forward) service.scrollForward() else service.scrollBackward()

        return when {
            success && forward -> "Прокручиваю вниз."
            success -> "Прокручиваю вверх."
            else -> "Не удалось прокрутить экран."
        }
    }

    private fun globalAction(action: Int, error: String): String {
        val service = CosAccessibilityService.instance
            ?: return accessibilityUnavailable()

        return if (service.performGlobalAction(action)) "Выполняю." else error
    }

    private fun accessibilityUnavailable(): String =
        "Сначала включи Cosmos в настройках специальных возможностей Android."
}

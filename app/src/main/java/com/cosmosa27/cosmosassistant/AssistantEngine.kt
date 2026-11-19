package com.cosmosa27.cosmosassistant

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.widget.Toast
import java.util.Locale

object AssistantEngine {
    fun execute(context: Context, raw: String): String {
        val command = raw.trim()
        if (command.isEmpty()) return "Что нужно сделать?"
        val lower = command.lowercase(Locale("ru", "RU"))
        val response = when {
            lower.contains("будильник") || lower.contains("будиль") -> {
                val match = Regex("(?:на\\s*)?(\\d{1,2})(?::(\\d{2}))?").find(lower)
                if (match != null) {
                    val hour = match.groupValues[1].toIntOrNull() ?: 7
                    val minute = match.groupValues[2].toIntOrNull() ?: 0
                    if (hour !in 0..23 || minute !in 0..59) "Уточни время будильника, например 7:30." else {
                        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                            putExtra(AlarmClock.EXTRA_HOUR, hour); putExtra(AlarmClock.EXTRA_MINUTES, minute)
                            putExtra(AlarmClock.EXTRA_MESSAGE, "Cosmos Assistant")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        launch(context, intent, "Не удалось открыть будильник") ?: "Открываю установку будильника на $hour:${minute.toString().padStart(2,'0')}"
                    }
                } else "На какое время поставить будильник? Например, на 7:30."
            }
            lower.contains("youtube") || lower.contains("ютуб") -> {
                if (launchPackage(context, "com.google.android.youtube")) "Открываю YouTube"
                else { openWeb(context, "https://www.youtube.com"); "Открываю YouTube в браузере" }
            }
            lower.contains("вконтакте") || lower == "вк" || lower.contains("открой вк") -> {
                if (launchPackage(context, "com.vkontakte.android")) "Открываю ВКонтакте" else openWeb(context, "https://vk.com")
            }
            lower.contains("музык") || lower.contains("включи музыку") -> {
                val intent = Intent("android.intent.action.MUSIC_PLAYER").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (launch(context, intent, null) == null) "Открываю музыкальный плеер"
                else { openWeb(context, "https://music.youtube.com"); "Открываю музыкальный сервис в браузере" }
            }
            lower.contains("позвони") || lower.contains("набери") -> {
                val number = Regex("\\+?[0-9][0-9() -]{8,}[0-9]").find(command)?.value
                if (number == null) "Кому позвонить? Назови номер или открой контакты." else {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    launch(context, intent, "Не удалось открыть набор номера") ?: "Открываю набор номера"
                }
            }
            lower.contains("контакт") || lower.contains("телефонную книгу") -> {
                val intent = Intent(Intent.ACTION_VIEW).apply { type = "vnd.android.cursor.dir/contact"; addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                launch(context, intent, "Не удалось открыть контакты") ?: "Открываю контакты"
            }
            lower.contains("настройки") -> {
                launch(context, Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "Не удалось открыть настройки") ?: "Открываю настройки телефона"
            }
            lower.contains("wi-fi") || lower.contains("вайфай") || lower.contains("вай фай") -> {
                launch(context, Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "Не удалось открыть настройки Wi-Fi") ?: "Открываю настройки Wi-Fi"
            }
            lower.contains("bluetooth") || lower.contains("блютуз") -> {
                launch(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "Не удалось открыть Bluetooth") ?: "Открываю настройки Bluetooth"
            }
            lower.contains("камера") || lower == "открой фото" -> {
                val intent = context.packageManager.getLaunchIntentForPackage("com.android.camera")
                    ?: context.packageManager.getLaunchIntentForPackage("com.huawei.camera")
                if (intent != null) { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); launch(context, intent, "Не удалось открыть камеру") ?: "Открываю камеру" }
                else "Не нашёл приложение камеры. Открой его вручную."
            }
            lower.startsWith("открой ") -> {
                val name = lower.removePrefix("открой ").trim()
                if (name.isBlank()) "Какое приложение открыть?" else {
                    val pkg = when {
                        name.contains("браузер") || name.contains("интернет") -> "com.android.chrome"
                        name.contains("телефон") || name.contains("звонил") -> "com.google.android.dialer"
                        else -> null
                    }
                    if (pkg != null && launchPackage(context, pkg)) "Открываю $name" else "Не нашёл приложение «$name». Назови его точнее."
                }
            }
            lower.startsWith("найди ") || lower.startsWith("поищи ") || lower.startsWith("загугли ") || lower.startsWith("поиск ") -> {
                val query = command.substringAfter(' ').trim()
                if (query.isBlank()) "Что именно найти?" else if (isOnline(context)) {
                    openWeb(context, "https://www.google.com/search?q=" + Uri.encode(query)); "Ищу в интернете"
                } else "Сейчас нет интернета. Могу выполнять команды телефона офлайн: открывать приложения, будильник, контакты и настройки."
            }
            Regex("\\d{10,}").containsMatchIn(command) -> {
                val number = Regex("\\+?[0-9() -]{10,}").find(command)?.value?.trim()
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number ?: ""))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launch(context, intent, "Не удалось открыть набор номера") ?: "Открываю набор номера"
            }
            else -> if (isOnline(context)) {
                openWeb(context, "https://www.google.com/search?q=" + Uri.encode(command)); "Ищу информацию по запросу"
            } else "Я сейчас офлайн. Уточни команду для телефона: открыть приложение, поставить будильник, открыть контакты или настройки."
        }
        Toast.makeText(context, response, Toast.LENGTH_SHORT).show()
        return response
    }

    private fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun launchPackage(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(context, intent, null) == null
    }

    private fun launch(context: Context, intent: Intent, error: String?): String? {
        return try { context.startActivity(intent); null } catch (_: Exception) { error ?: "" }
    }

    private fun openWeb(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(intent) } catch (_: Exception) { Toast.makeText(context, "Нет приложения для открытия ссылки", Toast.LENGTH_SHORT).show() }
    }
}

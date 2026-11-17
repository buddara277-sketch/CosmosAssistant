package com.cosmosa27.cosmosassistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.speech.tts.TextToSpeech
import android.widget.Toast
import java.util.Locale

object AssistantEngine {
    fun execute(context: Context, raw: String) {
        val command = raw.trim()
        val lower = command.lowercase(Locale("ru", "RU"))
        val message = when {
            lower.contains("будильник") || lower.contains("будиль") -> {
                val hour = Regex("(?:на\\s*)?(\\d{1,2})(?::(\\d{2}))?").find(lower)
                if (hour != null) {
                    val h = hour.groupValues[1].toIntOrNull() ?: 7
                    val m = hour.groupValues[2].toIntOrNull() ?: 0
                    val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, h.coerceIn(0,23))
                        putExtra(AlarmClock.EXTRA_MINUTES, m.coerceIn(0,59))
                        putExtra(AlarmClock.EXTRA_MESSAGE, "Cosmos Assistant")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(i); "Открываю создание будильника"
                } else {
                    context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Открываю будильники"
                }
            }
            lower.contains("youtube") || lower.contains("ютуб") -> {
                launchPackage(context, "com.google.android.youtube") ?: openWeb(context, "https://www.youtube.com")
                "Открываю YouTube"
            }
            lower.contains("вконтакте") || lower == "вк" || lower.contains("открой вк") -> {
                launchPackage(context, "com.vkontakte.android") ?: openWeb(context, "https://vk.com")
                "Открываю ВКонтакте"
            }
            lower.contains("музык") || lower.contains("включи музыку") -> {
                val i = Intent("android.intent.action.MUSIC_PLAYER").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { context.startActivity(i) } catch (_: Exception) { openWeb(context, "https://music.youtube.com") }
                "Открываю музыкальный плеер"
            }
            lower.startsWith("найди ") || lower.startsWith("поищи ") || lower.startsWith("загугли ") -> {
                openWeb(context, "https://www.google.com/search?q=" + Uri.encode(command.substringAfter(' ')))
                "Ищу в интернете"
            }
            lower.startsWith("открой ") -> {
                val name = lower.removePrefix("открой ").trim()
                val pkg = when {
                    name.contains("браузер") || name.contains("интернет") -> "com.android.chrome"
                    name.contains("телефон") || name.contains("звонил") -> "com.google.android.dialer"
                    else -> null
                }
                val launched = pkg?.let { launchPackage(context, it) }
                if (launched == null) openWeb(context, "https://www.google.com/search?q=" + Uri.encode(name))
                "Пробую открыть: $name"
            }
            Regex("\\d{10,}").containsMatchIn(command) -> {
                val number = Regex("\\+?[0-9() -]{10,}").find(command)?.value?.trim()
                val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number ?: "")))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i); "Открываю набор номера"
            }
            else -> {
                openWeb(context, "https://www.google.com/search?q=" + Uri.encode(command))
                "Ищу информацию по запросу"
            }
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun launchPackage(context: Context, packageName: String): Boolean? {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    private fun openWeb(context: Context, url: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }
}

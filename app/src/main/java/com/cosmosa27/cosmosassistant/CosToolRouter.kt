
package com.cosmosa27.cosmosassistant

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object CosToolRouter {

    data class ToolResult(
        val handled: Boolean,
        val response: String
    )

    // Распознаёт структурированную команду от Qwen.
    fun execute(context: Context, answer: String): ToolResult? {
        val marker = "COS_TOOL:"
        val start = answer.indexOf(marker)

        if (start < 0) return null

        return try {
            val jsonText = answer.substring(start + marker.length)
                .substringBefore("\n")
                .trim()

            val json = JSONObject(jsonText)
            val tool = json.optString("tool")
            val args = json.optJSONObject("args") ?: JSONObject()

            if (tool == "sequence") {
                val steps = args.optJSONArray("steps") ?: JSONArray()
                if (steps.length() !in 2..6) {
                    return ToolResult(true, "План должен содержать от двух до шести действий.")
                }
                val commands = (0 until steps.length()).mapNotNull { index ->
                    steps.optString(index).trim().takeIf { it.isNotBlank() }
                }
                if (commands.size != steps.length()) {
                    return ToolResult(true, "В плане есть пустое действие. Не выполняю его.")
                }
                val latch = CountDownLatch(1)
                var sequenceResult = "Не удалось завершить последовательность."
                CosCommandExecutor.executeSequence(
                    context,
                    commands.joinToString(", "),
                ) { result ->
                    sequenceResult = result
                    latch.countDown()
                }
                val completed = latch.await(100, TimeUnit.SECONDS)
                return ToolResult(
                    true,
                    if (completed) sequenceResult else "Выполнение заняло слишком много времени. Остановил ожидание; проверь экран телефона."
                )
            }

            val command = when (tool) {

                "open_app" ->
                    "открой приложение ${args.optString("name")}"

                "open_settings" ->
                    "открой настройки"

                "back" ->
                    "назад"

                "home" ->
                    "домой"

                "recents" ->
                    "открой последние приложения"

                "notifications" ->
                    "открой шторку уведомлений"

                "quick_settings" ->
                    "открой быстрые настройки"

                "read_screen" ->
                    "прочитай экран"

                "click" ->
                    "нажми ${args.optString("text")}"

                "type_text" ->
                    "введи текст ${args.optString("text")} в поле ${args.optString("view_id")}"

                "scroll_down" ->
                    "прокрути вниз"

                "scroll_up" ->
                    "прокрути вверх"

                "search_web" ->
                    "найди в интернете ${args.optString("query")}"

                "open_url" ->
                    "открой ссылку ${args.optString("url")}"

                "open_map" ->
                    "покажи на карте ${args.optString("place")}"

                "dial" ->
                    "позвони ${args.optString("number")}"

                "volume_up" -> "сделай громче"
                "volume_down" -> "сделай тише"
                "mute" -> "выключи звук"
                "unmute" -> "включи звук"
                "volume_max" -> "громкость на максимум"
                "set_volume" -> "громкость ${args.optString("percent")} процентов"
                "media_play" -> "продолжи музыку"
                "media_pause" -> "поставь музыку на паузу"
                "media_next" -> "следующий трек"
                "media_previous" -> "предыдущий трек"
                "brightness_up" -> "увеличь яркость"
                "brightness_down" -> "уменьши яркость"
                "brightness_max" -> "максимальная яркость"
                "brightness_min" -> "минимальная яркость"
                "set_brightness" -> "яркость ${args.optString("percent")} процентов"
                "set_alarm" -> "поставь будильник на ${args.optString("time")}"
                "set_timer" -> "поставь таймер на ${args.optString("duration")}"
                "find_contact" -> "найди контакт ${args.optString("name")}"
                "read_notifications" -> "прочитай уведомления"
                "open_wifi_settings" -> "открой настройки Wi-Fi"
                "open_bluetooth_settings" -> "открой настройки Bluetooth"
                "open_location_settings" -> "открой настройки геолокации"
                "open_sound_settings" -> "открой настройки звука"
                "open_display_settings" -> "открой настройки экрана"
                "open_accessibility_settings" -> "открой специальные возможности"

                else -> return ToolResult(
                    true,
                    "Я пока не умею выполнять это действие."
                )
            }

            val result = CosCommandExecutor.execute(context, command)

            ToolResult(
                true,
                result ?: "Не удалось выполнить действие."
            )

        } catch (_: Exception) {
            ToolResult(
                true,
                "Не удалось разобрать команду для телефона."
            )
        }
    }

}


package com.cosmosa27.cosmosassistant

import android.content.Context
import org.json.JSONObject

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

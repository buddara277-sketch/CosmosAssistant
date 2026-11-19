
package com.cosmosa27.cosmosassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object CosMemory {

    private const val PREFS = "cos_memory"
    private const val KEY_FACTS = "facts"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

    private fun readFacts(context: Context): MutableList<String> {
        val raw = preferences(context).getString(KEY_FACTS, null)
            ?: return mutableListOf()

        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { index ->
                array.optString(index)
            }.filter { it.isNotBlank() }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveFacts(context: Context, facts: List<String>) {
        val array = JSONArray()
        facts.forEach { array.put(it) }

        preferences(context).edit()
            .putString(KEY_FACTS, array.toString())
            .apply()
    }

    fun remember(context: Context, fact: String): String {
        val clean = fact.trim().trimEnd('.', '!', '?')

        if (clean.length < 3) {
            return "Скажи, что именно мне запомнить."
        }

        val facts = readFacts(context)

        if (facts.any { it.equals(clean, ignoreCase = true) }) {
            return "Я уже помню это."
        }

        facts.add(clean)
        saveFacts(context, facts)

        return "Хорошо, я запомнил."
    }

    fun forget(context: Context, fact: String): String {
        val clean = fact.trim().trimEnd('.', '!', '?')

        if (clean.isBlank()) {
            return "Уточни, что именно нужно забыть."
        }

        val facts = readFacts(context)
        val before = facts.size

        facts.removeAll {
            it.equals(clean, ignoreCase = true) ||
                it.contains(clean, ignoreCase = true)
        }

        if (facts.size == before) {
            return "Такого факта в моей памяти нет."
        }

        saveFacts(context, facts)
        return "Хорошо, я это забыл."
    }

    fun list(context: Context): String {
        val facts = readFacts(context)

        if (facts.isEmpty()) {
            return "Пока моя память пуста."
        }

        return "Вот что я помню:\n" +
            facts.mapIndexed { index, fact ->
                "${index + 1}. $fact"
            }.joinToString("\n")
    }

    fun handleCommand(context: Context, input: String): String? {
        val text = input.trim()
        val lower = text.lowercase()

        if (
            lower.contains("что ты обо мне помнишь") ||
            lower.contains("что ты помнишь обо мне") ||
            lower == "покажи память" ||
            lower == "моя память"
        ) {
            return list(context)
        }

        val rememberPrefixes = listOf(
            "запомни, что ",
            "запомни что ",
            "запомни "
        )

        for (prefix in rememberPrefixes) {
            if (lower.startsWith(prefix)) {
                return remember(context, text.substring(prefix.length))
            }
        }

        val forgetPrefixes = listOf(
            "забудь, что ",
            "забудь что ",
            "забудь "
        )

        for (prefix in forgetPrefixes) {
            if (lower.startsWith(prefix)) {
                return forget(context, text.substring(prefix.length))
            }
        }

        return null
    }

    fun contextFor(context: Context, question: String): String {
        val facts = readFacts(context)
        if (facts.isEmpty()) return ""

        val words = question.lowercase()
            .split(Regex("[^а-яёa-z0-9]+"))
            .filter { it.length >= 4 }
            .toSet()

        val relevant = facts.filter { fact ->
            val factWords = fact.lowercase()
                .split(Regex("[^а-яёa-z0-9]+"))
                .filter { it.length >= 4 }
                .toSet()

            words.any { it in factWords }
        }.take(5)

        if (relevant.isEmpty()) return ""

        return "Из долговременной памяти пользователя:\n" +
            relevant.joinToString("\n") { "- $it" }
    }
}

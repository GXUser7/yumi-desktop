package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.AppLanguage
import java.util.Locale
import kotlinx.serialization.json.*

class Strings(private val language: () -> AppLanguage = { AppLanguage.System }) {
    val russian: Boolean get() = (language().tag ?: Locale.getDefault().language) == "ru"
    private fun table(russian: Boolean): JsonObject = if (russian) RussianTable else EnglishTable

    private companion object {
        // Immutable resource tables are shared across models and loaded only when used.
        val RussianTable by lazy { load("ru") }
        val EnglishTable by lazy { load("en") }
        fun load(language: String): JsonObject =
            Json.parseToJsonElement(checkNotNull(Strings::class.java.getResourceAsStream("/strings-$language.json"))
                .bufferedReader().use { it.readText() }).jsonObject
    }
    fun text(ru: String, en: String) = if (russian) ru else en
    fun get(id: Int, vararg args: Any): String {
        val pattern = table(russian)[id.toString()]?.jsonPrimitive?.content ?: "#$id"
        return if (args.isEmpty()) pattern else String.format(if (russian) Locale.forLanguageTag("ru") else Locale.ENGLISH, pattern, *args)
    }
    fun plural(id: Int, count: Int): String {
        val form = if (russian) when { count % 10 == 1 && count % 100 != 11 -> "one"; count % 10 in 2..4 && count % 100 !in 12..14 -> "few"; else -> "many" }
            else if (count == 1) "one" else "other"
        val table = table(russian)
        val value = (table["$id.$form"] ?: table["$id.other"])?.jsonPrimitive?.content ?: "%d"
        return String.format(value, count)
    }
}

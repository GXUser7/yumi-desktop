package com.mydrop.vpn.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import com.mydrop.vpn.ui.theme.EmojiFamily

/**
 * A server's name with the emoji in it drawn by a font this app brought along.
 *
 * Providers write their names in pictures — a flag, a lightning bolt for a fast node, a star for a
 * recommended one — and on Windows that goes wrong twice.
 *
 * The flag goes wrong outright. A flag emoji is two Regional Indicators, and Segoe UI Emoji
 * deliberately has no glyph for the pair; Microsoft ships the letters instead, so every server in
 * the list read "SE" or "DE" in pale grey. Nothing in the app can fix that, because the glyph is
 * not on the machine.
 *
 * The rest goes wrong intermittently, which is worse, because it looks like a bug in the list
 * rather than in the platform. Left to the system's own fallback, "⚡ ⭐" comes out in colour and a
 * lone "⭐" comes out as monochrome line art — Segoe UI Symbol answers for U+2B50 before Segoe UI
 * Emoji is asked, and only a neighbouring emoji drags the resolution back to the colour font. Two
 * servers in the same list, one starred in yellow and one in grey, for no reason a reader could
 * ever guess. A variation selector does not talk it out of that.
 *
 * So nothing is left to fallback. Every emoji run in the string is spanned to [EmojiFamily]
 * explicitly, which draws all of them — flags included — from one file with one look. What is not
 * emoji is untouched and keeps the app's own typeface.
 */
@Composable
fun rememberEmojiText(text: String, size: TextUnit): AnnotatedString =
    remember(text, size) { emojiText(text, size) }

fun emojiText(text: String, size: TextUnit): AnnotatedString {
    if (!text.hasEmoji()) return AnnotatedString(text)
    val emoji = SpanStyle(fontFamily = EmojiFamily, fontSize = size)
    return buildAnnotatedString {
        var index = 0
        while (index < text.length) {
            val start = index
            val pictorial = opensEmoji(text.codePointAt(index))
            while (index < text.length) {
                val point = text.codePointAt(index)
                // A joiner or a variation selector only ever extends a run it is already inside,
                // so a stray one in plain text does not open a span of its own.
                val belongs = if (pictorial) opensEmoji(point) || extendsEmoji(point) else !opensEmoji(point)
                if (!belongs) break
                index += Character.charCount(point)
            }
            if (pictorial) withStyle(emoji) { append(text, start, index) } else append(text, start, index)
        }
    }
}

private fun String.hasEmoji(): Boolean {
    var index = 0
    while (index < length) {
        val point = codePointAt(index)
        if (opensEmoji(point)) return true
        index += Character.charCount(point)
    }
    return false
}

/**
 * Whether a codepoint is a picture rather than a letter.
 *
 * The blocks, not a property table: `Character` has no emoji predicate, and shipping the real
 * Unicode data to answer a question about six symbols in a server name would be a lot of table for
 * very little. The ranges are the pictorial blocks — symbols, dingbats, arrows and the emoji
 * planes — and the edges of them are somebody's dingbat either way.
 */
private fun opensEmoji(point: Int): Boolean =
    point in 0x1F000..0x1FAFF ||
        point in 0x2600..0x27BF ||
        point in 0x2B00..0x2BFF ||
        point in 0x2300..0x23FF ||
        point in 0x2190..0x21FF

private fun extendsEmoji(point: Int): Boolean =
    point == 0xFE0F || point == 0x200D || point in 0x1F3FB..0x1F3FF

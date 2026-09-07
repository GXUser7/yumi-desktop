package com.yumi.desktop

import com.mydrop.vpn.core.format.ByteScale
import com.mydrop.vpn.core.format.Elapsed
import com.mydrop.vpn.core.format.ValueAndUnit
import com.mydrop.vpn.core.format.elapsedSince
import com.mydrop.vpn.core.format.megabitsValue
import com.mydrop.vpn.core.format.scaleBytes
import com.mydrop.vpn.data.Strings
import com.mydrop.vpn.shared.R

/**
 * The words for the numbers `core/format` produces, in the language the window is set to.
 *
 * The phone reads these out of resources inside a composition; here they hang off [Strings], which
 * is the same table under a different door. Value and unit stay apart all the way to the screen so
 * a tile can set the figure large and the unit quiet — that separation is the whole reason
 * [ValueAndUnit] exists.
 */

private fun ByteScale.sizeUnit(): Int = when (this) {
    ByteScale.Bytes -> R.string.unit_bytes
    ByteScale.Kilo -> R.string.unit_kibibytes
    ByteScale.Mega -> R.string.unit_mebibytes
    ByteScale.Giga -> R.string.unit_gibibytes
    ByteScale.Tera -> R.string.unit_tebibytes
    ByteScale.Peta -> R.string.unit_pebibytes
}

/** Rates stop at TB/s: there is no per-second figure a link can produce beyond it. */
private fun ByteScale.rateUnit(): Int = when (this) {
    ByteScale.Bytes -> R.string.unit_bytes_per_second
    ByteScale.Kilo -> R.string.unit_kibibytes_per_second
    ByteScale.Mega -> R.string.unit_mebibytes_per_second
    ByteScale.Giga -> R.string.unit_gibibytes_per_second
    ByteScale.Tera, ByteScale.Peta -> R.string.unit_tebibytes_per_second
}

fun Strings.bytes(value: Long): ValueAndUnit =
    scaleBytes(value).let { ValueAndUnit(it.value, get(it.scale.sizeUnit())) }

fun Strings.rate(bytesPerSecond: Long): ValueAndUnit =
    scaleBytes(bytesPerSecond).let { ValueAndUnit(it.value, get(it.scale.rateUnit())) }

fun Strings.megabits(bytesPerSecond: Long): ValueAndUnit =
    ValueAndUnit(megabitsValue(bytesPerSecond), get(R.string.unit_megabits_per_second))

fun Strings.relativeTime(epochMillis: Long?): String = when (val elapsed = elapsedSince(epochMillis)) {
    Elapsed.Never -> get(R.string.time_never)
    Elapsed.JustNow -> get(R.string.time_just_now)
    is Elapsed.Minutes -> get(R.string.time_minutes_ago, elapsed.count)
    is Elapsed.Hours -> get(R.string.time_hours_ago, elapsed.count)
    is Elapsed.Days -> get(R.string.time_days_ago, elapsed.count)
    Elapsed.LongAgo -> get(R.string.time_long_ago)
}

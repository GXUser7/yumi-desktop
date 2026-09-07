package com.yumi.desktop.model

import kotlinx.serialization.Serializable

/**
 * The accent the window is dressed in.
 *
 * Only the hue is stored, not a table of colours: every tone the scheme needs is derived from it
 * at the same saturations and lightnesses, so the six palettes are the same palette six times over
 * rather than six sets of hand-picked values that drift apart the moment one of them is tuned.
 * The derivation lives in the desktop module, where the colour types are — see `Palettes.kt`.
 *
 * [Glacier] carries no hue and means "leave the scheme alone". It is the app's own ice blue,
 * hand-tuned against the neutrals it sits on, and reproducing it from a formula would only make it
 * slightly worse.
 *
 * [partnerHue] is the second data colour — upload against download, the marker against the map.
 * Held roughly opposite the accent so the two channels stay apart at a glance.
 */
@Serializable
enum class Palette(
    val ru: String,
    val en: String,
    val hue: Float? = null,
    val partnerHue: Float = 0f,
) {
    Glacier("Ледник", "Glacier"),
    Rose("Роза", "Rose", hue = 344f, partnerHue = 186f),
    Lilac("Сирень", "Lilac", hue = 268f, partnerHue = 160f),
    Mint("Мята", "Mint", hue = 158f, partnerHue = 28f),
    Peach("Персик", "Peach", hue = 22f, partnerHue = 194f),
    Sand("Песок", "Sand", hue = 44f, partnerHue = 210f),
    Sea("Море", "Sea", hue = 196f, partnerHue = 32f),
}

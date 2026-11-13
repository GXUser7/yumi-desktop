package com.yumi.desktop.model

import kotlinx.serialization.Serializable

/**
 * The accent the window is dressed in.
 *
 * Only the option's name is persisted. Hue, chroma and the readable light accent are defined here;
 * the desktop derives every surface from them, including the neutral and monochrome options.
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
    val chroma: Float = 1f,
    val lightAccent: Float = 0.38f,
) {
    Glacier("Ледник", "Glacier"),
    Rose("Роза", "Rose", hue = 344f, partnerHue = 186f),
    Lilac("Сирень", "Lilac", hue = 268f, partnerHue = 160f),
    Mint("Мята", "Mint", hue = 158f, partnerHue = 28f),
    Peach("Персик", "Peach", hue = 22f, partnerHue = 194f),
    Sand("Песок", "Sand", hue = 44f, partnerHue = 210f),
    Sea("Море", "Sea", hue = 196f, partnerHue = 32f),
    Cobalt("Кобальт", "Cobalt", hue = 226f, partnerHue = 36f, chroma = 1.15f),
    Indigo("Индиго", "Indigo", hue = 246f, partnerHue = 52f),
    Forest("Лес", "Forest", hue = 126f, partnerHue = 30f, lightAccent = 0.32f),
    Lime("Лайм", "Lime", hue = 84f, partnerHue = 274f, lightAccent = 0.30f),
    Amber("Янтарь", "Amber", hue = 34f, partnerHue = 220f, chroma = 1.2f, lightAccent = 0.32f),
    Orchid("Орхидея", "Orchid", hue = 304f, partnerHue = 158f, chroma = 1.1f, lightAccent = 0.34f),
    Graphite("Графит", "Graphite", hue = 220f, partnerHue = 30f, chroma = 0f),
    Coffee("Кофе", "Coffee", hue = 26f, partnerHue = 210f, chroma = 0.32f, lightAccent = 0.36f),
}

package io.github.quentinpn.viewer.tv.player

// Audio and subtitle languages set on this device:
// each choice defaults to « Comme le compte Emby »; a choice made here is applied first,
// the account's preferences after it. Pure, so the whole selection is unit-tested.

/** Languages offered in Réglages, as Emby stores them (ISO 639-2/B), the usual ones first. */
val LANGUAGE_CHOICES: List<String> = listOf("fre", "eng", "jpn", "ger", "spa", "ita", "kor", "por", "dut", "chi")

/** Subtitle modes offered in Réglages, in the order of Emby's own settings. */
val SUBTITLE_MODE_CHOICES: List<SubtitleMode> =
    listOf(SubtitleMode.Default, SubtitleMode.Smart, SubtitleMode.OnlyForced, SubtitleMode.Always, SubtitleMode.HearingImpaired, SubtitleMode.None)

/** Null fields follow the account. */
data class LanguageOverrides(
    val audioLanguage: String? = null,
    val subtitleMode: SubtitleMode? = null,
    val subtitleLanguage: String? = null,
) {
    fun overrides(type: TrackType) = when (type) {
        TrackType.Audio -> audioLanguage != null
        TrackType.Subtitle -> subtitleMode != null || subtitleLanguage != null
    }
}

/**
 * The preferences to apply for [type] when this device overrides it: the local language
 * before the account's, the local mode instead of the account's. Null when nothing is set
 * here for [type]: the account alone decides, as before.
 */
fun localTracks(account: AccountTracks?, overrides: LanguageOverrides, type: TrackType): AccountTracks? {
    if (!overrides.overrides(type)) return null
    val base = account ?: AccountTracks()
    return when (type) {
        // A language chosen here wins over « play the file's default track »
        TrackType.Audio -> base.copy(
            audioLanguages = (listOfNotNull(overrides.audioLanguage) + base.audioLanguages).let(::distinctLanguages),
            playDefaultAudio = false,
        )
        TrackType.Subtitle -> base.copy(
            subtitleLanguages = (listOfNotNull(overrides.subtitleLanguage) + base.subtitleLanguages).let(::distinctLanguages),
            // Only a language set here, and no mode on the account: Emby's own default mode
            subtitleMode = overrides.subtitleMode ?: base.subtitleMode ?: SubtitleMode.Default,
        )
    }
}

/**
 * The local pick for [type], or null when this device sets nothing for it (the server's and
 * the account's picks then apply). A transcode's audio is the server's: nothing to pick.
 */
fun localPick(tracks: List<Track>, type: TrackType, account: AccountTracks?, overrides: LanguageOverrides, audioLang: String?, transcode: Boolean): Selection? {
    if (transcode) return null
    val local = localTracks(account, overrides, type) ?: return null
    return accountPick(tracks, type, local, audioLang)
}

/** "fre" and "fra" once: the first spelling kept. */
private fun distinctLanguages(codes: List<String>): List<String> =
    codes.fold(emptyList()) { kept, code -> if (kept.any { sameLanguage(it, code) }) kept else kept + code }

/** `Français`; the label of a language choice. */
fun languageLabel(code: String): String = languageName(code)

/** French label of a subtitle mode, as in Emby's web settings. */
fun subtitleModeLabel(mode: SubtitleMode): String = when (mode) {
    SubtitleMode.Default -> "Par défaut"
    SubtitleMode.Smart -> "Intelligent"
    SubtitleMode.OnlyForced -> "Forcés uniquement"
    SubtitleMode.Always -> "Toujours"
    SubtitleMode.HearingImpaired -> "Pour malentendants"
    SubtitleMode.None -> "Aucun"
}

/** `Comme le compte Emby (Français, Anglais)`; « non défini » when the account sets nothing, « … » before it is read. */
fun accountLabel(values: List<String>?): String = when {
    values == null -> "Comme le compte Emby"
    values.isEmpty() -> "Comme le compte Emby (non défini)"
    else -> "Comme le compte Emby (${values.joinToString(", ")})"
}

/** The next value of a choice list that starts with « Comme le compte » (null), then back to it. */
fun <T> nextOverride(current: T?, choices: List<T>): T? {
    val all = listOf<T?>(null) + choices
    return all[(all.indexOf(current).coerceAtLeast(0) + 1) % all.size]
}

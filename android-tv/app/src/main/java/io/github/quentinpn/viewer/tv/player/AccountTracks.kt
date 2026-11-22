package io.github.quentinpn.viewer.tv.player

import io.github.quentinpn.viewer.tv.emby.UserConfigurationDto

// The audio and subtitle preferences of the Emby account, applied by the
// client to the tracks mpv lists. They come after the remembered choice and
// the server's pick: they replace mpv's own guess (`slang`) when the server
// names no track, or names one that cannot be found in the file.

/** `SubtitlePlaybackMode` of the account, the values the Emby documentation lists. */
enum class SubtitleMode { Default, Always, OnlyForced, None, Smart, HearingImpaired }

/** The account's preferences, read once per session; languages as Emby stores them ("fre", "jpn"). */
data class AccountTracks(
    val audioLanguages: List<String> = emptyList(),
    /** `PlayDefaultAudioTrack`: the file's default audio track, whatever its language. */
    val playDefaultAudio: Boolean = false,
    val subtitleLanguages: List<String> = emptyList(),
    /** Null when the account has none or one this client does not know: no subtitle preference then. */
    val subtitleMode: SubtitleMode? = null,
)

fun accountTracks(config: UserConfigurationDto) = AccountTracks(
    audioLanguages = languages(config.audioLanguagePreference),
    playDefaultAudio = config.playDefaultAudioTrack,
    subtitleLanguages = languages(config.subtitleLanguagePreference),
    subtitleMode = config.subtitleMode?.trim()?.let { mode -> SubtitleMode.entries.find { it.name.equals(mode, ignoreCase = true) } },
)

/** "fre", or "fre,eng" in preference order; anything that is not a language code is dropped. */
internal fun languages(preference: String?): List<String> =
    preference.orEmpty().split(',')
        .map { it.trim() }
        .filter { code -> code.length in 2..8 && code.all { it.isLetter() || it == '-' } }
        .distinct()

/**
 * The account's track for [type] among [tracks]; [audioLang] is the language of the audio
 * that plays, which `Smart` compares with the subtitle languages. `Selection(null)` turns
 * subtitles off; null means the account asks for nothing here.
 */
fun accountPick(tracks: List<Track>, type: TrackType, account: AccountTracks?, audioLang: String?): Selection? {
    account ?: return null
    return when (type) {
        TrackType.Audio -> accountAudio(tracks, account)
        TrackType.Subtitle -> accountSubtitle(tracks, account, audioLang)
    }
}

/** The file's default track if the account says so, else the first track of the most wanted language. */
internal fun accountAudio(tracks: List<Track>, account: AccountTracks): Selection? {
    val audio = tracks.filter { it.type == TrackType.Audio }
    if (account.playDefaultAudio) audio.firstOrNull { it.isDefault }?.let { return Selection(it.id) }
    return inLanguageOrder(audio, account.audioLanguages).firstOrNull()?.let { Selection(it.id) }
}

/**
 * Per mode: `None` turns subtitles off. `OnlyForced` takes a forced track, `Default` a forced
 * track else the file's default one. `Always` takes a full track in a wanted language,
 * `HearingImpaired` the same with a hearing-impaired one first. `Smart` acts as `Always`
 * when the audio is in another language than the wanted ones, as `OnlyForced` otherwise.
 * A mode that finds no track turns subtitles off; one that needs a language the account
 * (or the audio, for `Smart`) does not give asks for nothing.
 */
internal fun accountSubtitle(tracks: List<Track>, account: AccountTracks, audioLang: String?): Selection? {
    val mode = account.subtitleMode ?: return null
    val subtitles = tracks.filter { it.type == TrackType.Subtitle }
    val track = when (mode) {
        SubtitleMode.None -> null
        SubtitleMode.OnlyForced -> forcedTrack(subtitles, account.subtitleLanguages, audioLang)
        SubtitleMode.Default -> forcedTrack(subtitles, account.subtitleLanguages, audioLang)
            ?: subtitles.firstOrNull { it.isDefault && !isForced(it) }
        SubtitleMode.Always, SubtitleMode.HearingImpaired -> {
            if (account.subtitleLanguages.isEmpty()) return null
            fullTrack(subtitles, account.subtitleLanguages, hearingImpaired = mode == SubtitleMode.HearingImpaired)
        }
        SubtitleMode.Smart -> {
            if (account.subtitleLanguages.isEmpty() || audioLang == null) return null
            if (account.subtitleLanguages.any { sameLanguage(it, audioLang) }) forcedTrack(subtitles, account.subtitleLanguages, audioLang)
            else fullTrack(subtitles, account.subtitleLanguages, hearingImpaired = false)
        }
    }
    return Selection(track?.id)
}

/**
 * A forced track in a wanted language or the audio's; any forced track when neither is known.
 * A forced track in another language is for another dub: left out.
 */
private fun forcedTrack(subtitles: List<Track>, languages: List<String>, audioLang: String?): Track? {
    val forced = subtitles.filter(::isForced)
    val wanted = languages + listOfNotNull(audioLang)
    if (wanted.isEmpty()) return forced.firstOrNull()
    return inLanguageOrder(forced, wanted).firstOrNull()
}

/** A full track in a wanted language (hearing-impaired first or last), else a forced one in it. */
private fun fullTrack(subtitles: List<Track>, languages: List<String>, hearingImpaired: Boolean): Track? {
    val wanted = inLanguageOrder(subtitles, languages)
    val full = wanted.filterNot(::isForced)
    return full.firstOrNull { isHearingImpaired(it) == hearingImpaired } ?: full.firstOrNull() ?: wanted.firstOrNull()
}

/** Tracks of [languages], the first language first; within one, the file's default track first, then file order. */
private fun inLanguageOrder(tracks: List<Track>, languages: List<String>): List<Track> =
    languages.flatMap { lang -> tracks.filter { sameLanguage(it.lang, lang) }.sortedByDescending { it.isDefault } }.distinct()

// The flag, or a title saying so: many files only name the track ("FR Forced", "Français SDH")
internal fun isForced(track: Track) =
    track.forced || track.title?.let { it.contains("forced", ignoreCase = true) || it.contains("forcé", ignoreCase = true) } == true

internal fun isHearingImpaired(track: Track) =
    track.hearingImpaired || track.title?.let { Regex("\\bSDH\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) } == true

/** The language of the audio that plays once [decision] is applied: its selection, else mpv's current track. */
fun playingAudioLanguage(tracks: List<Track>, decision: TrackDecision): String? {
    val audio = tracks.filter { it.type == TrackType.Audio }
    val id = decision.selection?.id
    return (if (id != null) audio.firstOrNull { it.id == id } else audio.firstOrNull { it.selected })?.lang
}

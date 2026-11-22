package io.github.quentinpn.viewer.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.emby.MediaSourceDto
import io.github.quentinpn.viewer.tv.emby.SourceStreamDto
import io.github.quentinpn.viewer.tv.player.languageName
import java.util.Locale

// « Informations techniques » of the film and episode pages, as the desktop's
// section in src/lib/detail/ItemDetail.svelte: video, file, audio and subtitle tracks of the
// version chosen on the page. A button opens a side panel, so the page itself stays short.

/** One line of the panel: `Audio` and its tracks. */
data class TechRow(val label: String, val values: List<String>)

/** The version the page plays: the chosen one, else the server's first. */
fun shownSource(sources: List<MediaSourceDto>, version: String?): MediaSourceDto? =
    sources.firstOrNull { it.id != null && it.id == version } ?: sources.firstOrNull()

/** Video, Fichier, Audio, Sous-titres; video only when the file has some. */
fun techRows(source: MediaSourceDto): List<TechRow> {
    val video = source.mediaStreams.firstOrNull { it.type == "Video" }
    val audio = source.mediaStreams.filter { it.type == "Audio" }
    val subtitles = source.mediaStreams.filter { it.type == "Subtitle" }
    val file = listOfNotNull(
        source.container?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT),
        formatSize(source.size),
        formatBitrate(source.bitrate),
    ).joinToString(" · ").ifEmpty { "Inconnu" }
    return listOfNotNull(
        video?.let { TechRow("Vidéo", listOf(videoLabel(it).ifEmpty { "Inconnue" })) },
        TechRow("Fichier", listOf(file)),
        TechRow("Audio", audio.map(::audioLabel).ifEmpty { listOf("Aucune piste") }),
        TechRow("Sous-titres", subtitles.map(::subtitleLabel).ifEmpty { listOf("Aucun") }),
    )
}

private val CODECS = mapOf(
    "ac3" to "Dolby Digital",
    "eac3" to "Dolby Digital Plus",
    "truehd" to "Dolby TrueHD",
    "dts" to "DTS",
    "aac" to "AAC",
    "flac" to "FLAC",
    "opus" to "Opus",
    "mp3" to "MP3",
    "vorbis" to "Vorbis",
    "subrip" to "SRT",
    "srt" to "SRT",
    "ass" to "ASS",
    "ssa" to "SSA",
    "pgssub" to "PGS",
    "hdmv_pgs_subtitle" to "PGS",
    "dvdsub" to "VobSub",
    "dvd_subtitle" to "VobSub",
    "mov_text" to "Texte",
    "webvtt" to "WebVTT",
)

private fun trackCodec(codec: String?): String? =
    codec?.takeIf { it.isNotBlank() }?.let { CODECS[it.lowercase(Locale.ROOT)] ?: it.uppercase(Locale.ROOT) }

private fun channels(count: Int?): String? = when (count) {
    null, 0 -> null
    1 -> "mono"
    2 -> "stéréo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "$count canaux"
}

private fun language(stream: SourceStreamDto) =
    stream.language?.takeIf { it.isNotBlank() }?.let(::languageName) ?: "Langue inconnue"

/** The track title, unless it only repeats the forced flag. */
private fun title(stream: SourceStreamDto): String? {
    val title = stream.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return if (stream.isForced && Regex("^forc(ed|é|e)s?$", RegexOption.IGNORE_CASE).matches(title)) null else title
}

/** `Français · Dolby Digital 5.1 · par défaut`. */
internal fun audioLabel(stream: SourceStreamDto): String = listOfNotNull(
    language(stream),
    listOfNotNull(trackCodec(stream.codec), channels(stream.channels)).joinToString(" ").ifEmpty { null },
    title(stream),
    "par défaut".takeIf { stream.isDefault },
).joinToString(" · ")

/** `Français · SRT · forcés · externe`. */
internal fun subtitleLabel(stream: SourceStreamDto): String = listOfNotNull(
    language(stream),
    trackCodec(stream.codec),
    title(stream),
    "forcés".takeIf { stream.isForced },
    "externe".takeIf { stream.isExternal },
    "par défaut".takeIf { stream.isDefault },
).joinToString(" · ")

/** « Informations techniques »: opens the panel; none without a version to describe. */
@Composable
internal fun TechInfoButton(source: MediaSourceDto?, versionName: String?) {
    source ?: return
    var open by remember { mutableStateOf(false) }
    // Short label: « Informations techniques » wrapped on two lines beside the other actions (Shield, 1.2.0)
    OutlinedButton(onClick = { open = true }) { Text("Infos techniques", maxLines = 1, softWrap = false) }
    if (open) TechInfoPanel(source, versionName) { open = false }
}

/** A glass panel on the right; each line takes the focus so the D-pad scrolls a long list. Back closes. */
@Composable
private fun TechInfoPanel(source: MediaSourceDto, versionName: String?, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    val rows = remember(source) { techRows(source) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier.width(560.dp).fillMaxHeight()
                    .background(Color(0xF21C1C1E))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 36.dp, vertical = 40.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Informations techniques", style = MaterialTheme.typography.headlineSmall, color = Palette.text)
                versionName?.let { Text(it, color = Palette.textSecondary) }
                var index = 0
                rows.forEach { row ->
                    Text(row.label, style = MaterialTheme.typography.titleSmall, color = Palette.textSecondary, modifier = Modifier.padding(top = 8.dp))
                    row.values.forEach { value ->
                        Line(value, if (index++ == 0) Modifier.focusRequester(first) else Modifier)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun Line(text: String, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier.fillMaxWidth()
            .focusable(interactionSource = interaction)
            .background(if (focused) Palette.surfaceRaised else Color.Transparent, RoundedCornerShape(10.dp))
            .border(1.dp, if (focused) Palette.accent else Color.Transparent, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(text, color = Palette.text)
    }
}

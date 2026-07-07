package dev.windplayer.translate

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.windplayer.WindColors
import dev.windplayer.WindRadius
import dev.windplayer.Phosphor
import dev.windplayer.PhosphorIcon
import dev.windplayer.ui.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Translation start experience.
 *
 * Phase 1: Choice dialog (compact popup with two buttons).
 * Phase 2: Language picker (full-screen, only when "Translate" is picked).
 *
 * MobileApp renders this when [TranslationStarter.pendingRequest] is non-null.
 */
@Composable
fun TranslateChoiceSheet(
    params: TranslationStarter.Params,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var showLanguagePicker by remember { mutableStateOf(false) }
    // Probe audio tracks before showing the choice.
    var audioTracks by remember { mutableStateOf<List<AudioExtractor.AudioTrackInfo>?>(null) }
    var selectedTrack by remember { mutableStateOf(-1) }

    LaunchedEffect(params.sourceUrl) {
        // Quick probe to list audio tracks (runs on IO).
        val extractor = AudioExtractor(context)
        audioTracks = withContext(kotlinx.coroutines.Dispatchers.IO) {
            extractor.listAudioTracks(params.sourceUrl)
        }
        // Default: first track.
        selectedTrack = audioTracks?.firstOrNull()?.index ?: -1
    }

    if (!showLanguagePicker) {
        // ---- Phase 1: Compact choice dialog ----
        val isLandscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        Dialog(onDismissRequest = onDismiss) {
            Surface(
                shape = WindRadius.Stadium,
                color = WindColors.LiftedCream
            ) {
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        I18n.get("generate_subtitles"),
                        color = WindColors.Ink,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        params.videoTitle,
                        color = WindColors.Slate,
                        fontSize = 13.sp,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(16.dp))

                    // Audio track selector — always show (even for 1 track,
                    // so the user knows what's being used). If probe hasn't
                    // finished, show a loading indicator.
                    when {
                        audioTracks == null -> {
                            // Probing in progress.
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = WindColors.Slate
                            )
                            Text("Detecting audio tracks…", color = WindColors.Slate, fontSize = 12.sp)
                            Spacer(Modifier.height(12.dp))
                        }
                        audioTracks!!.isEmpty() -> {
                            Text("⚠ No audio tracks found", color = WindColors.SignalOrange, fontSize = 12.sp)
                            Spacer(Modifier.height(12.dp))
                        }
                        else -> {
                            Text("Audio Track", color = WindColors.Slate, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            audioTracks!!.forEach { track ->
                                val isSelected = selectedTrack == track.index
                                Surface(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    onClick = { selectedTrack = track.index },
                                    shape = WindRadius.Pill,
                                    color = if (isSelected) WindColors.Ink else WindColors.White
                                ) {
                                    Text(
                                        track.displayName,
                                        color = if (isSelected) WindColors.CanvasCream else WindColors.Ink,
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                    }

                    // Two buttons.
                    if (isLandscape) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            BigChoiceButton(Modifier.weight(1f), Phosphor.MICROPHONE,
                                "Source Only", "Original language", false
                            ) {
                                startTranslation(context, params, false, null, selectedTrack)
                                onDismiss()
                            }
                            BigChoiceButton(Modifier.weight(1f), Phosphor.GLOBE,
                                "Translate", "Pick target language", true
                            ) {
                                val config = TranslationConfigHelper.load(context)
                                if (config.llmApiKey.isBlank()) {
                                    Toast.makeText(context,
                                        "Please set your API key in Settings → AI Translation",
                                        Toast.LENGTH_LONG).show()
                                } else { showLanguagePicker = true }
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            BigChoiceButton(Modifier.fillMaxWidth(), Phosphor.MICROPHONE,
                                "Source Only", "Original language transcription", false
                            ) {
                                startTranslation(context, params, false, null, selectedTrack)
                                onDismiss()
                            }
                            BigChoiceButton(Modifier.fillMaxWidth(), Phosphor.GLOBE,
                                "Translate", "Pick a target language", true
                            ) {
                                val config = TranslationConfigHelper.load(context)
                                if (config.llmApiKey.isBlank()) {
                                    Toast.makeText(context,
                                        "Please set your API key in Settings → AI Translation",
                                        Toast.LENGTH_LONG).show()
                                } else { showLanguagePicker = true }
                            }
                        }
                    }
                }
            }
        }
    } else {
        // ---- Phase 2: Full-screen language picker ----
        Dialog(
            onDismissRequest = { showLanguagePicker = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = WindColors.CanvasCream) {
                LanguagePickerPage(
                    onPick = { lang ->
                        TranslateLanguages.addRecent(context, lang)
                        startTranslation(context, params, true, lang, selectedTrack)
                        onDismiss()
                    },
                    onBack = { showLanguagePicker = false }
                )
            }
        }
    }
}

// ------------------------------------------------------------------
// Big choice button
// ------------------------------------------------------------------

@Composable
private fun BigChoiceButton(
    modifier: Modifier = Modifier,
    icon: Char,
    title: String,
    subtitle: String,
    isPrimary: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.heightIn(min = 110.dp),
        onClick = onClick,
        shape = WindRadius.Button,
        color = if (isPrimary) WindColors.Ink else WindColors.White
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            PhosphorIcon(
                icon, null,
                tint = if (isPrimary) WindColors.SignalOrange else WindColors.Slate,
                size = 28.dp
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    title,
                    color = if (isPrimary) WindColors.CanvasCream else WindColors.Ink,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    subtitle,
                    color = if (isPrimary) WindColors.CanvasCream.copy(alpha = 0.7f) else WindColors.Slate,
                    fontSize = 11.sp
                )
            }
        }
    }
}

// ------------------------------------------------------------------
// Language picker (full-screen)
// ------------------------------------------------------------------

@Composable
private fun LanguagePickerPage(
    onPick: (TranslateLanguages.Lang) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    // BUG-29: re-read recent list on every recomposition so it stays fresh
    // after the user picks a language and navigates back.
    val recent = TranslateLanguages.loadRecent(context)

    val filtered = if (search.isBlank()) TranslateLanguages.ALL
    else TranslateLanguages.ALL.filter {
        it.nativeName.contains(search, ignoreCase = true) ||
        it.englishName.contains(search, ignoreCase = true)
    }

    Column(Modifier.fillMaxSize()) {
        // Header
        Surface(color = WindColors.LiftedCream) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    PhosphorIcon(Phosphor.ARROW_LEFT, "Back", tint = WindColors.Ink, size = 22.dp)
                }
                Text("Select Target Language",
                    color = WindColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Search
        OutlinedTextField(
            value = search, onValueChange = { search = it },
            singleLine = true,
            placeholder = { Text("Search language…", color = WindColors.DustTaupe, fontSize = 14.sp) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            shape = WindRadius.Pill,
            textStyle = androidx.compose.ui.text.TextStyle(color = WindColors.Ink, fontSize = 15.sp),
            colors = TextFieldDefaults.colors(
                cursorColor = WindColors.Ink,
                focusedContainerColor = WindColors.White,
                unfocusedContainerColor = WindColors.White,
                focusedIndicatorColor = WindColors.Ink,
                unfocusedIndicatorColor = WindColors.Hairline
            )
        )

        LazyColumn(Modifier.weight(1f)) {
            if (search.isBlank() && recent.isNotEmpty()) {
                item {
                    Text("Recent", color = WindColors.Slate, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
                items(recent) { lang -> LanguageRow(lang) { onPick(lang) } }
                item { HorizontalDivider(color = WindColors.Hairline, modifier = Modifier.padding(vertical = 4.dp)) }
                item {
                    Text("All Languages", color = WindColors.Slate, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
            }
            items(filtered) { lang -> LanguageRow(lang) { onPick(lang) } }
        }
    }
}

@Composable
private fun LanguageRow(lang: TranslateLanguages.Lang, onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), color = Color.Transparent) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(lang.nativeName, color = WindColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(lang.englishName, color = WindColors.Slate, fontSize = 12.sp)
            }
            PhosphorIcon(Phosphor.CARET_RIGHT, null, tint = WindColors.DustTaupe, size = 16.dp)
        }
    }
}

// ------------------------------------------------------------------
// Start helper
// ------------------------------------------------------------------

private fun startTranslation(
    context: android.content.Context,
    params: TranslationStarter.Params,
    doTranslate: Boolean,
    targetLang: TranslateLanguages.Lang?,
    trackIndex: Int = -1
) {
    if (targetLang != null) {
        val config = TranslationConfigHelper.load(context)
        TranslationConfigHelper.save(context, config.copy(targetLanguage = targetLang.nativeName))
    }
    android.widget.Toast.makeText(context,
        if (doTranslate && targetLang != null) "Translating to ${targetLang.nativeName}"
        else "Transcribing audio",
        android.widget.Toast.LENGTH_SHORT).show()

    TranslateService.start(
        context = context,
        videoTitle = params.videoTitle,
        sourceUrl = params.sourceUrl,
        duration = params.duration,
        doTranslate = doTranslate,
        trackIndex = trackIndex
    )
}

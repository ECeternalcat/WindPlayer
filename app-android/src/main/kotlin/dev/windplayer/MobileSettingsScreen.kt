package dev.windplayer

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.windplayer.ui.I18n
import dev.windplayer.ui.PlayerSettings
import dev.windplayer.ui.ThemeMode

private enum class SettingsPage(val titleKey: String, val glyph: Char) {
    PLAYBACK("cat_playback", Phosphor.PLAY),
    VIDEO("cat_video", Phosphor.VIDEO),
    AUDIO("cat_audio", Phosphor.SPEAKER_HIGH),
    SUBTITLE("cat_subtitle", Phosphor.SUBTITLES),
    NETWORK("cat_network", Phosphor.MONITOR),
    SCREENSHOT("cat_screenshot", Phosphor.GAUGE),
    APPEARANCE("appearance", Phosphor.CORNERS_OUT),
    LANGUAGE("language", Phosphor.FILE),
    ADVANCED("cat_advanced", Phosphor.GEAR),
    ABOUT("about", Phosphor.STAR);
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileSettingsScreen(
    settings: PlayerSettings,
    onSettingsChanged: (PlayerSettings) -> Unit,
    onBack: () -> Unit
) {
    var page by remember { mutableStateOf<SettingsPage?>(null) }

    BackHandler {
        if (page != null) page = null else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = page?.let { I18n.get(it.titleKey) } ?: I18n.get("settings"),
                        color = WindColors.Ink,
                        fontWeight = FontWeight.Medium,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (page != null) page = null else onBack() }) {
                        PhosphorIcon(Phosphor.ARROW_LEFT, "Back", tint = WindColors.Ink, size = 22.dp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WindColors.LiftedCream)
            )
        },
        containerColor = WindColors.CanvasCream
    ) { padding ->
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                if (initialState == null) {
                    // Forward (list → category): new page enters from right,
                    // old list exits to left
                    (slideInHorizontally { it / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it / 3 } + fadeOut())
                } else {
                    // Back (category → list): list enters from left,
                    // old page exits to right — reverse of forward
                    (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally { it / 3 } + fadeOut())
                }
            },
            modifier = Modifier.padding(padding),
            label = "settings"
        ) { current ->
            if (current == null) {
                CategoryList(settings) { page = it }
            } else {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    when (current) {
                        SettingsPage.PLAYBACK -> PlaybackCategory(settings, onSettingsChanged)
                        SettingsPage.VIDEO -> VideoCategory(settings, onSettingsChanged)
                        SettingsPage.AUDIO -> AudioCategory(settings, onSettingsChanged)
                        SettingsPage.SUBTITLE -> SubtitleCategory(settings, onSettingsChanged)
                        SettingsPage.NETWORK -> NetworkCategory(settings, onSettingsChanged)
                        SettingsPage.SCREENSHOT -> ScreenshotCategory(settings, onSettingsChanged)
                        SettingsPage.APPEARANCE -> AppearanceCategory(settings, onSettingsChanged)
                        SettingsPage.LANGUAGE -> LanguageCategory(settings, onSettingsChanged)
                        SettingsPage.ADVANCED -> AdvancedCategory(settings, onSettingsChanged)
                        SettingsPage.ABOUT -> AboutCategory(settings)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------
// Category list (main page)
// ------------------------------------------------------------------

@Composable
private fun CategoryList(settings: PlayerSettings, onSelect: (SettingsPage) -> Unit) {
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        SettingsPage.entries.forEach { page ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(page) },
                color = WindColors.White,
                shape = WindRadius.Button
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PhosphorIcon(page.glyph, null, tint = WindColors.SignalOrange, size = 22.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = I18n.get(page.titleKey),
                        color = WindColors.Ink,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    PhosphorIcon(Phosphor.CARET_RIGHT, null, tint = WindColors.DustTaupe, size = 18.dp)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ------------------------------------------------------------------
// Category detail pages
// ------------------------------------------------------------------

@Composable
private fun PlaybackCategory(s: PlayerSettings, onChange: (PlayerSettings) -> Unit) {
    SliderRow(I18n.get("default_volume"), s.defaultVolume.toFloat(), 0f..100f, "${s.defaultVolume}%") {
        onChange(s.copy(defaultVolume = it.toInt()))
    }
    ToggleRow(I18n.get("hw_decode"), s.hwdecAuto) { onChange(s.copy(hwdecAuto = it)) }
    ToggleRow(I18n.get("auto_play_next"), s.autoPlayNext) { onChange(s.copy(autoPlayNext = it)) }
    ToggleRow(I18n.get("resume_playback"), s.resumePlayback) { onChange(s.copy(resumePlayback = it)) }
    SliderRow(I18n.get("default_speed"), s.defaultSpeed.toFloat(), 0.25f..4f, "%.2fx".format(s.defaultSpeed)) {
        onChange(s.copy(defaultSpeed = it.toDouble()))
    }
    SliderRow(I18n.get("seek_step_short"), s.seekStepShort.toFloat(), 1f..30f, "${s.seekStepShort}${I18n.get("seconds_unit")}") {
        onChange(s.copy(seekStepShort = it.toInt()))
    }
    SliderRow(I18n.get("seek_step_long"), s.seekStepLong.toFloat(), 10f..120f, "${s.seekStepLong}${I18n.get("seconds_unit")}") {
        onChange(s.copy(seekStepLong = it.toInt()))
    }
}

@Composable
private fun VideoCategory(s: PlayerSettings, onChange: (PlayerSettings) -> Unit) {
    DropdownRow(I18n.get("gpu_api"),
        listOf("auto" to I18n.get("auto_value"), "opengl" to "OpenGL", "vulkan" to "Vulkan", "d3d11" to "Direct3D 11"),
        s.gpuApi) { onChange(s.copy(gpuApi = it)) }
    ToggleRow(I18n.get("deinterlace"), s.deinterlace) { onChange(s.copy(deinterlace = it)) }
    DropdownRow(I18n.get("video_aspect"),
        listOf("-1" to I18n.get("auto_value"), "16:9" to "16:9", "4:3" to "4:3", "2.35:1" to "2.35:1"),
        s.videoAspect) { onChange(s.copy(videoAspect = it)) }
}

@Composable
private fun AudioCategory(s: PlayerSettings, onChange: (PlayerSettings) -> Unit) {
    DropdownRow(I18n.get("audio_channels"),
        listOf("auto" to I18n.get("auto_value"), "stereo" to "Stereo", "5.1" to "5.1", "7.1" to "7.1"),
        s.audioChannels) { onChange(s.copy(audioChannels = it)) }
    ToggleRow(I18n.get("pitch_correction"), s.pitchCorrection) { onChange(s.copy(pitchCorrection = it)) }
}

@Composable
private fun SubtitleCategory(s: PlayerSettings, onChange: (PlayerSettings) -> Unit) {
    SliderRow(I18n.get("font_size"), s.subFontSize.toFloat(), 15f..100f, "${s.subFontSize}") {
        onChange(s.copy(subFontSize = it.toInt()))
    }
    SliderRow(I18n.get("border_size"), s.subBorderSize.toFloat(), 0f..10f, "${s.subBorderSize}") {
        onChange(s.copy(subBorderSize = it.toInt()))
    }
    ColorRow(I18n.get("sub_color"), s.subColor) { onChange(s.copy(subColor = it)) }
    ColorRow(I18n.get("sub_back_color"), s.subBackColor) { onChange(s.copy(subBackColor = it)) }
    DropdownRow(I18n.get("sub_font_family"),
        listOf("sans-serif" to "Sans-serif", "serif" to "Serif", "monospace" to "Monospace"),
        s.subFontFamily) { onChange(s.copy(subFontFamily = it)) }
    DropdownRow(I18n.get("sub_align"),
        listOf("bottom" to I18n.get("align_bottom"), "center" to I18n.get("align_center"), "top" to I18n.get("align_top")),
        s.subAlignY) { onChange(s.copy(subAlignY = it)) }
}

@Composable
private fun NetworkCategory(s: PlayerSettings, onChange: (PlayerSettings) -> Unit) {
    SliderRow(I18n.get("cache_size"), s.cacheSize.toFloat(), 30f..1000f, "${s.cacheSize} ${I18n.get("mib_unit")}") {
        onChange(s.copy(cacheSize = it.toInt()))
    }
    TextFieldRow(I18n.get("user_agent"), s.userAgent, "mpv/${I18n.get("auto_value")}") {
        onChange(s.copy(userAgent = it))
    }
}

@Composable
private fun ScreenshotCategory(s: PlayerSettings, onChange: (PlayerSettings) -> Unit) {
    DropdownRow(I18n.get("screenshot_format"),
        listOf("png" to "PNG", "jpg" to "JPG", "jpeg" to "JPEG"),
        s.screenshotFormat) { onChange(s.copy(screenshotFormat = it)) }
    if (s.screenshotFormat != "png") {
        SliderRow(I18n.get("screenshot_quality"), s.screenshotJpegQuality.toFloat(), 10f..100f, "${s.screenshotJpegQuality}%") {
            onChange(s.copy(screenshotJpegQuality = it.toInt()))
        }
    }
    ToggleRow(I18n.get("screenshot_subtitles"), s.screenshotSubtitles) {
        onChange(s.copy(screenshotSubtitles = it))
    }
}

@Composable
private fun AppearanceCategory(s: PlayerSettings, onChange: (PlayerSettings) -> Unit) {
    val context = LocalContext.current
    // isDynamicColorAvailable() unavailable in CMP 1.9.0; SDK >= S is the equivalent.
    val dynamicSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S

    Spacer(Modifier.height(8.dp))
    Text(I18n.get("appearance"), color = WindColors.Slate, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.48.sp)
    Spacer(Modifier.height(10.dp))
    ThemeSelectorRow(s.themeMode) { onChange(s.copy(themeMode = it)) }

    Spacer(Modifier.height(24.dp))
    Text(I18n.get("accent_color"), color = WindColors.Slate, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.48.sp)
    Spacer(Modifier.height(10.dp))

    // Accent color selector
    val options = listOf(
        dev.windplayer.ui.AccentColor.WINDPLAYER to I18n.get("accent_windplayer"),
        dev.windplayer.ui.AccentColor.AUTO to I18n.get("accent_auto")
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (mode, label) ->
            val isActive = s.accentColor == mode
            Surface(
                modifier = Modifier.weight(1f).clickable {
                    if (mode == dev.windplayer.ui.AccentColor.AUTO && !dynamicSupported) {
                        Toast.makeText(context, I18n.get("accent_auto_unsupported"), Toast.LENGTH_LONG).show()
                    } else {
                        onChange(s.copy(accentColor = mode))
                    }
                },
                shape = WindRadius.Button,
                color = if (isActive) WindColors.Ink else WindColors.White,
                border = androidx.compose.foundation.BorderStroke(1.5.dp, if (isActive) WindColors.Ink else WindColors.Hairline)
            ) {
                Row(Modifier.padding(vertical = 10.dp, horizontal = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(
                        if (mode == dev.windplayer.ui.AccentColor.WINDPLAYER) Color(0xFFCF4500) else Color(0xFF6750A4)
                    ))
                    Spacer(Modifier.width(8.dp))
                    Text(label, color = if (isActive) WindColors.CanvasCream else WindColors.Ink, fontSize = 13.sp, fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun LanguageCategory(s: PlayerSettings, onChange: (PlayerSettings) -> Unit) {
    I18n.languages.forEach { (code, name) ->
        Surface(
            modifier = Modifier.fillMaxWidth().clickable {
                I18n.current = code
                onChange(s.copy(language = code))
            },
            color = if (s.language == code) WindColors.Ink else WindColors.White,
            shape = WindRadius.Pill
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    color = if (s.language == code) WindColors.CanvasCream else WindColors.Ink,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                if (s.language == code) {
                    PhosphorIcon(Phosphor.CHECK, null, tint = WindColors.CanvasCream, size = 18.dp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AdvancedCategory(s: PlayerSettings, onChange: (PlayerSettings) -> Unit) {
    Spacer(Modifier.height(16.dp))
    OutlinedButton(
        onClick = {
            val reset = PlayerSettings.DEFAULT.copy(language = s.language, themeMode = s.themeMode)
            I18n.current = reset.language
            onChange(reset)
        },
        modifier = Modifier.fillMaxWidth(),
        shape = WindRadius.Button,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, WindColors.Ink),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = WindColors.White, contentColor = WindColors.Ink),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(I18n.get("reset_defaults"), fontWeight = FontWeight.Medium)
    }
}

// ------------------------------------------------------------------
// Category: About
// ------------------------------------------------------------------

@Composable
private fun AboutCategory(s: PlayerSettings) {
    val context = LocalContext.current
    var showLicenses by remember { mutableStateOf(false) }

    if (showLicenses) {
        // Licenses list view
        BackHandler { showLicenses = false }
        Text(
            I18n.get("about_licenses"),
            color = WindColors.Ink, fontSize = 20.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Spacer(Modifier.height(8.dp))
        dev.windplayer.ui.THIRD_PARTY_LIBRARIES.forEach { lib ->
            Surface(
                modifier = Modifier.fillMaxWidth().clickable {
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(lib.url))
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                },
                color = WindColors.White, shape = WindRadius.Consent,
                border = androidx.compose.foundation.BorderStroke(1.dp, WindColors.Hairline)
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(lib.name, color = WindColors.Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Surface(
                            shape = WindRadius.Chip,
                            color = if (lib.license.contains("MIT")) WindColors.LightSignalOrange.copy(alpha = 0.15f) else WindColors.Ink.copy(alpha = 0.08f)
                        ) {
                            Text(lib.license, color = WindColors.Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text("${lib.version} · ${lib.url.removePrefix("https://")}", color = WindColors.Slate, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { showLicenses = false }, modifier = Modifier.fillMaxWidth(), shape = WindRadius.Button) {
            Text(I18n.get("close"))
        }
        return
    }

    Spacer(Modifier.height(8.dp))

    Text(
        "WindPlayer ${I18n.get("about_version")}",
        color = WindColors.Ink, fontSize = 26.sp, fontWeight = FontWeight.Medium
    )
    Spacer(Modifier.height(6.dp))
    Text(I18n.get("about_made_by"), color = WindColors.Slate, fontSize = 14.sp)

    Spacer(Modifier.height(24.dp))

    // Phosphor Icons credit
    Text(I18n.get("about_icons"), color = WindColors.Slate, fontSize = 13.sp)
    Spacer(Modifier.height(16.dp))

    // Open Source Licenses button
    OutlinedButton(
        onClick = { showLicenses = true },
        shape = WindRadius.Button,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, WindColors.Ink),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = WindColors.White, contentColor = WindColors.Ink),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
    ) {
        PhosphorIcon(Phosphor.LIST, null, tint = WindColors.Ink, size = 16.dp)
        Spacer(Modifier.width(8.dp))
        Text(I18n.get("about_licenses"), fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }

    Spacer(Modifier.height(24.dp))

    Text(I18n.get("about_github"), color = WindColors.Slate, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.48.sp)
    Spacer(Modifier.height(4.dp))
    Surface(
        onClick = {
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/ECeternalcat/WindPlayer"))
                context.startActivity(intent)
            } catch (_: Exception) {}
        },
        color = WindColors.White, shape = WindRadius.Pill
    ) {
        Text(
            "github.com/ECeternalcat/WindPlayer",
            color = Color(0xFF3860BE), fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }

    Spacer(Modifier.height(28.dp))
    Text(I18n.get("about_license"), color = WindColors.DustTaupe, fontSize = 13.sp)
}

// ------------------------------------------------------------------
// Reusable components
// ------------------------------------------------------------------

@Composable
private fun SliderRow(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    displayValue: String, onChange: (Float) -> Unit
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = WindColors.Ink, fontSize = 14.sp)
            Text(displayValue, color = WindColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Slider(value, onChange, valueRange = range, colors = SliderDefaults.colors(
            thumbColor = WindColors.Ink, activeTrackColor = WindColors.Ink, inactiveTrackColor = WindColors.Hairline
        ))
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = WindColors.Ink, fontSize = 14.sp)
        Switch(checked, onChange, colors = SwitchDefaults.colors(
            checkedThumbColor = WindColors.CanvasCream, checkedTrackColor = WindColors.Ink, checkedBorderColor = WindColors.Ink,
            uncheckedThumbColor = WindColors.White, uncheckedTrackColor = WindColors.DustTaupe, uncheckedBorderColor = WindColors.DustTaupe
        ))
    }
}

@Composable
private fun DropdownRow(
    label: String, options: List<Pair<String, String>>, selectedKey: String, onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val displayLabel = options.find { it.first == selectedKey }?.second ?: selectedKey
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = WindColors.Ink, fontSize = 14.sp)
        Box {
            Surface(onClick = { expanded = true }, color = WindColors.White, shape = WindRadius.Pill) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(displayLabel, color = WindColors.Ink, fontSize = 13.sp)
                    Spacer(Modifier.width(4.dp))
                    PhosphorIcon(Phosphor.CARET_DOWN, null, tint = WindColors.Slate, size = 12.dp)
                }
            }
            DropdownMenu(expanded, { expanded = false }) {
                options.forEach { (key, name) ->
                    DropdownMenuItem({ Text(name) }, { onSelect(key); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun ColorRow(label: String, selected: String, onSelect: (String) -> Unit) {
    val presets = listOf(
        "#FFFFFF" to Color.White, "#FFFF00" to Color(0xFFFFFF00),
        "#00FFFF" to Color(0xFF00FFFF), "#FF5500" to Color(0xFFFF5500),
        "#000000" to Color.Black, "#00000000" to Color.Transparent
    )
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = WindColors.Ink, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            presets.forEach { (hex, color) ->
                Box(Modifier.size(24.dp).clip(CircleShape).background(color)
                    .clickable { onSelect(hex) }
                    .then(if (selected.equals(hex, ignoreCase = true)) Modifier.background(WindColors.Ink.copy(alpha = 0.2f)) else Modifier)
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(selected.uppercase().take(7), color = WindColors.Slate, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun TextFieldRow(label: String, value: String, placeholder: String, onValueChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, color = WindColors.Ink, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value, onValueChange, singleLine = true,
            placeholder = { Text(placeholder, color = WindColors.DustTaupe, fontSize = 13.sp) },
            modifier = Modifier.fillMaxWidth(), shape = WindRadius.Pill,
            textStyle = androidx.compose.ui.text.TextStyle(color = WindColors.Ink, fontSize = 13.sp),
            colors = TextFieldDefaults.colors(
                cursorColor = WindColors.Ink, focusedContainerColor = WindColors.White, unfocusedContainerColor = WindColors.White,
                focusedIndicatorColor = WindColors.Ink, unfocusedIndicatorColor = WindColors.Hairline
            )
        )
    }
}

@Composable
private fun ThemeSelectorRow(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val options = listOf(
        ThemeMode.LIGHT to I18n.get("theme_light"),
        ThemeMode.DARK to I18n.get("theme_dark"),
        ThemeMode.SYSTEM to I18n.get("theme_system")
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (mode, label) ->
            val isActive = selected == mode
            Surface(
                modifier = Modifier.weight(1f).clickable { onSelect(mode) },
                shape = WindRadius.Button,
                color = if (isActive) WindColors.Ink else WindColors.White,
                border = androidx.compose.foundation.BorderStroke(1.5.dp, if (isActive) WindColors.Ink else WindColors.Hairline)
            ) {
                Text(
                    label,
                    color = if (isActive) WindColors.CanvasCream else WindColors.Ink,
                    fontSize = 13.sp, fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 10.dp)
                )
            }
        }
    }
}

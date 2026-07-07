package dev.windplayer.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ------------------------------------------------------------------
// Category enum
// ------------------------------------------------------------------

enum class SettingsCategory(val icon: String, val titleKey: String) {
    PLAYBACK("play", "cat_playback"),
    VIDEO("video", "cat_video"),
    AUDIO("speaker-high", "cat_audio"),
    SUBTITLE("subtitles", "cat_subtitle"),
    NETWORK("monitor", "cat_network"),
    SCREENSHOT("gauge", "cat_screenshot"),
    APPEARANCE("corners-out", "appearance"),
    LANGUAGE("file", "language"),
    ADVANCED("gear", "cat_advanced"),
    ABOUT("star", "about")
}

// ------------------------------------------------------------------
// Main screen
// ------------------------------------------------------------------

@Composable
fun SettingsScreen(
    settings: PlayerSettings,
    onSettingsChanged: (PlayerSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedCategory by remember { mutableStateOf(SettingsCategory.SUBTITLE) }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(WindColors.CanvasCream)
    ) {
        // ---- Left sidebar ----
        Column(
            modifier = Modifier
                .width(220.dp)
                .fillMaxHeight()
                .background(WindColors.LiftedCream)
                .padding(top = 16.dp)
        ) {
            // Back button + title
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                    Icon(
                        painter = iconPainter(PhosphorIcons.ARROW_LEFT),
                        contentDescription = I18n.get("back"),
                        tint = WindColors.Ink,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = I18n.get("settings"),
                    color = WindColors.Ink,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = WindColors.Hairline, thickness = 1.dp)

            // Category list
            SettingsCategory.entries.forEach { category ->
                val isSelected = selectedCategory == category
                // WindMotion: animate selection background + tint so moving
                // between categories feels smooth (was a hard color swap).
                val categoryBg by animateColorAsState(
                    targetValue = if (isSelected) WindColors.CanvasCream else Color.Transparent,
                    animationSpec = tween(WindMotion.DurFast, easing = WindMotion.EasingStandard),
                    label = "categoryBg"
                )
                val iconTint by animateColorAsState(
                    targetValue = if (isSelected) WindColors.SignalOrange else WindColors.Slate,
                    animationSpec = tween(WindMotion.DurFast, easing = WindMotion.EasingStandard),
                    label = "iconTint"
                )
                val textTint by animateColorAsState(
                    targetValue = if (isSelected) WindColors.Ink else WindColors.Slate,
                    animationSpec = tween(WindMotion.DurFast, easing = WindMotion.EasingStandard),
                    label = "textTint"
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedCategory = category },
                    color = categoryBg
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = iconPainter(category.icon),
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = I18n.get(category.titleKey),
                            color = textTint,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }
        }

        // ---- Right content panel ----
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            AnimatedContent(
                targetState = selectedCategory,
                // WindMotion: cross-fade between category pages with standard
                // easing. Was default spring (no duration); now aligned with
                // the rest of the app.
                transitionSpec = {
                    fadeIn(animationSpec = tween(WindMotion.DurMedium, easing = WindMotion.EasingStandard)) togetherWith
                    fadeOut(animationSpec = tween(WindMotion.DurMedium, easing = WindMotion.EasingExit))
                },
                label = "settings"
            ) { category ->
                Column {
                    when (category) {
                        SettingsCategory.PLAYBACK -> PlaybackSettings(settings, onSettingsChanged)
                        SettingsCategory.VIDEO -> VideoSettings(settings, onSettingsChanged)
                        SettingsCategory.AUDIO -> AudioSettings(settings, onSettingsChanged)
                        SettingsCategory.SUBTITLE -> SubtitleSettings(settings, onSettingsChanged)
                        SettingsCategory.NETWORK -> NetworkSettings(settings, onSettingsChanged)
                        SettingsCategory.SCREENSHOT -> ScreenshotSettings(settings, onSettingsChanged)
                        SettingsCategory.APPEARANCE -> AppearanceSettings(settings, onSettingsChanged)
                        SettingsCategory.LANGUAGE -> LanguageSettings(settings, onSettingsChanged)
                        SettingsCategory.ADVANCED -> AdvancedSettings(settings, onSettingsChanged)
                        SettingsCategory.ABOUT -> AboutSection()
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------
// Category: Playback
// ------------------------------------------------------------------

@Composable
private fun PlaybackSettings(s: PlayerSettings, onChange: (PlayerSettings) -> Unit) {
    CategoryTitle(SettingsCategory.PLAYBACK)

    SettingSliderRow(
        label = I18n.get("default_volume"),
        value = s.defaultVolume.toFloat(),
        range = 0f..100f,
        onValueChange = { onChange(s.copy(defaultVolume = it.toInt())) },
        displayValue = "${s.defaultVolume}%"
    )
    SettingToggleRow(
        label = I18n.get("hw_decode"),
        checked = s.hwdecAuto,
        onCheckedChange = { onChange(s.copy(hwdecAuto = it)) }
    )
    SettingToggleRow(
        label = I18n.get("auto_play_next"),
        checked = s.autoPlayNext,
        onCheckedChange = { onChange(s.copy(autoPlayNext = it)) }
    )
    SettingToggleRow(
        label = I18n.get("resume_playback"),
        checked = s.resumePlayback,
        onCheckedChange = { onChange(s.copy(resumePlayback = it)) }
    )
    SettingSliderRow(
        label = I18n.get("default_speed"),
        value = s.defaultSpeed.toFloat(),
        range = 0.25f..4f,
        onValueChange = { onChange(s.copy(defaultSpeed = it.toDouble())) },
        displayValue = "%.2fx".format(s.defaultSpeed)
    )
    SettingSliderRow(
        label = I18n.get("seek_step_short"),
        value = s.seekStepShort.toFloat(),
        range = 1f..30f,
        onValueChange = { onChange(s.copy(seekStepShort = it.toInt())) },
        displayValue = "${s.seekStepShort}${I18n.get("seconds_unit")}"
    )
    SettingSliderRow(
        label = I18n.get("seek_step_long"),
        value = s.seekStepLong.toFloat(),
        range = 10f..120f,
        onValueChange = { onChange(s.copy(seekStepLong = it.toInt())) },
        displayValue = "${s.seekStepLong}${I18n.get("seconds_unit")}"
    )
}

// ------------------------------------------------------------------
// Category: Video
// ------------------------------------------------------------------

@Composable
private fun VideoSettings(s: PlayerSettings, onChange: (PlayerSettings) -> Unit) {
    CategoryTitle(SettingsCategory.VIDEO)

    SettingDropdownRow(
        label = I18n.get("gpu_api"),
        options = listOf("auto" to I18n.get("auto_value"), "opengl" to "OpenGL", "vulkan" to "Vulkan", "d3d11" to "Direct3D 11"),
        selectedKey = s.gpuApi,
        onSelect = { onChange(s.copy(gpuApi = it)) }
    )
    SettingToggleRow(
        label = I18n.get("deinterlace"),
        checked = s.deinterlace,
        onCheckedChange = { onChange(s.copy(deinterlace = it)) }
    )
    SettingDropdownRow(
        label = I18n.get("video_aspect"),
        options = listOf("-1" to I18n.get("auto_value"), "16:9" to "16:9", "4:3" to "4:3", "2.35:1" to "2.35:1"),
        selectedKey = s.videoAspect,
        onSelect = { onChange(s.copy(videoAspect = it)) }
    )
}

// ------------------------------------------------------------------
// Category: Audio
// ------------------------------------------------------------------

@Composable
private fun AudioSettings(s: PlayerSettings, onChange: (PlayerSettings) -> Unit) {
    CategoryTitle(SettingsCategory.AUDIO)

    SettingDropdownRow(
        label = I18n.get("audio_channels"),
        options = listOf("auto" to I18n.get("auto_value"), "stereo" to "Stereo", "5.1" to "5.1 Surround", "7.1" to "7.1 Surround"),
        selectedKey = s.audioChannels,
        onSelect = { onChange(s.copy(audioChannels = it)) }
    )
    SettingToggleRow(
        label = I18n.get("pitch_correction"),
        checked = s.pitchCorrection,
        onCheckedChange = { onChange(s.copy(pitchCorrection = it)) }
    )
}

// ------------------------------------------------------------------
// Category: Subtitle
// ------------------------------------------------------------------

@Composable
private fun SubtitleSettings(s: PlayerSettings, onChange: (PlayerSettings) -> Unit) {
    CategoryTitle(SettingsCategory.SUBTITLE)

    SettingSliderRow(
        label = I18n.get("font_size"),
        value = s.subFontSize.toFloat(),
        range = 15f..100f,
        onValueChange = { onChange(s.copy(subFontSize = it.toInt())) },
        displayValue = "${s.subFontSize}"
    )
    SettingSliderRow(
        label = I18n.get("border_size"),
        value = s.subBorderSize.toFloat(),
        range = 0f..10f,
        onValueChange = { onChange(s.copy(subBorderSize = it.toInt())) },
        displayValue = "${s.subBorderSize}"
    )
    SettingColorRow(
        label = I18n.get("sub_color"),
        selected = s.subColor,
        onSelect = { onChange(s.copy(subColor = it)) }
    )
    SettingColorRow(
        label = I18n.get("sub_back_color"),
        selected = s.subBackColor,
        onSelect = { onChange(s.copy(subBackColor = it)) }
    )
    SettingDropdownRow(
        label = I18n.get("sub_font_family"),
        options = listOf("sans-serif" to "Sans-serif", "serif" to "Serif", "monospace" to "Monospace"),
        selectedKey = s.subFontFamily,
        onSelect = { onChange(s.copy(subFontFamily = it)) }
    )
    SettingDropdownRow(
        label = I18n.get("sub_align"),
        options = listOf(
            "bottom" to I18n.get("align_bottom"),
            "center" to I18n.get("align_center"),
            "top" to I18n.get("align_top")
        ),
        selectedKey = s.subAlignY,
        onSelect = { onChange(s.copy(subAlignY = it)) }
    )
}

// ------------------------------------------------------------------
// Category: Network
// ------------------------------------------------------------------

@Composable
private fun NetworkSettings(s: PlayerSettings, onChange: (PlayerSettings) -> Unit) {
    CategoryTitle(SettingsCategory.NETWORK)

    SettingSliderRow(
        label = I18n.get("cache_size"),
        value = s.cacheSize.toFloat(),
        range = 30f..1000f,
        onValueChange = { onChange(s.copy(cacheSize = it.toInt())) },
        displayValue = "${s.cacheSize} ${I18n.get("mib_unit")}"
    )
    SettingTextFieldRow(
        label = I18n.get("user_agent"),
        value = s.userAgent,
        placeholder = "mpv/${I18n.get("auto_value")}",
        onValueChange = { onChange(s.copy(userAgent = it)) }
    )
}

// ------------------------------------------------------------------
// Category: Screenshot
// ------------------------------------------------------------------

@Composable
private fun ScreenshotSettings(s: PlayerSettings, onChange: (PlayerSettings) -> Unit) {
    CategoryTitle(SettingsCategory.SCREENSHOT)

    SettingDropdownRow(
        label = I18n.get("screenshot_format"),
        options = listOf("png" to "PNG", "jpg" to "JPG", "jpeg" to "JPEG"),
        selectedKey = s.screenshotFormat,
        onSelect = { onChange(s.copy(screenshotFormat = it)) }
    )
    if (s.screenshotFormat != "png") {
        SettingSliderRow(
            label = I18n.get("screenshot_quality"),
            value = s.screenshotJpegQuality.toFloat(),
            range = 10f..100f,
            onValueChange = { onChange(s.copy(screenshotJpegQuality = it.toInt())) },
            displayValue = "${s.screenshotJpegQuality}%"
        )
    }
    SettingToggleRow(
        label = I18n.get("screenshot_subtitles"),
        checked = s.screenshotSubtitles,
        onCheckedChange = { onChange(s.copy(screenshotSubtitles = it)) }
    )
}

// ------------------------------------------------------------------
// Category: Appearance
// ------------------------------------------------------------------

@Composable
private fun AppearanceSettings(s: PlayerSettings, onChange: (PlayerSettings) -> Unit) {
    CategoryTitle(SettingsCategory.APPEARANCE)

    SectionHeader(I18n.get("appearance"))
    Spacer(modifier = Modifier.height(12.dp))
    ThemeSelectorRow(
        selected = s.themeMode,
        onSelect = { onChange(s.copy(themeMode = it)) }
    )

    Spacer(modifier = Modifier.height(24.dp))

    // Accent color selector
    SectionHeader(I18n.get("accent_color"))
    Spacer(modifier = Modifier.height(12.dp))
    AccentSelectorRow(
        selected = s.accentColor,
        onSelect = { onChange(s.copy(accentColor = it)) }
    )
}

@Composable
private fun AccentSelectorRow(
    selected: AccentColor,
    onSelect: (AccentColor) -> Unit
) {
    val options = listOf(
        AccentColor.WINDPLAYER to I18n.get("accent_windplayer"),
        AccentColor.AUTO to I18n.get("accent_system")
    )
    // Detect the Windows accent color for the swatch preview (guarded so any
    // JNA / registry error doesn't crash the Compose render loop).
    val systemAccent = remember {
        try {
            val argb = readWindowsAccentColor()
            if (argb != null) Color(argb) else Color(0xFF6750A4) // M3 purple fallback
        } catch (_: Exception) {
            Color(0xFF6750A4)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (mode, label) ->
            val isActive = selected == mode
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(mode) },
                shape = WindRadius.Button,
                color = if (isActive) WindColors.Ink else WindColors.White,
                border = androidx.compose.foundation.BorderStroke(
                    1.5.dp,
                    if (isActive) WindColors.Ink else WindColors.Hairline
                )
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Color swatch
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(
                                // ARCH-6: use WindColors.SignalOrange so the
                                // swatch follows applyAccent overrides.
                                if (mode == AccentColor.WINDPLAYER) WindColors.SignalOrange
                                else systemAccent
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = label,
                        color = if (isActive) WindColors.CanvasCream else WindColors.Ink,
                        fontSize = 13.sp,
                        fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------
// Category: Language
// ------------------------------------------------------------------

@Composable
private fun LanguageSettings(s: PlayerSettings, onChange: (PlayerSettings) -> Unit) {
    CategoryTitle(SettingsCategory.LANGUAGE)

    var showMenu by remember { mutableStateOf(false) }
    Box {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showMenu = true },
            color = WindColors.White,
            shape = WindRadius.Pill
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = I18n.get("language"), color = WindColors.Ink, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = I18n.languages.find { it.first == s.language }?.second ?: "English",
                        color = WindColors.Slate,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        painter = iconPainter(PhosphorIcons.CARET_DOWN),
                        contentDescription = null,
                        tint = WindColors.Slate,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            I18n.languages.forEach { (code, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        I18n.current = code
                        onChange(s.copy(language = code))
                        showMenu = false
                    }
                )
            }
        }
    }
}

// ------------------------------------------------------------------
// Category: Advanced
// ------------------------------------------------------------------

@Composable
private fun AdvancedSettings(s: PlayerSettings, onChange: (PlayerSettings) -> Unit) {
    CategoryTitle(SettingsCategory.ADVANCED)

    Spacer(modifier = Modifier.height(16.dp))
    OutlinedButton(
        onClick = {
            val reset = PlayerSettings.DEFAULT.copy(
                language = s.language,
                themeMode = s.themeMode
            )
            I18n.current = reset.language
            onChange(reset)
        },
        modifier = Modifier.fillMaxWidth(),
        shape = WindRadius.Button,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = WindColors.White,
            contentColor = WindColors.Ink
        ),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, WindColors.Ink),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(text = I18n.get("reset_defaults"), fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// ------------------------------------------------------------------
// Category: About
// ------------------------------------------------------------------

@Composable
private fun AboutSection() {
    var showLicenses by remember { mutableStateOf(false) }

    CategoryTitle(SettingsCategory.ABOUT)

    Spacer(modifier = Modifier.height(12.dp))

    // App name + version
    Text(
        text = "WindPlayer ${I18n.get("about_version")}",
        color = WindColors.Ink,
        fontSize = 28.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.56).sp
    )

    Spacer(modifier = Modifier.height(6.dp))

    // Credit line
    Text(
        text = I18n.get("about_made_by"),
        color = WindColors.Slate,
        fontSize = 14.sp
    )

    Spacer(modifier = Modifier.height(24.dp))

    // Phosphor Icons credit
    Text(
        text = I18n.get("about_icons"),
        color = WindColors.Slate,
        fontSize = 13.sp
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Open Source Licenses button
    OutlinedButton(
        onClick = { showLicenses = true },
        shape = WindRadius.Button,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = WindColors.White,
            contentColor = WindColors.Ink
        ),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, WindColors.Ink),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Icon(
            painter = iconPainter(PhosphorIcons.LIST),
            contentDescription = null,
            tint = WindColors.Ink,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(I18n.get("about_licenses"), fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }

    Spacer(modifier = Modifier.height(24.dp))

    // GitHub link
    Text(
        text = I18n.get("about_github"),
        color = WindColors.Slate,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.48.sp
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "github.com/ECeternalcat/WindPlayer",
        // ARCH-6: use WindColors.LinkBlue so the link follows theme/accent.
        color = WindColors.LinkBlue,
        fontSize = 14.sp,
        modifier = Modifier.clickable {
            try {
                java.awt.Desktop.getDesktop()
                    .browse(java.net.URI("https://github.com/ECeternalcat/WindPlayer"))
            } catch (_: Exception) {}
        }
    )

    Spacer(modifier = Modifier.height(24.dp))

    // License
    Text(
        text = I18n.get("about_license"),
        color = WindColors.DustTaupe,
        fontSize = 13.sp
    )

    // Licenses dialog
    if (showLicenses) {
        LicensesDialog(onDismiss = { showLicenses = false })
    }
}

@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                I18n.get("about_licenses"),
                color = WindColors.Ink,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                THIRD_PARTY_LIBRARIES.forEach { lib ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                try {
                                    java.awt.Desktop.getDesktop().browse(java.net.URI(lib.url))
                                } catch (_: Exception) {}
                            },
                        color = WindColors.LiftedCream,
                        shape = WindRadius.Consent,
                        border = androidx.compose.foundation.BorderStroke(1.dp, WindColors.Hairline)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = lib.name,
                                    color = WindColors.Ink,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Surface(
                                    shape = WindRadius.Chip,
                                    color = if (lib.license.contains("MIT")) WindColors.LightSignalOrange.copy(alpha = 0.15f)
                                            else WindColors.Ink.copy(alpha = 0.08f)
                                ) {
                                    Text(
                                        text = lib.license,
                                        color = WindColors.Ink,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${lib.version} · ${lib.url.removePrefix("https://")}",
                                color = WindColors.Slate,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(I18n.get("close"), color = WindColors.Ink, fontWeight = FontWeight.Bold)
            }
        },
        shape = WindRadius.Stadium,
        containerColor = WindColors.White
    )
}

// ------------------------------------------------------------------
// Reusable components
// ------------------------------------------------------------------

@Composable
private fun CategoryTitle(category: SettingsCategory) {
    Text(
        text = I18n.get(category.titleKey),
        color = WindColors.Ink,
        fontSize = 24.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.48).sp
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(WindColors.SignalOrange)
        )
        Spacer(modifier = Modifier.width(8.dp))
        HorizontalDivider(
            modifier = Modifier.fillMaxWidth(),
            thickness = 1.dp,
            color = WindColors.Hairline
        )
    }
    Spacer(modifier = Modifier.height(20.dp))
}

@Composable
private fun SectionHeader(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(WindColors.SignalOrange)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text.uppercase(),
            color = WindColors.Slate,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.48.sp
        )
    }
}

@Composable
private fun SettingSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    displayValue: String
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, color = WindColors.Ink, fontSize = 14.sp)
            Text(
                text = displayValue,
                color = WindColors.Ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = WindColors.Ink,
                activeTrackColor = WindColors.Ink,
                inactiveTrackColor = WindColors.Hairline
            )
        )
    }
}

@Composable
private fun SettingToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = WindColors.Ink, fontSize = 14.sp)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = WindColors.CanvasCream,
                checkedTrackColor = WindColors.Ink,
                checkedBorderColor = WindColors.Ink,
                uncheckedThumbColor = WindColors.White,
                uncheckedTrackColor = WindColors.DustTaupe,
                uncheckedBorderColor = WindColors.DustTaupe
            )
        )
    }
}

@Composable
private fun SettingDropdownRow(
    label: String,
    options: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val displayLabel = options.find { it.first == selectedKey }?.second ?: selectedKey

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = WindColors.Ink, fontSize = 14.sp)
        Box {
            Surface(
                modifier = Modifier.clickable { expanded = true },
                color = WindColors.White,
                shape = WindRadius.Pill
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = displayLabel,
                        color = WindColors.Ink,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        painter = iconPainter(PhosphorIcons.CARET_DOWN),
                        contentDescription = null,
                        tint = WindColors.Slate,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (key, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = { onSelect(key); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingColorRow(
    label: String,
    selected: String,
    onSelect: (String) -> Unit
) {
    val presets = listOf(
        "#FFFFFF" to Color.White,
        "#FFFF00" to Color(0xFFFFFF00),
        "#00FFFF" to Color(0xFF00FFFF),
        "#FF5500" to Color(0xFFFF5500),
        "#000000" to Color.Black,
        "#00000000" to Color.Transparent
    )
    var showCustom by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = WindColors.Ink, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            presets.forEach { (hex, color) ->
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(color)
                        .clickable { onSelect(hex) }
                        .then(
                            if (selected.equals(hex, ignoreCase = true))
                                Modifier.background(WindColors.Ink.copy(alpha = 0.2f))
                            else Modifier
                        )
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            // Custom hex input toggle
            Text(
                text = selected.uppercase().take(7),
                color = WindColors.Slate,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun SettingTextFieldRow(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text = label, color = WindColors.Ink, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            placeholder = { Text(placeholder, color = WindColors.DustTaupe, fontSize = 13.sp) },
            modifier = Modifier.fillMaxWidth(),
            shape = WindRadius.Pill,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = WindColors.Ink,
                fontSize = 13.sp
            ),
            colors = TextFieldDefaults.colors(
                cursorColor = WindColors.Ink,
                focusedContainerColor = WindColors.White,
                unfocusedContainerColor = WindColors.White,
                focusedIndicatorColor = WindColors.Ink,
                unfocusedIndicatorColor = WindColors.Hairline
            )
        )
    }
}

@Composable
private fun ThemeSelectorRow(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit
) {
    val options = listOf(
        ThemeMode.LIGHT to I18n.get("theme_light"),
        ThemeMode.DARK to I18n.get("theme_dark"),
        ThemeMode.SYSTEM to I18n.get("theme_system")
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (mode, label) ->
            val isActive = selected == mode
            // WindMotion: animate pill selection state.
            val pillBg by animateColorAsState(
                targetValue = if (isActive) WindColors.Ink else WindColors.White,
                animationSpec = tween(WindMotion.DurFast, easing = WindMotion.EasingStandard),
                label = "themePillBg"
            )
            val pillBorder by animateColorAsState(
                targetValue = if (isActive) WindColors.Ink else WindColors.Hairline,
                animationSpec = tween(WindMotion.DurFast, easing = WindMotion.EasingStandard),
                label = "themePillBorder"
            )
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(mode) },
                shape = WindRadius.Button,
                color = pillBg,
                border = androidx.compose.foundation.BorderStroke(1.5.dp, pillBorder)
            ) {
                Text(
                    text = label,
                    color = if (isActive) WindColors.CanvasCream else WindColors.Ink,
                    fontSize = 13.sp,
                    fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 10.dp)
                )
            }
        }
    }
}

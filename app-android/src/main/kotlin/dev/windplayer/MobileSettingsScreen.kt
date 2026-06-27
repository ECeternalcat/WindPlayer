package dev.windplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.windplayer.ui.I18n
import dev.windplayer.ui.PlayerSettings
import dev.windplayer.ui.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileSettingsScreen(
    settings: PlayerSettings,
    onSettingsChanged: (PlayerSettings) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        I18n.get("settings"),
                        color = WindColors.Ink,
                        fontWeight = FontWeight.Medium,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        PhosphorIcon(Phosphor.ARROW_LEFT, "Back", tint = WindColors.Ink, size = 22.dp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WindColors.LiftedCream)
            )
        },
        containerColor = WindColors.CanvasCream
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SectionLabel(I18n.get("appearance"))
            Spacer(Modifier.height(10.dp))
            ThemeSelectorRow(settings.themeMode) {
                onSettingsChanged(settings.copy(themeMode = it))
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel(I18n.get("subtitle_settings"))
            Spacer(Modifier.height(10.dp))
            SliderRow(I18n.get("font_size"), settings.subFontSize.toFloat(), 15f..100f) {
                onSettingsChanged(settings.copy(subFontSize = it.toInt()))
            }
            SliderRow(I18n.get("border_size"), settings.subBorderSize.toFloat(), 0f..10f) {
                onSettingsChanged(settings.copy(subBorderSize = it.toInt()))
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel(I18n.get("playback"))
            Spacer(Modifier.height(10.dp))
            SliderRow(I18n.get("default_volume"), settings.defaultVolume.toFloat(), 0f..100f) {
                onSettingsChanged(settings.copy(defaultVolume = it.toInt()))
            }
            ToggleRow(I18n.get("hw_decode"), settings.hwdecAuto) {
                onSettingsChanged(settings.copy(hwdecAuto = it))
            }
            ToggleRow(I18n.get("auto_play_next"), settings.autoPlayNext) {
                onSettingsChanged(settings.copy(autoPlayNext = it))
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel(I18n.get("language"))
            Spacer(Modifier.height(10.dp))
            I18n.languages.forEach { (code, name) ->
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable {
                        dev.windplayer.ui.I18n.current = code
                        onSettingsChanged(settings.copy(language = code))
                    },
                    color = if (settings.language == code) WindColors.Ink else WindColors.White,
                    shape = WindRadius.Pill
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = name,
                            color = if (settings.language == code) WindColors.CanvasCream else WindColors.Ink,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        if (settings.language == code) {
                            PhosphorIcon(Phosphor.CHECK, null, tint = WindColors.CanvasCream, size = 18.dp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = {
                    I18n.current = "en"
                    onSettingsChanged(PlayerSettings.DEFAULT)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = WindRadius.Button,
                border = androidx.compose.foundation.BorderStroke(1.5.dp, WindColors.Ink),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = WindColors.White,
                    contentColor = WindColors.Ink
                ),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(I18n.get("reset_defaults"), fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(WindColors.SignalOrange)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text.uppercase(),
            color = WindColors.Slate,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.48.sp
        )
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = WindColors.Ink, fontSize = 14.sp)
            Text("${value.toInt()}", color = WindColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = WindColors.Ink,
                activeTrackColor = WindColors.Ink,
                inactiveTrackColor = WindColors.Hairline
            )
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = WindColors.Ink, fontSize = 14.sp)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
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
                border = androidx.compose.foundation.BorderStroke(
                    1.5.dp, if (isActive) WindColors.Ink else WindColors.Hairline
                )
            ) {
                Text(
                    text = label,
                    color = if (isActive) WindColors.CanvasCream else WindColors.Ink,
                    fontSize = 13.sp,
                    fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(vertical = 10.dp)
                )
            }
        }
    }
}

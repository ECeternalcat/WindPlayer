package dev.windplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(
    settings: PlayerSettings,
    onSettingsChanged: (PlayerSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(WindColors.CanvasCream)
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    painter = iconPainter(PhosphorIcons.ARROW_LEFT),
                    contentDescription = "Back",
                    tint = WindColors.Ink,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = I18n.get("settings"),
                color = WindColors.Ink,
                fontSize = 28.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.56).sp
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        SectionHeader(I18n.get("appearance"))
        Spacer(modifier = Modifier.height(12.dp))
        ThemeSelectorRow(
            selected = settings.themeMode,
            onSelect = { onSettingsChanged(settings.copy(themeMode = it)) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionHeader(I18n.get("subtitle_settings"))
        Spacer(modifier = Modifier.height(12.dp))

        SettingSliderRow(
            label = I18n.get("font_size"),
            value = settings.subFontSize.toFloat(),
            range = 15f..100f,
            onValueChange = { onSettingsChanged(settings.copy(subFontSize = it.toInt())) },
            displayValue = "${settings.subFontSize}"
        )
        SettingSliderRow(
            label = I18n.get("border_size"),
            value = settings.subBorderSize.toFloat(),
            range = 0f..10f,
            onValueChange = { onSettingsChanged(settings.copy(subBorderSize = it.toInt())) },
            displayValue = "${settings.subBorderSize}"
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionHeader(I18n.get("playback"))
        Spacer(modifier = Modifier.height(12.dp))

        SettingSliderRow(
            label = I18n.get("default_volume"),
            value = settings.defaultVolume.toFloat(),
            range = 0f..100f,
            onValueChange = { onSettingsChanged(settings.copy(defaultVolume = it.toInt())) },
            displayValue = "${settings.defaultVolume}%"
        )

        SettingToggleRow(
            label = I18n.get("hw_decode"),
            checked = settings.hwdecAuto,
            onCheckedChange = { onSettingsChanged(settings.copy(hwdecAuto = it)) }
        )
        SettingToggleRow(
            label = I18n.get("auto_play_next"),
            checked = settings.autoPlayNext,
            onCheckedChange = { onSettingsChanged(settings.copy(autoPlayNext = it)) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionHeader(I18n.get("language"))
        Spacer(modifier = Modifier.height(12.dp))

        var showLangMenu by remember { mutableStateOf(false) }
        Box {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showLangMenu = true },
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
                            text = I18n.languages.find { it.first == settings.language }?.second ?: "English",
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
            DropdownMenu(
                expanded = showLangMenu,
                onDismissRequest = { showLangMenu = false }
            ) {
                I18n.languages.forEach { (code, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            I18n.current = code
                            onSettingsChanged(settings.copy(language = code))
                            showLangMenu = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedButton(
            onClick = {
                I18n.current = "en"
                onSettingsChanged(PlayerSettings.DEFAULT)
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

        Spacer(modifier = Modifier.height(48.dp))
    }
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
    HorizontalDivider(
        modifier = Modifier.padding(top = 8.dp),
        thickness = 1.dp,
        color = WindColors.Hairline
    )
}

@Composable
private fun SettingSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    displayValue: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
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

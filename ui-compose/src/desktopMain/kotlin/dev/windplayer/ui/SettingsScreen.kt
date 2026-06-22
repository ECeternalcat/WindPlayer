package dev.windplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
            .background(Color(0xFF0F0F1A))
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    painter = iconPainter(PhosphorIcons.ARROW_LEFT),
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                painter = iconPainter(PhosphorIcons.GEAR),
                contentDescription = null,
                tint = Color(0xFF0F84E4),
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = I18n.get("settings"),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .clickable { showLangMenu = true },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = I18n.get("language"), color = Color.White, fontSize = 14.sp)
                Text(
                    text = I18n.languages.find { it.first == settings.language }?.second ?: "English",
                    color = Color(0xFF0F84E4),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
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

        Button(
            onClick = {
                I18n.current = "en"
                onSettingsChanged(PlayerSettings.DEFAULT)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2A2A3E),
                contentColor = Color(0xFFAAAAAA)
            )
        ) {
            Text(text = I18n.get("reset_defaults"), fontSize = 13.sp)
        }

        Spacer(modifier = Modifier.height(48.dp))
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = Color(0xFF0F84E4),
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold
    )
    HorizontalDivider(
        modifier = Modifier.padding(top = 6.dp),
        color = Color(0xFF333366)
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
            Text(text = label, color = Color.White, fontSize = 14.sp)
            Text(
                text = displayValue,
                color = Color(0xFF0F84E4),
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
                thumbColor = Color(0xFF0F84E4),
                activeTrackColor = Color(0xFF0F84E4)
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
        Text(text = label, color = Color.White, fontSize = 14.sp)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF0F84E4),
                checkedTrackColor = Color(0xFF0F84E4).copy(alpha = 0.3f),
                uncheckedThumbColor = Color(0xFF888888),
                uncheckedTrackColor = Color(0xFF2A2A3E)
            )
        )
    }
}

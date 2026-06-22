package dev.windplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.windplayer.ui.I18n
import dev.windplayer.ui.PlayerSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileSettingsScreen(
    settings: PlayerSettings,
    onSettingsChanged: (PlayerSettings) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(I18n.get("settings"), color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A1A2E)
                )
            )
        },
        containerColor = Color(0xFF0F0F1A)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SectionText(I18n.get("subtitle_settings"))
            Spacer(Modifier.height(8.dp))
            SliderRow(I18n.get("font_size"), settings.subFontSize.toFloat(), 15f..100f) {
                onSettingsChanged(settings.copy(subFontSize = it.toInt()))
            }
            SliderRow(I18n.get("border_size"), settings.subBorderSize.toFloat(), 0f..10f) {
                onSettingsChanged(settings.copy(subBorderSize = it.toInt()))
            }

            Spacer(Modifier.height(24.dp))
            SectionText(I18n.get("playback"))
            Spacer(Modifier.height(8.dp))
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
            SectionText(I18n.get("language"))
            Spacer(Modifier.height(8.dp))
            I18n.languages.forEach { (code, name) ->
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable {
                        dev.windplayer.ui.I18n.current = code
                        onSettingsChanged(settings.copy(language = code))
                    },
                    color = if (settings.language == code) Color(0xFF1A2A4E) else Color.Transparent
                ) {
                    Text(
                        text = name,
                        color = if (settings.language == code) Color(0xFF0F84E4) else Color(0xFFCCCCCC),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp)
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
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
                Text(I18n.get("reset_defaults"))
            }
        }
    }
}

@Composable
private fun SectionText(text: String) {
    Text(text, color = Color(0xFF0F84E4), fontSize = 14.sp, fontWeight = FontWeight.Bold)
    HorizontalDivider(Modifier.padding(top = 4.dp), color = Color(0xFF333366))
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White, fontSize = 14.sp)
            Text("${value.toInt()}", color = Color(0xFF0F84E4), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, colors = SliderDefaults.colors(
            thumbColor = Color(0xFF0F84E4), activeTrackColor = Color(0xFF0F84E4)
        ))
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(
            checkedThumbColor = Color(0xFF0F84E4),
            checkedTrackColor = Color(0xFF0F84E4).copy(alpha = 0.3f)
        ))
    }
}

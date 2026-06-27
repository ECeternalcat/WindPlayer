package dev.windplayer

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.windplayer.R

/**
 * Phosphor icon glyphs sourced from `icons/Fonts/regular/Phosphor.ttf`.
 *
 * Android doesn't ship a vector for every Phosphor name, so we render the
 * typeface directly: each entry below is the Private-Use-Area codepoint that
 * the font maps to the named icon (extracted from `style.css`).
 *
 * The `PhosphorIcon` composable is the mobile counterpart of the desktop
 * `iconPainter()` — same visual set, rendered via FontFamily.
 */
object Phosphor {
    const val ARROW_LEFT = '\ue058'
    const val ARROW_RIGHT = '\ue06c'
    const val PLAY = '\ue3d0'
    const val PAUSE = '\ue39e'
    const val LIST = '\ue2f0'
    const val SPEAKER_HIGH = '\ue44a'
    const val SPEAKER_SLASH = '\ue45a'
    const val CORNERS_OUT = '\ue1d0'
    const val CORNERS_IN = '\ue1ce'
    const val LIGHTNING = '\ue2de'
    const val GAUGE = '\ue628'
    const val FOLDER = '\ue24a'
    const val FOLDER_OPEN = '\ue256'
    const val FOLDER_SIMPLE = '\ue25a'
    const val VIDEO = '\ue740'
    const val SUBTITLES = '\ue1a8'
    const val CLOSED_CAPTIONING = '\ue1a4'
    const val FILE = '\ue230'
    const val PLUS = '\ue3d4'
    const val MONITOR = '\ue32e'
    const val DESKTOP = '\ue560'
    const val X = '\ue4f6'
    const val CHECK = '\ue182'
    const val SEAL_CHECK = '\ue606'
    const val GEAR = '\ue270'
    const val CLOCK = '\ue19a'
    const val QUEUE = '\ue6ac'
    const val STAR = '\ue46a'
    const val MAGNIFYING_GLASS = '\ue30c'
    const val DOTS_THREE = '\ue1fe'
    const val DOTS_THREE_VERTICAL = '\ue208'
    const val CARET_DOWN = '\ue136'
    const val CARET_UP = '\ue13c'
    const val CARET_RIGHT = '\ue13a'
    const val CARET_DOUBLE_RIGHT = '\ue12a'
    const val WARNING = '\ue4e0'
    const val FAST_FORWARD = '\ue6a6'
    const val REWIND = '\ue6a8'
    const val SKIP_BACK = '\ue5a4'
    const val SKIP_FORWARD = '\ue5a6'
    const val SKIP_BACK_CIRCLE = '\ue42e'
    const val SKIP_FORWARD_CIRCLE = '\ue430'
    const val ARROWS_OUT_SIMPLE = '\ue0a6'
    const val TRASH = '\ue4a6'
    const val TRASH_SIMPLE = '\ue4a8'
    const val PENCIL_SIMPLE = '\ue3b4'
    const val STACK = '\ue466'
    const val FUNNEL = '\ue266'
    const val SLIDERS = '\ue432'
    const val SHARE_NETWORK = '\ue408'
    const val DOWNLOAD_SIMPLE = '\ue20c'
    const val SCISSORS = '\ueae0'
    const val MICROPHONE = '\ue326'
    const val CAMERA = '\ue10e'
    const val IMAGE = '\ue2ca'
    const val EYE = '\ue220'
    const val RECORD = '\ue3ee'
    const val GRID_FOUR = '\ue296'
    const val LIST_BULLETS = '\ue2f2'
    const val HOUSE = '\ue2c2'
    const val HARD_DRIVES = '\ue2a0'
    const val LOCK_KEY = '\ue2fe'
    const val CLOUD = '\ue1aa'
    const val GLOBE = '\ue288'
    const val USER_CIRCLE = '\ue4c4'
    const val SIGN_OUT = '\ue42a'
    const val SPEED = '\ue628'
    const val PLUG = '\ue3d6'
    const val PLUGS_CONNECTED = '\ue3da'
}

private val PhosphorFamily = FontFamily(Font(R.font.phosphor))

/**
 * Render a Phosphor glyph at the given [size] (dp ≈ sp). Mirrors the call
 * shape of `androidx.compose.material3.Icon` so screens read the same as the
 * desktop `Icon(painter = iconPainter(...))` form.
 */
@Composable
fun PhosphorIcon(
    glyph: Char,
    contentDescription: String? = null,
    tint: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp
) {
    Text(
        text = glyph.toString(),
        color = tint,
        fontFamily = PhosphorFamily,
        fontSize = size.value.sp,
        lineHeight = size.value.sp,
        modifier = modifier
    )
}

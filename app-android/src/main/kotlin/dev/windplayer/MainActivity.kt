package dev.windplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.windplayer.vfs.KnownHostsManager
import dev.windplayer.vfs.initializeSshj
import java.io.File

class MainActivity : ComponentActivity() {

    /** Updated whenever a new VIEW/SEND intent arrives while the app is running. */
    private val pendingVideoUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initializeSshj()
        KnownHostsManager.initialize(File(filesDir, ".windplayer"))

        // H18: only auto-handle the intent on a FRESH launch. After the system
        // kills and restarts the process, getIntent() still returns the
        // original ACTION_VIEW — replaying it would auto-play a video the
        // user thought they'd closed. savedInstanceState != null means
        // this is a restoration, not a fresh start.
        if (savedInstanceState == null) {
            handleIntent(intent)
        }
        // Consume the intent so a config-change recreation doesn't re-handle it.
        @Suppress("DEPRECATION")
        intent.removeExtra(Intent.EXTRA_STREAM)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Apply the palette before the first Compose frame so dark mode doesn't
        // flash light on startup. MobileApp keeps it in sync at runtime.
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindColors.applyDark(night)
        setContent {
            // Theming (light/dark + status-bar appearance) is owned by MobileApp
            // so it can react to PlayerSettings.themeMode and system config.
            MobileApp(
                externalVideoUri = pendingVideoUri.value,
                onExternalVideoConsumed = { pendingVideoUri.value = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null || intent.action == Intent.ACTION_MAIN) return
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            // M27: getParcelableExtra is deprecated on API 33+. IntentCompat
            // provides the type-safe replacement.
            Intent.ACTION_SEND -> androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java)
            else -> null
        }
        if (uri != null) pendingVideoUri.value = uri
    }
}

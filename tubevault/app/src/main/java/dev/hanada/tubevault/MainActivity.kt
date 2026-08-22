package dev.hanada.tubevault

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.hanada.tubevault.ui.AppRoot
import dev.hanada.tubevault.ui.BrandSplash
import dev.hanada.tubevault.ui.theme.TubeVaultTheme

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate() — it swaps the launch theme
        // (icon-only system splash) out for the activity's real theme once
        // this activity's first frame is ready to draw.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // The app is near-black whatever the system theme is, so the bar
        // icons have to be forced light. The default follows the system, which
        // would paint dark icons onto a black background in light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        askForNotificationPermission()
        consumeSharedLink(intent)

        val container = (application as TubeVaultApp).container
        container.playback.connect()

        setContent {
            TubeVaultTheme {
                var showSplash by remember { mutableStateOf(true) }
                if (showSplash) {
                    BrandSplash(onFinished = { showSplash = false })
                } else {
                    AppRoot(container)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeSharedLink(intent)
    }

    /** "Share → TubeVault" from the YouTube app lands here. */
    private fun consumeSharedLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val url = text.split(Regex("\\s+")).firstOrNull { it.startsWith("http") } ?: return
        (application as TubeVaultApp).container.sharedLink.value = url
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

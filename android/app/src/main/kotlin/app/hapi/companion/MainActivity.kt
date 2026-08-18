package app.hapi.companion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.hapi.companion.di.AppGraph
import app.hapi.companion.di.LocalAppGraph
import app.hapi.companion.feature.settings.ThemeMode
import app.hapi.companion.feature.settings.ThemeSettings
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.protocol.pairing.BindLink

/**
 * Single-activity entry point (`launchMode="singleTask"`). Hosts the
 * Navigation Compose graph under the persisted theme choice
 * ([AppGraph.themePrefs], B-M4e) and feeds `hapicompanion://bind?hub=…&code=…`
 * deep links — cold start and [onNewIntent] — into
 * [AppGraph.pendingBindLink]; all parsing stays in [BindLink].
 */
class MainActivity : ComponentActivity() {

    private val appGraph: AppGraph get() = (application as HapiApp).appGraph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            // Only a fresh launch consumes the launching intent — after a
            // config change / process restore the same (already-consumed)
            // intent is redelivered and must not resurrect the confirm card.
            handleBindIntent(intent)
        }
        setContent {
            // Follow-system default renders for the first frames while the
            // DataStore read completes; the persisted choice then applies.
            val theme by appGraph.themePrefs.settings.collectAsState(initial = ThemeSettings())
            HapiTheme(
                darkTheme = when (theme.mode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK, ThemeMode.OLED -> true
                },
                dynamicColor = theme.dynamicColor,
                oled = theme.mode == ThemeMode.OLED,
            ) {
                CompositionLocalProvider(LocalAppGraph provides appGraph) {
                    HapiNavigation()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleBindIntent(intent)
    }

    private fun handleBindIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        val link = BindLink.parse(data.toString())
        if (link != null) {
            appGraph.pendingBindLink.value = link
        } else if (BindLink.SCHEME.equals(data.scheme, ignoreCase = true)) {
            // Ours but malformed (truncated QR, mangled copy/paste).
            appGraph.pairingNotice.value = getString(R.string.pairing_invalid_link)
        }
    }
}

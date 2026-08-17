package app.hapi.companion.feature.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import app.hapi.companion.ui.markdown.MarkdownLinkHandler
import app.hapi.protocol.markdown.HrefDecision

/**
 * The confirm-aware URL opener the M2d1 markdown module defers to this
 * milestone: [HrefDecision.Allowed] dispatches immediately,
 * [HrefDecision.ConfirmFirst] asks first (custom schemes), blocked never gets
 * here. Workspace-file citations stay inert until the session file viewer
 * lands (M4) — a toast tells the user why.
 */
@Composable
fun rememberChatLinkHandler(): MarkdownLinkHandler {
    val context = LocalContext.current
    var confirmUrl by remember { mutableStateOf<String?>(null) }

    confirmUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { confirmUrl = null },
            title = { Text("Open link?") },
            text = { Text(url) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUrl = null
                        context.openUrl(url)
                    },
                ) { Text("Open") }
            },
            dismissButton = {
                TextButton(onClick = { confirmUrl = null }) { Text("Cancel") }
            },
        )
    }

    return remember(context) {
        object : MarkdownLinkHandler {
            override fun onFilePath(path: String, line: Int?) {
                Toast.makeText(context, "File viewer arrives in a later milestone", Toast.LENGTH_SHORT).show()
            }

            override fun onUrl(url: String, decision: HrefDecision) {
                when (decision) {
                    is HrefDecision.Allowed -> context.openUrl(url)
                    is HrefDecision.ConfirmFirst -> confirmUrl = url
                    is HrefDecision.Blocked -> Unit
                }
            }
        }
    }
}

private fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
    }
}

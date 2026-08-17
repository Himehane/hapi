package app.hapi.companion.feature.chat.composer

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hapi.companion.feature.chat.ComposerUiState
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.companion.ui.theme.hapi

/**
 * A pending (M4) attachment chip — the row renders only when non-empty, so
 * the M4 upload flow can light it up without composer surgery.
 */
data class ComposerAttachment(
    val filename: String,
    val mimeType: String,
)

/**
 * The chat input bar (B-M3a): multiline text field (Enter = newline, mobile
 * default), a send button whose long-press offers "Send & steer" while a
 * turn is active, and an abort button during thinking. Attachments are an M4
 * seam ([attachments] chips render when present; no picker yet).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatComposer(
    state: ComposerUiState,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onSendSteer: () -> Unit,
    onAbort: () -> Unit,
    modifier: Modifier = Modifier,
    attachments: List<ComposerAttachment> = emptyList(),
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            if (attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    attachments.forEach { attachment ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                text = "📎 ${attachment.filename}",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = state.text,
                    onValueChange = onTextChange,
                    placeholder = { Text("Message the agent…") },
                    minLines = 1,
                    maxLines = 6,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.weight(1f),
                )
                if (state.canSteer) {
                    AbortButton(onAbort)
                }
                SendButton(state = state, onSend = onSend, onSendSteer = onSendSteer)
            }
        }
    }
}

@Composable
private fun AbortButton(onAbort: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = CircleShape,
        onClick = onAbort,
        modifier = Modifier
            .padding(start = 6.dp, bottom = 4.dp)
            .size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Stop square.
            Text(text = "■", fontSize = 16.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SendButton(
    state: ComposerUiState,
    onSend: () -> Unit,
    onSendSteer: () -> Unit,
) {
    var steerMenuOpen by remember { mutableStateOf(false) }
    val hasText = state.text.isNotBlank()
    val enabled = hasText && !state.isSending

    Box(
        modifier = Modifier
            .padding(start = 6.dp, bottom = 4.dp)
            .size(44.dp)
            .clip(CircleShape),
    ) {
        Surface(
            color = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            contentColor = if (enabled) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.hapi.hint
            },
            shape = CircleShape,
            modifier = Modifier
                .size(44.dp)
                .combinedClickable(
                    enabled = enabled,
                    onClick = onSend,
                    // Steer intent is deliberate: only offered while a turn is
                    // active (`messageDelivery.ts` — queue is always the default).
                    onLongClick = { if (state.canSteer) steerMenuOpen = true },
                ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (state.isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.hapi.hint,
                    )
                } else {
                    Text(text = "➤", fontSize = 18.sp)
                }
            }
        }
        DropdownMenu(expanded = steerMenuOpen, onDismissRequest = { steerMenuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Send & steer into current turn") },
                onClick = {
                    steerMenuOpen = false
                    onSendSteer()
                },
            )
        }
    }
}

// -------------------------------------------------------------- previews --

@Preview(showBackground = true)
@Composable
private fun ChatComposerPreview() {
    HapiTheme {
        Surface {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChatComposer(
                    state = ComposerUiState(text = "", isSending = false, canSteer = false),
                    onTextChange = {}, onSend = {}, onSendSteer = {}, onAbort = {},
                )
                ChatComposer(
                    state = ComposerUiState(
                        text = "Run the tests and summarize failures",
                        isSending = false,
                        canSteer = true,
                    ),
                    onTextChange = {}, onSend = {}, onSendSteer = {}, onAbort = {},
                )
                ChatComposer(
                    state = ComposerUiState(text = "Sending…", isSending = true, canSteer = false),
                    onTextChange = {}, onSend = {}, onSendSteer = {}, onAbort = {},
                    attachments = listOf(ComposerAttachment("screenshot.png", "image/png")),
                )
            }
        }
    }
}

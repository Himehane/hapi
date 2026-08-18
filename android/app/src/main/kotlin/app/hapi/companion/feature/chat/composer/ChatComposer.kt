package app.hapi.companion.feature.chat.composer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hapi.companion.feature.chat.ComposerUiState
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.companion.ui.theme.hapi
import app.hapi.protocol.wire.SlashCommand
import kotlinx.coroutines.delay

/**
 * A pending (M4) attachment chip — the row renders only when non-empty, so
 * the M4 upload flow can light it up without composer surgery.
 */
data class ComposerAttachment(
    val filename: String,
    val mimeType: String,
)

/**
 * The chat input bar (B-M3a, extended in B-M3ce): multiline text field
 * (Enter = newline, mobile default), a send button whose long-press offers
 * "Send & steer" while a turn is active, an abort button during thinking,
 * a mic button for press-to-toggle dictation (recording chip with elapsed
 * time + cancel while capturing), and a slash-command dropdown that opens
 * while the text is a lone `/token`. Attachments remain an M4 seam.
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
    slashSuggestions: List<SlashCommand> = emptyList(),
    onSlashCommandSelected: (SlashCommand) -> Unit = {},
    /** null ⇒ dictation unavailable (no controller wired) — mic button hidden. */
    dictation: DictationState? = null,
    onDictationToggle: () -> Unit = {},
    onDictationCancel: () -> Unit = {},
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            if (slashSuggestions.isNotEmpty()) {
                SlashCommandDropdown(
                    suggestions = slashSuggestions,
                    onSelect = onSlashCommandSelected,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
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
            val recording = dictation as? DictationState.Recording
            if (recording != null) {
                RecordingChip(
                    startedAtMs = recording.startedAtMs,
                    onCancel = onDictationCancel,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
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
                if (dictation != null) {
                    MicButton(state = dictation, onToggle = onDictationToggle)
                }
                if (state.canSteer) {
                    AbortButton(onAbort)
                }
                SendButton(state = state, onSend = onSend, onSendSteer = onSendSteer)
            }
        }
    }
}

// -------------------------------------------------------- slash dropdown --

/**
 * Filtered command list above the input (web `Autocomplete.tsx` twin):
 * name + description rows, tap inserts `/name `.
 */
@Composable
private fun SlashCommandDropdown(
    suggestions: List<SlashCommand>,
    onSelect: (SlashCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
            items(suggestions, key = { "${it.source}:${it.name}" }) { command ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = { onSelect(command) })
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "/${command.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    command.description?.takeIf { it.isNotBlank() }?.let { description ->
                        Text(
                            text = description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.hapi.hint,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
            }
        }
    }
}

// ------------------------------------------------------------- dictation --

/** Elapsed-time recording chip with a cancel affordance (discards the take). */
@Composable
private fun RecordingChip(
    startedAtMs: Long,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /** Injectable clock for previews. */
    now: () -> Long = System::currentTimeMillis,
) {
    var elapsedSec by remember(startedAtMs) {
        mutableLongStateOf(((now() - startedAtMs) / 1000).coerceAtLeast(0))
    }
    LaunchedEffect(startedAtMs) {
        while (true) {
            elapsedSec = ((now() - startedAtMs) / 1000).coerceAtLeast(0)
            delay(250)
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "● Recording…  ${formatElapsed(elapsedSec)}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            )
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

/** `m:ss` elapsed-time label for the recording chip. */
internal fun formatElapsed(totalSeconds: Long): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * Press-to-toggle mic: idle glyph → recording stop-square (error colors) →
 * spinner while starting/transcribing.
 */
@Composable
private fun MicButton(state: DictationState, onToggle: () -> Unit) {
    val recording = state is DictationState.Recording
    val busy = state is DictationState.Starting || state is DictationState.Transcribing
    Surface(
        color = if (recording) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (recording) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = CircleShape,
        enabled = !busy,
        onClick = onToggle,
        modifier = Modifier
            .padding(start = 6.dp, bottom = 4.dp)
            .size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            when {
                busy -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.hapi.hint,
                )
                recording -> Text(text = "■", fontSize = 16.sp)
                else -> Text(text = "🎙", fontSize = 17.sp)
            }
        }
    }
}

// ---------------------------------------------------------------- actions --

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
                    dictation = DictationState.Idle,
                )
                ChatComposer(
                    state = ComposerUiState(
                        text = "Run the tests and summarize failures",
                        isSending = false,
                        canSteer = true,
                    ),
                    onTextChange = {}, onSend = {}, onSendSteer = {}, onAbort = {},
                    dictation = DictationState.Idle,
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

@Preview(showBackground = true)
@Composable
private fun RecordingComposerPreview() {
    HapiTheme {
        Surface {
            ChatComposer(
                state = ComposerUiState(text = "", isSending = false, canSteer = false),
                onTextChange = {}, onSend = {}, onSendSteer = {}, onAbort = {},
                dictation = DictationState.Recording(startedAtMs = System.currentTimeMillis() - 42_000),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SlashDropdownComposerPreview() {
    HapiTheme {
        Surface {
            ChatComposer(
                state = ComposerUiState(text = "/co", isSending = false, canSteer = false),
                onTextChange = {}, onSend = {}, onSendSteer = {}, onAbort = {},
                dictation = DictationState.Idle,
                slashSuggestions = listOf(
                    SlashCommand(
                        name = "compact",
                        description = "Clear conversation history but keep a summary in context",
                        source = "builtin",
                    ),
                    SlashCommand(
                        name = "context",
                        description = "Visualize current context usage as a colored grid",
                        source = "builtin",
                    ),
                    SlashCommand(name = "code-review", description = null, source = "project"),
                ),
            )
        }
    }
}

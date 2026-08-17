package app.hapi.companion.feature.chat

import app.hapi.protocol.chat.ChatToolCall
import app.hapi.protocol.chat.getInputStringAny
import app.hapi.protocol.chat.isAskUserQuestionToolName
import app.hapi.protocol.chat.isRequestUserInputToolName
import app.hapi.protocol.chat.truncate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Collapsed tool-card header: icon glyph + title + optional subtitle. Port of
 * the presentation registry in `web/src/components/ToolCard/knownTools.tsx`
 * (the read-only subset — the web's `minimal` flag maps to "no inline body by
 * default" and is a per-card expansion default here). Icons are text glyphs in
 * the same family as the event-row emoji the shared protocol presentation
 * already emits.
 */
data class ToolCardPresentation(
    val icon: String,
    val title: String,
    val subtitle: String?,
)

private object ToolIcons {
    const val TERMINAL = "💻"
    const val READ = "📖"
    const val SEARCH = "🔍"
    const val EDIT = "✏️"
    const val WEB = "🌐"
    const val AGENT = "🚀"
    const val QUESTION = "❓"
    const val PLAN = "📋"
    const val IDEA = "💡"
    const val PUZZLE = "🧩"
    const val MESSAGE = "💬"
    const val TEAM = "👥"
    const val WARNING = "⚠️"
    const val WRENCH = "🔧"
}

// ---------------------------------------------------------------- helpers --

private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement?.asStringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun countLines(text: String): Int = text.split('\n').size

/** Strip the session root so paths read workspace-relative (web `resolveDisplayPath`). */
internal fun displayPath(path: String, basePath: String?): String {
    if (basePath.isNullOrEmpty()) return path
    val root = basePath.trimEnd('/')
    return when {
        path == root -> "."
        path.startsWith("$root/") -> path.removePrefix("$root/")
        else -> path
    }
}

private fun basename(path: String): String =
    path.trimEnd('/').substringAfterLast('/')

// ------------------------------------------------------- terminal parsing --

private val COMMANDS_WITH_SUBCOMMAND =
    setOf("git", "bun", "npm", "pnpm", "yarn", "docker", "systemctl", "cargo", "go")
private val COMMAND_ASSIGNMENT_RE = Regex("^[A-Za-z_][A-Za-z0-9_]*=")
private val AMBIGUOUS_SHELL_RE = Regex("[;&|<>$`(){}\n\r]")

/** `formatTerminalCommandTitle` (web): the leading executable(+subcommand) of a simple command. */
internal fun formatTerminalCommandTitle(command: String?): String? {
    if (command.isNullOrEmpty() || AMBIGUOUS_SHELL_RE.containsMatchIn(command)) return null

    val parts = command.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    var index = 0
    while (COMMAND_ASSIGNMENT_RE.containsMatchIn(parts.getOrNull(index) ?: "")) index += 1

    if (parts.getOrNull(index) == "env") {
        index += 1
        while (
            parts.getOrNull(index) == "-i" ||
            parts.getOrNull(index) == "--ignore-environment" ||
            COMMAND_ASSIGNMENT_RE.containsMatchIn(parts.getOrNull(index) ?: "")
        ) index += 1
    }
    if (parts.getOrNull(index) == "sudo") {
        index += 1
        while (parts.getOrNull(index) in setOf("-n", "--non-interactive", "-E", "--preserve-env")) index += 1
    }
    if (parts.getOrNull(index)?.startsWith("-") == true) return null

    val executable = parts.getOrNull(index)?.let(::basename) ?: return null
    if (executable.isEmpty()) return null

    val subcommand = parts.getOrNull(index + 1)?.takeUnless { it.startsWith("-") }
    if (subcommand == null || executable !in COMMANDS_WITH_SUBCOMMAND) return executable
    if (executable in setOf("bun", "npm", "pnpm", "yarn") && subcommand == "run") {
        val script = parts.getOrNull(index + 2)
        return if (script != null && !script.startsWith("-")) "$executable run $script" else "$executable run"
    }
    if (executable == "docker" && subcommand == "compose") {
        val action = parts.getOrNull(index + 2)
        return if (action != null && !action.startsWith("-")) "docker compose $action" else "docker compose"
    }
    return "$executable $subcommand"
}

/** The command string, joining Codex-style `command: string[]` arrays. */
internal fun terminalCommand(input: JsonElement?): String? {
    getInputStringAny(input, listOf("command", "cmd"))?.let { return it }
    val array = input.asObjectOrNull()?.get("command") as? JsonArray ?: return null
    val parts = array.mapNotNull { it.asStringOrNull()?.takeIf(String::isNotEmpty) }
    return if (parts.isEmpty()) null else parts.joinToString(" ")
}

private fun terminalTitle(input: JsonElement?, description: String?): String {
    val command = terminalCommand(input)
    if (description != null && description != command) return description
    return formatTerminalCommandTitle(command) ?: description ?: "Terminal"
}

private fun terminalSubtitle(input: JsonElement?, description: String?): String? {
    val command = terminalCommand(input)
    return if (command == terminalTitle(input, description)) null else command
}

// ------------------------------------------------------------- questions --

private fun questionTitle(input: JsonElement?): String {
    val questions = input.asObjectOrNull()?.get("questions") as? JsonArray ?: JsonArray(emptyList())
    if (questions.size > 1) return "${questions.size} Questions"
    val header = questions.firstOrNull().asObjectOrNull()?.get("header").asStringOrNull()?.trim().orEmpty()
    return header.ifEmpty { "Question" }
}

private fun questionSubtitle(input: JsonElement?): String? {
    val questions = input.asObjectOrNull()?.get("questions") as? JsonArray ?: JsonArray(emptyList())
    val question = questions.firstOrNull().asObjectOrNull()?.get("question").asStringOrNull()?.trim().orEmpty()
    if (questions.size > 1 && question.isNotEmpty()) return truncate(question, 100) + " (+${questions.size - 1} more)"
    return question.takeIf { it.isNotEmpty() }?.let { truncate(it, 120) }
}

// --------------------------------------------------------------- MCP names --

private fun snakeToTitle(value: String): String = value
    .split('_')
    .filter { it.isNotEmpty() }
    .joinToString(" ") { part -> part.lowercase().replaceFirstChar { it.uppercaseChar() } }

private fun mcpTitle(toolName: String): String {
    val withoutPrefix = toolName.removePrefix("mcp__")
    val parts = withoutPrefix.split("__")
    return if (parts.size >= 2) {
        "MCP: ${snakeToTitle(parts[0])} ${snakeToTitle(parts.drop(1).joinToString("_"))}"
    } else {
        "MCP: ${snakeToTitle(withoutPrefix)}"
    }
}

// ------------------------------------------------------------ entry point --

@Suppress("CyclomaticComplexMethod", "LongMethod")
fun toolCardPresentation(
    tool: ChatToolCall,
    basePath: String?,
): ToolCardPresentation {
    val input = tool.input
    val name = tool.name
    val description = tool.description

    if (name.startsWith("mcp__")) {
        return ToolCardPresentation(ToolIcons.PUZZLE, mcpTitle(name), null)
    }
    if (isAskUserQuestionToolName(name) || isRequestUserInputToolName(name)) {
        return ToolCardPresentation(ToolIcons.QUESTION, questionTitle(input), questionSubtitle(input))
    }

    fun filePathTitle(keys: List<String>, fallback: String): String =
        getInputStringAny(input, keys)?.let { displayPath(it, basePath) } ?: fallback

    when (name) {
        "Bash", "CodexBash", "shell_command", "run_shell_command" -> {
            // CodexBash single parsed read renders as the file it reads.
            if (name == "CodexBash") {
                val parsed = input.asObjectOrNull()?.get("parsed_cmd") as? JsonArray
                val first = parsed?.singleOrNull().asObjectOrNull()
                if (first?.get("type").asStringOrNull() == "read") {
                    first?.get("name").asStringOrNull()?.let { file ->
                        return ToolCardPresentation(
                            ToolIcons.READ,
                            displayPath(file, basePath),
                            terminalSubtitle(input, description),
                        )
                    }
                }
            }
            return ToolCardPresentation(
                ToolIcons.TERMINAL,
                terminalTitle(input, description),
                terminalSubtitle(input, description),
            )
        }

        "Read" -> return ToolCardPresentation(
            ToolIcons.READ, filePathTitle(listOf("file_path", "path", "file"), "Read file"), null,
        )

        "NotebookRead" -> return ToolCardPresentation(
            ToolIcons.READ, filePathTitle(listOf("notebook_path"), "Read notebook"), null,
        )

        "Edit" -> return ToolCardPresentation(
            ToolIcons.EDIT, filePathTitle(listOf("file_path", "path"), "Edit file"), null,
        )

        "MultiEdit" -> {
            val file = getInputStringAny(input, listOf("file_path", "path"))
                ?: return ToolCardPresentation(ToolIcons.EDIT, "Edit file", null)
            val count = (input.asObjectOrNull()?.get("edits") as? JsonArray)?.size ?: 0
            val path = displayPath(file, basePath)
            return ToolCardPresentation(ToolIcons.EDIT, if (count > 1) "$path ($count edits)" else path, null)
        }

        "Write" -> {
            val content = getInputStringAny(input, listOf("content", "text"))
            val subtitle = content?.let {
                val lines = countLines(it)
                if (lines > 1) "$lines lines" else "${it.length} chars"
            }
            return ToolCardPresentation(
                ToolIcons.EDIT, filePathTitle(listOf("file_path", "path"), "Write file"), subtitle,
            )
        }

        "NotebookEdit" -> return ToolCardPresentation(
            ToolIcons.EDIT,
            filePathTitle(listOf("notebook_path"), "Edit notebook"),
            getInputStringAny(input, listOf("edit_mode"))?.let { "mode: $it" },
        )

        "Glob" -> return ToolCardPresentation(
            ToolIcons.SEARCH, getInputStringAny(input, listOf("pattern")) ?: "Search files", null,
        )

        "Grep" -> {
            val pattern = getInputStringAny(input, listOf("pattern"))
            return ToolCardPresentation(
                ToolIcons.SEARCH, pattern?.let { "grep(pattern: $it)" } ?: "Search content", null,
            )
        }

        "LS" -> return ToolCardPresentation(
            ToolIcons.SEARCH, filePathTitle(listOf("path"), "List files"), null,
        )

        "WebFetch" -> {
            val url = getInputStringAny(input, listOf("url"))
                ?: return ToolCardPresentation(ToolIcons.WEB, "Web fetch", null)
            val host = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/]+)").find(url)?.groupValues?.get(1) ?: url
            return ToolCardPresentation(ToolIcons.WEB, host, url)
        }

        "WebSearch" -> {
            val query = getInputStringAny(input, listOf("query"))
            return ToolCardPresentation(ToolIcons.WEB, query ?: "Web search", query?.let { truncate(it, 80) })
        }

        "Task", "Agent" -> {
            val inputName = getInputStringAny(input, listOf("name"))
            val teamName = getInputStringAny(input, listOf("team_name"))
            val title = when {
                name == "Task" && inputName != null && teamName != null -> "Agent: $inputName"
                else -> getInputStringAny(input, listOf("description"))
                    ?: if (name == "Task") "Task" else "Launch Agent"
            }
            val subtitle = getInputStringAny(input, listOf("prompt"))?.let { truncate(it, 120) }
                ?: getInputStringAny(input, listOf("subagent_type"))
            return ToolCardPresentation(ToolIcons.AGENT, title, subtitle)
        }

        "CodexAgent", "spawn_agent", "resume_agent", "wait_agent", "close_agent", "interrupt_agent" -> {
            val title = when (name) {
                "spawn_agent" -> "Spawn agent"
                "resume_agent" -> "Resume agent"
                "wait_agent" -> "Wait for agent"
                "close_agent" -> "Close agent"
                "interrupt_agent" -> "Interrupt agent"
                else -> "Agent"
            }
            val prompt = getInputStringAny(input, listOf("prompt", "summary"))
            return ToolCardPresentation(ToolIcons.AGENT, title, prompt?.let { truncate(it, 120) })
        }

        "SendMessage", "send_input", "send_message", "followup_task" -> {
            val recipient = getInputStringAny(input, listOf("recipient"))
            val msgType = getInputStringAny(input, listOf("type"))
            val title = when {
                msgType == "broadcast" -> "Broadcast"
                msgType == "shutdown_request" -> "Shutdown: ${recipient ?: "agent"}"
                msgType == "shutdown_response" -> "Shutdown Response"
                recipient != null -> "Message: $recipient"
                else -> "Message agent"
            }
            val summary = getInputStringAny(input, listOf("summary"))
            return ToolCardPresentation(ToolIcons.MESSAGE, title, summary?.let { truncate(it, 120) })
        }

        "list_agents" -> return ToolCardPresentation(ToolIcons.TEAM, "List agents", null)

        "TeamCreate" -> {
            val teamName = getInputStringAny(input, listOf("team_name"))
            return ToolCardPresentation(
                ToolIcons.TEAM,
                teamName?.let { "Team: $it" } ?: "Create Team",
                getInputStringAny(input, listOf("description")),
            )
        }

        "TeamDelete" -> return ToolCardPresentation(ToolIcons.TEAM, "Delete Team", null)

        "TodoWrite" -> return ToolCardPresentation(ToolIcons.IDEA, "Todo list", null)

        "update_plan" -> return ToolCardPresentation(ToolIcons.PLAN, "Plan", null)

        "ExitPlanMode", "exit_plan_mode" -> return ToolCardPresentation(ToolIcons.PLAN, "Plan proposal", null)

        "Skill" -> {
            val skill = getInputStringAny(input, listOf("skill"))
            return ToolCardPresentation(ToolIcons.PUZZLE, skill?.let { "Skill: $it" } ?: "Skill", null)
        }

        "CodexReasoning" -> return ToolCardPresentation(
            ToolIcons.IDEA, getInputStringAny(input, listOf("title")) ?: "Reasoning", null,
        )

        "CodexPermission" -> {
            val permissionTool = getInputStringAny(input, listOf("tool"))
            return ToolCardPresentation(
                ToolIcons.QUESTION,
                permissionTool?.let { "Permission: $it" } ?: "Permission request",
                getInputStringAny(input, listOf("message", "command")),
            )
        }

        "CodexPatch" -> {
            val changes = input.asObjectOrNull()?.get("changes").asObjectOrNull()
            val files = changes?.keys?.toList().orEmpty()
            val subtitle = files.firstOrNull()?.let { first ->
                val display = basename(displayPath(first, basePath))
                if (files.size > 1) "$display (+${files.size - 1})" else display
            }
            return ToolCardPresentation(ToolIcons.EDIT, "Apply changes", subtitle)
        }

        "CodexDiff" -> {
            val unified = getInputStringAny(input, listOf("unified_diff"))
            val subtitle = unified?.lineSequence()
                ?.firstOrNull { it.startsWith("+++ ") }
                ?.removePrefix("+++ ")?.removePrefix("b/")
                ?.let { it.substringAfterLast('/') }
            return ToolCardPresentation(ToolIcons.EDIT, "Diff", subtitle)
        }

        "AgyTaskLog" -> {
            val task = getInputStringAny(input, listOf("task"))
            return ToolCardPresentation(
                ToolIcons.MESSAGE, task?.let { "$it log" } ?: "Inspecting task log", null,
            )
        }

        "AgyAsyncTask" -> return ToolCardPresentation(ToolIcons.PLAN, description ?: "Background task", null)

        "AgyError" -> return ToolCardPresentation(ToolIcons.WARNING, description ?: "Error", null)
    }

    // Generic fallback (web `getToolPresentation` tail): promote a semantic
    // label when an ACP agent's title is the verbatim argument.
    val filePath = getInputStringAny(input, listOf("file_path", "path", "filePath", "file"))
    val command = getInputStringAny(input, listOf("command", "cmd"))
    val pattern = getInputStringAny(input, listOf("pattern"))
    val url = getInputStringAny(input, listOf("url"))
    val query = getInputStringAny(input, listOf("query"))
    val nameInput = getInputStringAny(input, listOf("name"))
    val subtitle = filePath ?: command ?: pattern ?: url ?: query ?: nameInput

    var title = description ?: name
    if (subtitle != null && subtitle == title) {
        title = when {
            filePath != null -> "Read file"
            command != null -> "Run shell"
            pattern != null -> "Search"
            url != null -> "Open URL"
            query != null -> "Query"
            else -> title
        }
    }
    return ToolCardPresentation(
        ToolIcons.WRENCH,
        title,
        subtitle?.takeIf { it != title }?.let { truncate(it, 80) },
    )
}

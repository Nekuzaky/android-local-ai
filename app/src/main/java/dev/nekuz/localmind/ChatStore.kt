package dev.nekuz.localmind

import java.io.File
import java.util.UUID

class ChatStore(private val hub: Hub) {
    data class PendingImage(val base64: String, val label: String)

    private val dir = File(hub.app.filesDir, "chats")
    private val summaryFile = File(hub.app.filesDir, "chat_summaries.json")
    private var summaryWritten: List<ChatSummary>? = null

    var summaries: List<ChatSummary> = emptyList()
        private set
    var current: Conversation? = null
        private set
    var busy = false
        private set
    var notice: String? = null
        private set
    var pendingImage: PendingImage? = null
        private set
    var mode = ChatMode.GENERAL
        private set
    var loaded = false
        private set

    private val partial = StringBuilder()
    private var rawPieces = 0
    private var pcToken: CancelToken? = null
    private var lastSave = 0L
    private var lastPaint = 0L

    private val client: OllamaClient by lazy { OllamaClient(LanHttp(readTimeoutMs = PC_READ_TIMEOUT_MS, totalDeadlineMs = PC_TOTAL_MS)) }

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val wanted = hub.settings.conversationId
        hub.background(null) {
            dir.mkdirs()
            val ids = dir.list()?.asSequence()
                ?.filter { it.endsWith(".json") }
                ?.map { it.removeSuffix(".json") }
                ?.filter { ChatCodec.isValidId(it) }
                ?.take(MAX_CONVERSATIONS)
                ?.toSet()
                ?: emptySet()
            val stored = ChatSummaryCodec.decode(AtomicFiles.readText(summaryFile, ChatSummaryCodec.MAX_FILE_BYTES))
            val (known, missing) = ChatSummaryCodec.reconcile(stored, ids)
            val rebuilt = missing.mapNotNull { id ->
                val parsed = readConversation(id)
                if (parsed != null && parsed.id == id) ChatSummary(id, parsed.title, parsed.updatedAt) else null
            }
            val list = (known + rebuilt).sortedByDescending { it.updatedAt }
            val open = if (wanted in ids) readConversation(wanted) else null
            hub.main.post {
                summaries = (summaries + list).distinctBy { it.id }
                if (rebuilt.isNotEmpty() || known.size != stored.size) {
                    val merged = summaries
                    hub.io.execute { writeSummaries(merged) }
                }
                if (current == null && open != null) {
                    current = open
                    mode = open.mode
                }
                hub.changed()
            }
        }
    }

    private fun readConversation(id: String): Conversation? = try {
        ChatCodec.decode(AtomicFiles.readText(File(dir, "$id.json"), ChatCodec.MAX_FILE_BYTES))
    } catch (e: OutOfMemoryError) {
        null
    }

    fun setMode(next: ChatMode) {
        mode = next
        current?.let { conversation ->
            val updated = conversation.copy(mode = next)
            current = updated
            persist(updated)
        }
        hub.changed()
    }

    fun newConversation() {
        if (busy) return
        current = null
        pendingImage = null
        notice = null
        hub.updateSettings { it.copy(conversationId = "") }
    }

    fun open(id: String) {
        if (busy || !ChatCodec.isValidId(id)) return
        hub.background({ say("That conversation could not be read.") }) {
            val parsed = readConversation(id)
            hub.main.post {
                if (parsed == null) {
                    say("That conversation is damaged and could not be opened.")
                } else {
                    current = parsed
                    mode = parsed.mode
                    notice = null
                    hub.updateSettings { it.copy(conversationId = id) }
                }
            }
        }
    }

    fun delete(id: String) {
        if (busy || !ChatCodec.isValidId(id)) return
        summaries = summaries.filter { it.id != id }
        if (current?.id == id) {
            current = null
            hub.updateSettings { it.copy(conversationId = "") }
        }
        val remaining = summaries
        hub.io.execute {
            File(dir, "$id.json").delete()
            writeSummaries(remaining)
        }
        hub.changed()
    }

    fun setImage(image: PendingImage?) {
        pendingImage = image
        hub.changed()
    }

    fun say(text: String?) {
        notice = text
        hub.changed()
    }

    fun send(text: String, modelText: String? = null): Boolean {
        val typed = ForeignText.multiline(text.trim(), ChatCodec.MAX_TEXT)
        if (typed.isBlank() || busy) return false
        val settings = hub.settings
        if (settings.usePc) {
            if (Endpoints.parse(settings.ollamaAddress, PcStore.OLLAMA_PORT) == null || !OllamaNames.isValid(settings.ollamaModel)) {
                say("Set an Ollama address and choose a model on the PC tab first.")
                return false
            }
        } else if (hub.engine.state != EngineClient.State.READY) {
            say("No model is loaded. Open the Models tab and tap Load.")
            return false
        }
        val now = System.currentTimeMillis()
        val base = current ?: Conversation(newId(), ChatCodec.titleFrom(typed), mode, now, emptyList())
        val user = ChatMessage(ChatRole.USER, typed, now, modelText = modelText)
        val history = base.messages + user
        val assistant = ChatMessage(ChatRole.ASSISTANT, "", now)
        val conversation = base.copy(messages = (history + assistant).takeLast(ChatCodec.MAX_MESSAGES), updatedAt = now, mode = mode)
        current = conversation
        summaries = listOf(ChatSummary(conversation.id, conversation.title, now)) + summaries.filter { it.id != conversation.id }
        hub.updateSettings { it.copy(conversationId = conversation.id) }
        partial.setLength(0)
        rawPieces = 0
        busy = true
        notice = null
        val image = pendingImage
        pendingImage = null
        persist(conversation)
        if (settings.usePc) {
            runPc(settings, PromptBuilder.build(mode, history), image)
        } else {
            runPhone(history, PromptBuilder.MAX_PROMPT_CHARS, true)
        }
        hub.changed()
        return true
    }

    private fun runPhone(history: List<ChatMessage>, maxChars: Int, mayRetry: Boolean) {
        val started = hub.engine.generate(
            PromptBuilder.build(mode, history, maxChars),
            PromptBuilder.temperature(mode),
            MAX_NEW_TOKENS,
            onPiece = { piece -> appendPiece(piece) },
            onDone = { code ->
                if (code == EngineCodes.ERR_PROMPT_TOO_LONG && mayRetry && partial.isEmpty()) {
                    runPhone(history, maxChars / 2, false)
                } else {
                    finish(endNote(code))
                }
            },
        )
        if (!started) finish("The engine is not ready.")
    }

    private fun endNote(code: Int): String? = when (code) {
        EngineCodes.OK -> EngineStats.describe(hub.engine.lastTokens, hub.engine.lastMillis)
        EngineClient.ERR_DIED -> "Engine stopped before finishing."
        else -> EngineCodes.message(code)
    }

    private fun runPc(settings: AppSettings, messages: List<PromptMessage>, image: PendingImage?) {
        val endpoint = Endpoints.parse(settings.ollamaAddress, PcStore.OLLAMA_PORT)
        if (endpoint == null) {
            finish("The PC address is not valid.")
            return
        }
        val token = CancelToken()
        pcToken = token
        val temperature = PromptBuilder.temperature(mode)
        hub.background({ finish("The PC request failed.") }) {
            val outcome = client.chat(endpoint, settings.ollamaModel, messages, image?.base64, temperature, token) { piece ->
                hub.main.post { appendPiece(piece) }
            }
            hub.main.post {
                when (outcome) {
                    is PcOutcome.Success -> finish(null)
                    is PcOutcome.Failure -> finish(outcome.message)
                }
            }
        }
    }

    private fun appendPiece(piece: String) {
        if (!busy) return
        rawPieces++
        if (partial.length >= ChatCodec.MAX_TEXT) return
        partial.append(ForeignText.multiline(piece, 8192))
        if (partial.length > ChatCodec.MAX_TEXT) partial.setLength(ChatCodec.MAX_TEXT)
        val now = System.currentTimeMillis()
        if (now - lastPaint >= PAINT_MS) {
            lastPaint = now
            updateAssistant(null)
            hub.changed()
        }
        if (now - lastSave >= SAVE_MS) {
            lastSave = now
            current?.let { persist(it) }
        }
    }

    private fun updateAssistant(note: String?) {
        val conversation = current ?: return
        val last = conversation.messages.lastOrNull() ?: return
        if (last.role != ChatRole.ASSISTANT) return
        val updated = last.copy(text = partial.toString(), note = note)
        current = conversation.copy(messages = conversation.messages.dropLast(1) + updated, updatedAt = System.currentTimeMillis())
    }

    private fun finish(note: String?) {
        if (!busy) return
        busy = false
        pcToken = null
        updateAssistant(note)
        val conversation = current
        if (conversation != null) {
            val trimmed = if (partial.isEmpty()) {
                val last = conversation.messages.last().copy(text = if (rawPieces > 0) "(the answer had no visible text)" else "(no answer)")
                conversation.copy(messages = conversation.messages.dropLast(1) + last)
            } else {
                conversation
            }
            current = trimmed
            persist(trimmed)
        }
        hub.changed()
    }

    fun stop() {
        pcToken?.cancel()
        hub.engine.stop()
    }

    fun planWithModel(command: String, durations: List<Long>, onDone: (String?, String?) -> Unit) {
        if (busy) {
            onDone(null, "Wait for the current answer to finish first.")
            return
        }
        val settings = hub.settings
        val messages = PromptBuilder.plan(command, durations)
        val buffer = StringBuilder()
        if (settings.usePc) {
            val endpoint = Endpoints.parse(settings.ollamaAddress, PcStore.OLLAMA_PORT)
            if (endpoint == null || !OllamaNames.isValid(settings.ollamaModel)) {
                onDone(null, "Set an Ollama address and choose a model on the PC tab first.")
                return
            }
            val token = CancelToken()
            pcToken = token
            busy = true
            hub.changed()
            hub.background({
                busy = false
                pcToken = null
                onDone(null, "The PC request failed.")
                hub.changed()
            }) {
                val outcome = client.chat(endpoint, settings.ollamaModel, messages, null, PromptBuilder.TEMPERATURE_PLAN, token) { piece ->
                    synchronized(buffer) {
                        if (buffer.length <= PlanLimits.MAX_MODEL_OUTPUT) buffer.append(piece)
                    }
                }
                hub.main.post {
                    busy = false
                    pcToken = null
                    when (outcome) {
                        is PcOutcome.Success -> onDone(synchronized(buffer) { buffer.toString() }, null)
                        is PcOutcome.Failure -> onDone(null, outcome.message)
                    }
                    hub.changed()
                }
            }
            return
        }
        if (hub.engine.state != EngineClient.State.READY) {
            onDone(null, "No model is loaded. Load one on the Models tab, or type the plan so no model is needed.")
            return
        }
        busy = true
        val started = hub.engine.generate(
            messages,
            PromptBuilder.TEMPERATURE_PLAN,
            MAX_PLAN_TOKENS,
            onPiece = { piece ->
                if (buffer.length <= PlanLimits.MAX_MODEL_OUTPUT) buffer.append(piece)
            },
            onDone = { code ->
                busy = false
                if (code == EngineCodes.OK || code == EngineCodes.LIMIT_REACHED) {
                    onDone(buffer.toString(), null)
                } else {
                    val text = if (code == EngineClient.ERR_DIED) EngineClient.DIED else EngineCodes.message(code)
                    onDone(null, text)
                }
                hub.changed()
            },
        )
        if (!started) {
            busy = false
            onDone(null, "The engine is not ready.")
        }
        hub.changed()
    }

    private fun persist(conversation: Conversation) {
        val encodedTarget = File(dir, "${conversation.id}.json")
        val list = summaries
        hub.io.execute {
            try {
                AtomicFiles.write(encodedTarget, ChatCodec.encode(conversation))
                writeSummaries(list)
            } catch (e: OutOfMemoryError) {
                summaryWritten = null
            }
        }
    }

    private fun writeSummaries(list: List<ChatSummary>) {
        if (summaryWritten === list) return
        if (AtomicFiles.write(summaryFile, ChatSummaryCodec.encode(list))) summaryWritten = list
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private companion object {
        const val MAX_CONVERSATIONS = 200
        const val PC_READ_TIMEOUT_MS = 300_000
        const val PC_TOTAL_MS = 30L * 60 * 1000
        const val MAX_NEW_TOKENS = 1024
        const val MAX_PLAN_TOKENS = 512
        const val PAINT_MS = 100L
        const val SAVE_MS = 3000L
    }
}

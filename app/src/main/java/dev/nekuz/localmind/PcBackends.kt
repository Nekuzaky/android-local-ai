package dev.nekuz.localmind

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed class PcOutcome<out T> {
    data class Success<T>(val value: T) : PcOutcome<T>()
    data class Failure(val message: String) : PcOutcome<Nothing>()
}

object OllamaNames {
    private val VALID = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")

    fun isValid(name: String?): Boolean = name != null && VALID.matches(name)
}

object OllamaCodec {
    const val MAX_MODELS = 200

    fun tags(text: String?): List<String>? {
        val root = StrictJson.parseObject(text) ?: return null
        val models = root.arr("models") ?: return null
        return models.take(MAX_MODELS)
            .mapNotNull { (it as? JsonValue.JObj)?.str("name") }
            .filter { OllamaNames.isValid(it) }
    }

    fun chatRequest(model: String, messages: List<PromptMessage>, imageBase64: String?, temperature: Float): String {
        val list = JSONArray()
        val lastUser = messages.indexOfLast { it.role == ChatRole.USER.wire }
        messages.forEachIndexed { index, message ->
            val item = JSONObject().put("role", message.role).put("content", message.content)
            if (imageBase64 != null && index == lastUser) item.put("images", JSONArray().put(imageBase64))
            list.put(item)
        }
        return JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("messages", list)
            .put("options", JSONObject().put("temperature", temperature.toDouble()))
            .toString()
    }

    sealed class ChatLine {
        data class Piece(val text: String, val done: Boolean) : ChatLine()
        data class Error(val message: String) : ChatLine()
        data object Bad : ChatLine()
    }

    fun chatLine(line: String): ChatLine {
        val root = StrictJson.parseObject(line, 1024 * 1024) ?: return ChatLine.Bad
        root.str("error")?.let { return ChatLine.Error(ForeignText.clean(it, 300)) }
        val content = root.obj("message")?.str("content")
        val done = root.bool("done") ?: false
        if (content == null && !done) return ChatLine.Bad
        return ChatLine.Piece(content.orEmpty(), done)
    }
}

class OllamaClient(private val http: LanHttp) {
    fun models(endpoint: Endpoint, cancel: CancelToken? = null): PcOutcome<List<String>> =
        when (val result = http.get(endpoint, "/api/tags", MAX_TAGS_BYTES, cancel)) {
            is LanResult.Ok -> OllamaCodec.tags(result.text)?.let { PcOutcome.Success(it) }
                ?: PcOutcome.Failure(LanMessages.of(LanResult.Malformed))
            else -> PcOutcome.Failure(LanMessages.of(result))
        }

    fun chat(
        endpoint: Endpoint,
        model: String,
        messages: List<PromptMessage>,
        imageBase64: String?,
        temperature: Float,
        cancel: CancelToken,
        onPiece: (String) -> Unit,
    ): PcOutcome<Unit> {
        if (!OllamaNames.isValid(model)) return PcOutcome.Failure("That model name is not valid.")
        var failure: String? = null
        var finished = false
        val body = OllamaCodec.chatRequest(model, messages, imageBase64, temperature)
        val result = http.postLines(endpoint, "/api/chat", body, MAX_LINE_BYTES, MAX_STREAM_BYTES, cancel) { line ->
            when (val parsed = OllamaCodec.chatLine(line)) {
                is OllamaCodec.ChatLine.Piece -> {
                    if (parsed.text.isNotEmpty()) onPiece(parsed.text)
                    if (parsed.done) finished = true
                    !parsed.done
                }
                is OllamaCodec.ChatLine.Error -> {
                    failure = parsed.message
                    false
                }
                OllamaCodec.ChatLine.Bad -> {
                    failure = LanMessages.of(LanResult.Malformed)
                    false
                }
            }
        }
        failure?.let { return PcOutcome.Failure(it) }
        return when {
            result is LanResult.Ok && finished -> PcOutcome.Success(Unit)
            result is LanResult.Ok -> PcOutcome.Failure("The server stopped before finishing the answer.")
            else -> PcOutcome.Failure(LanMessages.of(result))
        }
    }

    private companion object {
        const val MAX_TAGS_BYTES = 1024 * 1024
        const val MAX_LINE_BYTES = 256 * 1024
        const val MAX_STREAM_BYTES = 16 * 1024 * 1024
    }
}

data class ComfyParams(
    val prompt: String,
    val negative: String,
    val checkpoint: String,
    val width: Int,
    val height: Int,
    val steps: Int,
    val cfg: Double,
    val seed: Long,
)

object ComfyWorkflow {
    const val MAX_PROMPT = 2000
    const val MAX_CHECKPOINT = 256
    private const val MAX_SEED = 1L shl 48

    fun validate(params: ComfyParams): String? = when {
        params.prompt.isBlank() -> "Type a prompt first."
        params.prompt.length > MAX_PROMPT || params.negative.length > MAX_PROMPT -> "The prompt is too long."
        params.checkpoint.isBlank() || params.checkpoint.length > MAX_CHECKPOINT -> "Choose a checkpoint first."
        params.width !in 256..1536 || params.width % 8 != 0 -> "Width must be a multiple of 8 between 256 and 1536."
        params.height !in 256..1536 || params.height % 8 != 0 -> "Height must be a multiple of 8 between 256 and 1536."
        params.steps !in 1..60 -> "Steps must be between 1 and 60."
        params.cfg.isNaN() || params.cfg < 1.0 || params.cfg > 20.0 -> "CFG must be between 1 and 20."
        params.seed < 0 || params.seed > MAX_SEED -> "The seed is out of range."
        else -> null
    }

    fun build(params: ComfyParams): JSONObject {
        fun link(node: String, slot: Int) = JSONArray().put(node).put(slot)
        fun node(type: String, inputs: JSONObject) = JSONObject().put("class_type", type).put("inputs", inputs)
        return JSONObject()
            .put("4", node("CheckpointLoaderSimple", JSONObject().put("ckpt_name", params.checkpoint)))
            .put(
                "5",
                node(
                    "EmptyLatentImage",
                    JSONObject().put("width", params.width).put("height", params.height).put("batch_size", 1),
                ),
            )
            .put("6", node("CLIPTextEncode", JSONObject().put("text", params.prompt).put("clip", link("4", 1))))
            .put("7", node("CLIPTextEncode", JSONObject().put("text", params.negative).put("clip", link("4", 1))))
            .put(
                "3",
                node(
                    "KSampler",
                    JSONObject()
                        .put("seed", params.seed)
                        .put("steps", params.steps)
                        .put("cfg", params.cfg)
                        .put("sampler_name", "euler")
                        .put("scheduler", "normal")
                        .put("denoise", 1.0)
                        .put("model", link("4", 0))
                        .put("positive", link("6", 0))
                        .put("negative", link("7", 0))
                        .put("latent_image", link("5", 0)),
                ),
            )
            .put("8", node("VAEDecode", JSONObject().put("samples", link("3", 0)).put("vae", link("4", 2))))
            .put("9", node("SaveImage", JSONObject().put("filename_prefix", "LocalMind").put("images", link("8", 0))))
    }

    fun request(params: ComfyParams, clientId: String): String =
        JSONObject().put("prompt", build(params)).put("client_id", clientId).toString()
}

data class ComfyImageRef(val filename: String, val subfolder: String, val type: String)

sealed class ComfyStatus {
    data object Pending : ComfyStatus()
    data class Ready(val image: ComfyImageRef) : ComfyStatus()
    data class Failed(val message: String) : ComfyStatus()
}

object ComfyCodec {
    private const val MAX_CHECKPOINTS = 500
    private val PROMPT_ID = Regex("[A-Za-z0-9-]{1,64}")
    private val SAFE_NAME = Regex("[A-Za-z0-9 _.()-]{1,200}")
    private val ALLOWED_TYPES = setOf("output", "temp")

    fun checkpoints(text: String?): List<String>? {
        val root = StrictJson.parseObject(text, ComfyClient.MAX_JSON_BYTES) ?: return null
        val required = root.obj("CheckpointLoaderSimple")?.obj("input")?.obj("required") ?: return null
        val spec = required.arr("ckpt_name") ?: return null
        val first = spec.firstOrNull()
        val names = when {
            first is JsonValue.JArr -> first.items
            first is JsonValue.JStr && first.value == "COMBO" ->
                ((spec.getOrNull(1) as? JsonValue.JObj)?.arr("options")) ?: return null
            else -> return null
        }
        return names.take(MAX_CHECKPOINTS)
            .mapNotNull { (it as? JsonValue.JStr)?.value }
            .filter { it.isNotBlank() && it.length <= ComfyWorkflow.MAX_CHECKPOINT && it.none { c -> c.code < 0x20 } }
    }

    fun promptId(text: String?): String? {
        val root = StrictJson.parseObject(text, 1024 * 1024) ?: return null
        return root.str("prompt_id")?.takeIf { PROMPT_ID.matches(it) }
    }

    fun isValidPromptId(id: String?): Boolean = id != null && PROMPT_ID.matches(id)

    fun status(text: String?, promptId: String): ComfyStatus? {
        val root = StrictJson.parseObject(text, ComfyClient.MAX_JSON_BYTES) ?: return null
        val entry = root.obj(promptId) ?: return ComfyStatus.Pending
        val state = entry.obj("status")
        if (state?.str("status_str") == "error") return ComfyStatus.Failed("ComfyUI reported an error for this workflow.")
        val outputs = entry.obj("outputs") ?: return ComfyStatus.Pending
        for (output in outputs.fields.values) {
            val images = (output as? JsonValue.JObj)?.arr("images") ?: continue
            for (image in images) {
                val item = image as? JsonValue.JObj ?: continue
                val ref = imageRef(item)
                if (ref != null) return ComfyStatus.Ready(ref)
            }
        }
        return if (state?.bool("completed") == true) {
            ComfyStatus.Failed("ComfyUI finished without a usable image.")
        } else {
            ComfyStatus.Pending
        }
    }

    private fun imageRef(item: JsonValue.JObj): ComfyImageRef? {
        val filename = item.str("filename") ?: return null
        val subfolder = item.str("subfolder").orEmpty()
        val type = item.str("type") ?: return null
        if (!isSafeName(filename) || type !in ALLOWED_TYPES) return null
        if (subfolder.isNotEmpty() && !isSafeName(subfolder)) return null
        return ComfyImageRef(filename, subfolder, type)
    }

    fun isSafeName(name: String): Boolean =
        SAFE_NAME.matches(name) && !name.startsWith(".") && !name.contains("..")

    fun viewPath(ref: ComfyImageRef): String = "/view?filename=" + encode(ref.filename) +
        "&subfolder=" + encode(ref.subfolder) + "&type=" + encode(ref.type)

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}

enum class ImageKind(val mime: String, val extension: String) {
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    WEBP("image/webp", "webp"),
}

object ImageSniff {
    fun kind(bytes: ByteArray): ImageKind? {
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
        ) {
            return ImageKind.PNG
        }
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return ImageKind.JPEG
        }
        if (bytes.size >= 12 && String(bytes, 0, 4, StandardCharsets.ISO_8859_1) == "RIFF" &&
            String(bytes, 8, 4, StandardCharsets.ISO_8859_1) == "WEBP"
        ) {
            return ImageKind.WEBP
        }
        return null
    }
}

data class ComfyImage(val bytes: ByteArray, val kind: ImageKind) {
    override fun equals(other: Any?) = other is ComfyImage && other.kind == kind && other.bytes.contentEquals(bytes)
    override fun hashCode() = bytes.contentHashCode()
}

class ComfyClient(
    private val http: LanHttp,
    private val pollIntervalMs: Long = 1_000,
    private val maxPolls: Int = 1_200,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    fun checkpoints(endpoint: Endpoint, cancel: CancelToken? = null): PcOutcome<List<String>> =
        when (val result = http.get(endpoint, "/object_info/CheckpointLoaderSimple", MAX_JSON_BYTES, cancel)) {
            is LanResult.Ok -> ComfyCodec.checkpoints(result.text)?.let { PcOutcome.Success(it) }
                ?: PcOutcome.Failure(LanMessages.of(LanResult.Malformed))
            else -> PcOutcome.Failure(LanMessages.of(result))
        }

    fun generate(endpoint: Endpoint, params: ComfyParams, clientId: String, cancel: CancelToken): PcOutcome<ComfyImage> {
        ComfyWorkflow.validate(params)?.let { return PcOutcome.Failure(it) }
        val submitted = http.postJson(endpoint, "/prompt", ComfyWorkflow.request(params, clientId), MAX_JSON_BYTES, cancel)
        val promptId = when (submitted) {
            is LanResult.Ok -> ComfyCodec.promptId(submitted.text)
                ?: return PcOutcome.Failure(LanMessages.of(LanResult.Malformed))
            else -> return PcOutcome.Failure(LanMessages.of(submitted))
        }
        for (poll in 0 until maxPolls) {
            if (cancel.cancelled) return PcOutcome.Failure(LanMessages.of(LanResult.Cancelled))
            val history = http.get(endpoint, "/history/$promptId", MAX_JSON_BYTES, cancel)
            if (history !is LanResult.Ok) return PcOutcome.Failure(LanMessages.of(history))
            when (val status = ComfyCodec.status(history.text, promptId)) {
                null -> return PcOutcome.Failure(LanMessages.of(LanResult.Malformed))
                is ComfyStatus.Failed -> return PcOutcome.Failure(status.message)
                is ComfyStatus.Ready -> return fetch(endpoint, status.image, cancel)
                ComfyStatus.Pending -> try {
                    sleep(pollIntervalMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return PcOutcome.Failure(LanMessages.of(LanResult.Cancelled))
                }
            }
        }
        return PcOutcome.Failure("ComfyUI took too long, so LocalMind stopped waiting.")
    }

    private fun fetch(endpoint: Endpoint, ref: ComfyImageRef, cancel: CancelToken): PcOutcome<ComfyImage> {
        val image = http.get(endpoint, ComfyCodec.viewPath(ref), MAX_IMAGE_BYTES, cancel)
        if (image !is LanResult.Ok) return PcOutcome.Failure(LanMessages.of(image))
        val kind = ImageSniff.kind(image.body) ?: return PcOutcome.Failure("The server did not send a PNG, JPEG or WebP image.")
        return PcOutcome.Success(ComfyImage(image.body, kind))
    }

    companion object {
        const val MAX_JSON_BYTES = 1024 * 1024
        const val MAX_IMAGE_BYTES = 32 * 1024 * 1024
    }
}

data class AppSettings(
    val usePc: Boolean = false,
    val ollamaAddress: String = "",
    val ollamaModel: String = "",
    val comfyAddress: String = "",
    val comfyCheckpoint: String = "",
    val phoneModelId: String = "",
    val conversationId: String = "",
    val searchFolder: String = "",
)

object SettingsCodec {
    private const val MAX_FIELD = 2048

    fun encode(settings: AppSettings): String = JSONObject()
        .put("usePc", settings.usePc)
        .put("ollamaAddress", settings.ollamaAddress)
        .put("ollamaModel", settings.ollamaModel)
        .put("comfyAddress", settings.comfyAddress)
        .put("comfyCheckpoint", settings.comfyCheckpoint)
        .put("phoneModelId", settings.phoneModelId)
        .put("conversationId", settings.conversationId)
        .put("searchFolder", settings.searchFolder)
        .toString()

    fun decode(text: String?): AppSettings {
        val root = StrictJson.parseObject(text, 64 * 1024) ?: return AppSettings()
        fun field(key: String) = root.str(key)?.takeIf { it.length <= MAX_FIELD }.orEmpty()
        return AppSettings(
            usePc = root.bool("usePc") ?: false,
            ollamaAddress = field("ollamaAddress"),
            ollamaModel = field("ollamaModel").takeIf { OllamaNames.isValid(it) }.orEmpty(),
            comfyAddress = field("comfyAddress"),
            comfyCheckpoint = field("comfyCheckpoint"),
            phoneModelId = field("phoneModelId"),
            conversationId = field("conversationId").takeIf { ChatCodec.isValidId(it) }.orEmpty(),
            searchFolder = field("searchFolder"),
        )
    }
}

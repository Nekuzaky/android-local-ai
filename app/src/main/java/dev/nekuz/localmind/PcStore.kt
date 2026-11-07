package dev.nekuz.localmind

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.UUID

object Images {
    private const val MAX_PIXELS = 100_000_000L
    private const val MAX_INPUT_BYTES = 32 * 1024 * 1024
    private const val VISION_SIDE = 1280
    const val PREVIEW_SIDE = 1600

    fun decodeBounded(bytes: ByteArray, maxSide: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) {
            null
        } else {
            var sample = 1
            while (width / sample > maxSide || height / sample > maxSide) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    fun forVision(hub: Hub, uri: Uri): ChatStore.PendingImage? {
        val bytes = hub.app.contentResolver.openInputStream(uri)?.use { Bounded.readBytes(it, MAX_INPUT_BYTES) } ?: return null
        val bitmap = decodeBounded(bytes, VISION_SIDE) ?: return null
        return try {
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)) return null
            val encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            ChatStore.PendingImage(encoded, "${bitmap.width} x ${bitmap.height}")
        } finally {
            bitmap.recycle()
        }
    }
}

class PcStore(private val hub: Hub) {
    data class Result(val image: ComfyImage, val preview: Bitmap)

    var ollamaModels: List<String> = emptyList()
        private set
    var ollamaMessage: String? = null
        private set
    var checkpoints: List<String> = emptyList()
        private set
    var comfyMessage: String? = null
        private set
    var generating = false
        private set
    var result: Result? = null
        private set
    var saved = false
        private set
    private var saving = false

    private var token: CancelToken? = null
    private var ollamaToken: CancelToken? = null
    private var comfyToken: CancelToken? = null
    private val http = LanHttp()

    fun connectOllama(address: String) {
        val endpoint = Endpoints.parse(address, OLLAMA_PORT)
        if (endpoint == null) {
            ollamaMessage = "Enter an address such as 192.168.1.20 or 192.168.1.20:11434."
            hub.changed()
            return
        }
        ollamaMessage = "Connecting…"
        ollamaToken?.cancel()
        val attempt = CancelToken()
        ollamaToken = attempt
        hub.updateSettings { it.copy(ollamaAddress = endpoint.display) }
        hub.background({
            ollamaMessage = "The request failed."
            hub.changed()
        }) {
            val outcome = OllamaClient(http).models(endpoint, attempt)
            hub.main.post {
                if (ollamaToken !== attempt) return@post
                when (outcome) {
                    is PcOutcome.Success -> {
                        ollamaModels = outcome.value
                        ollamaMessage = if (outcome.value.isEmpty()) "Connected, but this Ollama has no models." else "Connected: ${outcome.value.size} models."
                    }
                    is PcOutcome.Failure -> {
                        ollamaModels = emptyList()
                        ollamaMessage = outcome.message
                    }
                }
                hub.changed()
            }
        }
    }

    fun connectComfy(address: String) {
        val endpoint = Endpoints.parse(address, COMFY_PORT)
        if (endpoint == null) {
            comfyMessage = "Enter an address such as 192.168.1.20 or 192.168.1.20:8188."
            hub.changed()
            return
        }
        comfyMessage = "Connecting…"
        comfyToken?.cancel()
        val attempt = CancelToken()
        comfyToken = attempt
        hub.updateSettings { it.copy(comfyAddress = endpoint.display) }
        hub.background({
            comfyMessage = "The request failed."
            hub.changed()
        }) {
            val outcome = ComfyClient(http).checkpoints(endpoint, attempt)
            hub.main.post {
                if (comfyToken !== attempt) return@post
                when (outcome) {
                    is PcOutcome.Success -> {
                        checkpoints = outcome.value
                        comfyMessage = if (outcome.value.isEmpty()) "Connected, but ComfyUI lists no checkpoints." else "Connected: ${outcome.value.size} checkpoints."
                        if (hub.settings.comfyCheckpoint !in outcome.value) {
                            hub.updateSettings { it.copy(comfyCheckpoint = outcome.value.firstOrNull().orEmpty()) }
                        }
                    }
                    is PcOutcome.Failure -> {
                        checkpoints = emptyList()
                        comfyMessage = outcome.message
                    }
                }
                hub.changed()
            }
        }
    }

    fun generate(prompt: String) {
        if (generating) return
        val settings = hub.settings
        val endpoint = Endpoints.parse(settings.comfyAddress, COMFY_PORT)
        if (endpoint == null) {
            comfyMessage = "Connect to ComfyUI first."
            hub.changed()
            return
        }
        val params = ComfyParams(
            prompt = prompt.trim(),
            negative = "",
            checkpoint = settings.comfyCheckpoint,
            width = 512,
            height = 512,
            steps = 20,
            cfg = 7.0,
            seed = (System.nanoTime() and 0xFFFFFFFFL),
        )
        val problem = ComfyWorkflow.validate(params)
        if (problem != null) {
            comfyMessage = problem
            hub.changed()
            return
        }
        val current = CancelToken()
        token = current
        generating = true
        saved = false
        comfyMessage = "Waiting for ComfyUI…"
        hub.changed()
        hub.background({
            generating = false
            comfyMessage = "Image generation failed."
            hub.changed()
        }) {
            val outcome = ComfyClient(http).generate(endpoint, params, UUID.randomUUID().toString(), current)
            val preview = (outcome as? PcOutcome.Success)?.value?.let { Images.decodeBounded(it.bytes, Images.PREVIEW_SIDE) }
            hub.main.post {
                generating = false
                token = null
                when (outcome) {
                    is PcOutcome.Success -> {
                        if (preview == null) {
                            comfyMessage = "The server's image could not be decoded."
                        } else {
                            result = Result(outcome.value, preview)
                            comfyMessage = "Done. Nothing is saved until you tap Save."
                        }
                    }
                    is PcOutcome.Failure -> comfyMessage = outcome.message
                }
                hub.changed()
            }
        }
    }

    fun cancel() {
        token?.cancel()
    }

    fun save() {
        val current = result ?: return
        if (saving || saved) return
        saving = true
        hub.background({
            saving = false
            comfyMessage = "The image could not be saved."
            hub.changed()
        }) {
            val done = MediaSave.image(hub.app, current.image.bytes, current.image.kind)
            hub.main.post {
                saving = false
                saved = done
                comfyMessage = if (done) "Saved to Pictures/LocalMind." else "The image could not be saved."
                hub.changed()
            }
        }
    }

    companion object {
        const val OLLAMA_PORT = 11434
        const val COMFY_PORT = 8188
    }
}

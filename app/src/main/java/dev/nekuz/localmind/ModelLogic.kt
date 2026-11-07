package dev.nekuz.localmind

import org.json.JSONArray
import org.json.JSONObject

enum class GgufCheck { OK, TOO_SHORT, BAD_MAGIC, BAD_VERSION, ABSURD_COUNTS }

object GgufHeader {
    const val BYTES = 24
    private const val MAX_TENSORS = 1_000_000L
    private const val MAX_KEYS = 1_000_000L

    fun check(header: ByteArray): GgufCheck {
        if (header.size < BYTES) return GgufCheck.TOO_SHORT
        if (header[0] != 'G'.code.toByte() || header[1] != 'G'.code.toByte() ||
            header[2] != 'U'.code.toByte() || header[3] != 'F'.code.toByte()
        ) {
            return GgufCheck.BAD_MAGIC
        }
        val version = little(header, 4, 4)
        if (version != 2L && version != 3L) return GgufCheck.BAD_VERSION
        val tensors = little(header, 8, 8)
        val keys = little(header, 16, 8)
        if (tensors < 0 || tensors > MAX_TENSORS || keys < 0 || keys > MAX_KEYS) return GgufCheck.ABSURD_COUNTS
        return GgufCheck.OK
    }

    private fun little(bytes: ByteArray, offset: Int, count: Int): Long {
        var value = 0L
        for (i in count - 1 downTo 0) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return value
    }
}

sealed class MemoryCheck {
    data class Fits(val neededBytes: Long) : MemoryCheck()
    data class TooBig(val neededBytes: Long, val availableBytes: Long) : MemoryCheck()
}

object MemoryGate {
    const val CONTEXT_TOKENS = 4096
    private const val BYTES_PER_CONTEXT_TOKEN = 200L * 1024
    private const val MARGIN = 256L * 1024 * 1024

    fun neededBytes(modelBytes: Long, contextTokens: Int): Long =
        modelBytes.coerceAtLeast(0) + contextTokens.coerceAtLeast(0) * BYTES_PER_CONTEXT_TOKEN + MARGIN

    fun check(modelBytes: Long, contextTokens: Int, availableBytes: Long): MemoryCheck {
        val needed = neededBytes(modelBytes, contextTokens)
        return if (needed > availableBytes) MemoryCheck.TooBig(needed, availableBytes) else MemoryCheck.Fits(needed)
    }
}

object CpuFeatures {
    private val REQUIRED = listOf("asimddp", "asimdhp")

    fun missing(cpuInfo: String?): List<String> {
        if (cpuInfo == null) return emptyList()
        val lines = cpuInfo.lineSequence()
            .filter { it.trimStart().startsWith("Features", ignoreCase = true) && it.contains(':') }
            .toList()
        if (lines.isEmpty()) return emptyList()
        val shared = lines.map { it.substringAfter(':').trim().split(' ', '\t').toSet() }
        return REQUIRED.filter { feature -> shared.any { feature !in it } }
    }
}

object Sizes {
    fun human(bytes: Long): String {
        val value = bytes.coerceAtLeast(0)
        return when {
            value >= GIB -> String.format(java.util.Locale.ROOT, "%.2f GB", value.toDouble() / GIB)
            value >= MIB -> String.format(java.util.Locale.ROOT, "%.0f MB", value.toDouble() / MIB)
            value >= 1024 -> "${value / 1024} KB"
            else -> "$value B"
        }
    }

    private const val GIB = 1024L * 1024 * 1024
    private const val MIB = 1024L * 1024
}

enum class ModelSource { CATALOGUE, IMPORT }

data class InstalledModel(
    val id: String,
    val name: String,
    val file: String,
    val source: ModelSource,
    val sha256: String,
    val sizeBytes: Long,
)

object EnginePolicy {
    const val IMPORT_ENABLED = true
    private const val FLAG_ISOLATED = 1
    private const val FLAG_MAPPED = 2

    fun requiresIsolation(source: ModelSource): Boolean = source == ModelSource.IMPORT

    fun mustRefuse(requireIsolated: Boolean, engineIsolated: Boolean): Boolean = requireIsolated && !engineIsolated

    fun flags(isolated: Boolean, mapped: Boolean): Int =
        (if (isolated) FLAG_ISOLATED else 0) or (if (mapped) FLAG_MAPPED else 0)

    fun describe(flags: Int): String {
        val where = if (flags and FLAG_ISOLATED != 0) "isolated engine" else "engine, NOT isolated"
        val how = if (flags and FLAG_MAPPED != 0) "memory-mapped" else "read into memory"
        return "$where, $how"
    }
}

enum class ModelGateResult { OK, BAD_NAME, MISSING, WRONG_SIZE, WRONG_HASH, NOT_IN_CATALOGUE }

object ModelFiles {
    private val SAFE = Regex("[a-z0-9][a-z0-9._-]{0,80}\\.gguf")

    fun isSafeName(name: String?): Boolean =
        name != null && SAFE.matches(name) && !name.contains("..")

    fun gate(model: InstalledModel, actualSize: Long?, actualSha: String?): ModelGateResult {
        if (!isSafeName(model.file)) return ModelGateResult.BAD_NAME
        if (actualSize == null || actualSha == null) return ModelGateResult.MISSING
        val pinned = if (model.source == ModelSource.CATALOGUE) {
            Catalogue.byId(model.id) ?: return ModelGateResult.NOT_IN_CATALOGUE
        } else {
            null
        }
        val wantedSize = pinned?.sizeBytes ?: model.sizeBytes
        val wantedSha = pinned?.sha256 ?: model.sha256
        if (actualSize != wantedSize) return ModelGateResult.WRONG_SIZE
        if (actualSha != wantedSha) return ModelGateResult.WRONG_HASH
        return ModelGateResult.OK
    }
}

object ModelRegistryCodec {
    private const val MAX_MODELS = 64

    fun mayCleanOrphans(registryExists: Boolean, text: String?): Boolean =
        !registryExists || StrictJson.parseObject(text)?.arr("models") != null

    fun encode(models: List<InstalledModel>): String {
        val list = JSONArray()
        for (model in models.take(MAX_MODELS)) {
            list.put(
                JSONObject()
                    .put("id", model.id)
                    .put("name", model.name)
                    .put("file", model.file)
                    .put("source", model.source.name)
                    .put("sha256", model.sha256)
                    .put("size", model.sizeBytes),
            )
        }
        return JSONObject().put("models", list).toString()
    }

    fun decode(text: String?): List<InstalledModel> {
        val root = StrictJson.parseObject(text) ?: return emptyList()
        val items = root.arr("models") ?: return emptyList()
        val out = ArrayList<InstalledModel>()
        for (item in items.take(MAX_MODELS)) {
            val entry = item as? JsonValue.JObj ?: continue
            val id = entry.str("id") ?: continue
            val file = entry.str("file") ?: continue
            val sha = entry.str("sha256") ?: continue
            val size = entry.long("size") ?: continue
            val source = ModelSource.entries.firstOrNull { it.name == entry.str("source") } ?: continue
            if (!ModelFiles.isSafeName(file) || !Sha256.isValidHex(sha) || size <= 0 || id.length > 128) continue
            if (out.any { it.id == id || it.file == file }) continue
            out.add(InstalledModel(id, ForeignText.label(entry.str("name"), id), file, source, sha, size))
        }
        return out
    }
}

package dev.nekuz.localmind

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

data class CatalogueModel(
    val id: String,
    val name: String,
    val purpose: String,
    val licence: String,
    val repo: String,
    val revision: String,
    val file: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    val url: String
        get() = "https://huggingface.co/$repo/resolve/$revision/$file"
    val localFile: String
        get() = "$id.gguf"
}

object Catalogue {
    val models = listOf(
        CatalogueModel(
            id = "qwen25-coder-15b-q4km",
            name = "Qwen2.5-Coder 1.5B Instruct Q4_K_M",
            purpose = "Best fit for code questions on this phone.",
            licence = "Apache-2.0",
            repo = "Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF",
            revision = "f86cb2c1fa58255f8052cc32aeede1b7482d4361",
            file = "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            sizeBytes = 1_117_320_768L,
            sha256 = "cc324af070c2ecbfd324a30884d2f951a7ff756aba85cb811a6ec436933bb046",
        ),
        CatalogueModel(
            id = "smollm2-17b-q4km",
            name = "SmolLM2 1.7B Instruct Q4_K_M",
            purpose = "Small general chat model.",
            licence = "Apache-2.0",
            repo = "HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF",
            revision = "2d4a76a30b4af41ecd395c35725ac11688d4cfe4",
            file = "smollm2-1.7b-instruct-q4_k_m.gguf",
            sizeBytes = 1_055_609_536L,
            sha256 = "decd2598bc2c8ed08c19adc3c8fdd461ee19ed5708679d1c54ef54a5a30d4f33",
        ),
        CatalogueModel(
            id = "granite33-2b-q4km",
            name = "Granite 3.3 2B Instruct Q4_K_M",
            purpose = "Larger general model, slower, better answers.",
            licence = "Apache-2.0",
            repo = "ibm-granite/granite-3.3-2b-instruct-GGUF",
            revision = "7cdf86ccd1f1bb3491c9b7017b033f2e51367397",
            file = "granite-3.3-2b-instruct-Q4_K_M.gguf",
            sizeBytes = 1_545_303_328L,
            sha256 = "ac71e9e32c0bea919b409c5918f69ca74339854b0319c5065e4e9fb6d95c4852",
        ),
    )

    fun byId(id: String?): CatalogueModel? = models.firstOrNull { it.id == id }
}

object DownloadUrls {
    private val EXACT = setOf("huggingface.co", "hf.co")
    private val SUFFIXES = listOf(".huggingface.co", ".hf.co")
    private const val MAX_LENGTH = 4096

    fun isAllowed(url: String?): Boolean {
        if (url == null || url.length > MAX_LENGTH) return false
        if (url.any { it.code <= 0x20 || it.code >= 0x7F || it == '\\' }) return false
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return false
        }
        if (!"https".equals(uri.scheme, ignoreCase = true)) return false
        if (uri.rawUserInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (host.endsWith(".")) return false
        return host in EXACT || SUFFIXES.any { host.endsWith(it) && host.length > it.length }
    }
}

package dev.nekuz.localmind

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL

sealed class DownloadResult {
    data object Done : DownloadResult()
    data object Cancelled : DownloadResult()
    data object HashMismatch : DownloadResult()
    data object SizeMismatch : DownloadResult()
    data object UrlRefused : DownloadResult()
    data class NoSpace(val neededBytes: Long, val freeBytes: Long) : DownloadResult()
    data class HttpFailure(val code: Int) : DownloadResult()
    data object NetworkFailure : DownloadResult()
    data object StorageFailure : DownloadResult()
}

class ResumableDownload(
    private val url: String,
    private val partFile: File,
    private val expectedSize: Long,
    private val expectedSha256: String,
    private val allowUrl: (String) -> Boolean,
    private val freeBytes: (File) -> Long,
    private val cancel: CancelToken,
    private val onProgress: (Long, Long) -> Unit = { _, _ -> },
    private val onVerifying: () -> Unit = {},
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 30_000,
) {
    private sealed class Opened {
        class Body(val connection: HttpURLConnection, val append: Boolean) : Opened()
        class Stop(val result: DownloadResult) : Opened()
        data object RangeRejected : Opened()
    }

    fun run(): DownloadResult {
        if (expectedSize <= 0 || !Sha256.isValidHex(expectedSha256)) return DownloadResult.SizeMismatch
        var existing = safeLength()
        if (existing > expectedSize) {
            partFile.delete()
            existing = 0
        }
        if (existing < expectedSize) {
            val needed = expectedSize - existing + SPACE_MARGIN
            val free = try {
                freeBytes(partFile.parentFile ?: partFile)
            } catch (e: RuntimeException) {
                0L
            }
            if (free < needed) return DownloadResult.NoSpace(needed, free)
            val result = fetch(existing)
            if (result != null) return result
        }
        if (cancel.cancelled) return DownloadResult.Cancelled
        onVerifying()
        val actual = Sha256.ofFile(partFile, cancel)
        if (cancel.cancelled) return DownloadResult.Cancelled
        if (actual == null) return DownloadResult.StorageFailure
        if (actual != expectedSha256) {
            partFile.delete()
            return DownloadResult.HashMismatch
        }
        return DownloadResult.Done
    }

    private fun safeLength(): Long = try {
        if (partFile.isFile) partFile.length() else 0L
    } catch (e: SecurityException) {
        0L
    }

    private fun fetch(startFrom: Long): DownloadResult? {
        var existing = startFrom
        for (attempt in 0 until 2) {
            if (cancel.cancelled) return DownloadResult.Cancelled
            when (val opened = open(existing)) {
                is Opened.Stop -> return opened.result
                Opened.RangeRejected -> {
                    partFile.delete()
                    existing = 0
                }
                is Opened.Body -> return copy(opened, existing)
            }
        }
        return DownloadResult.NetworkFailure
    }

    private fun open(existing: Long): Opened {
        var current = url
        for (hop in 0..MAX_REDIRECTS) {
            if (!allowUrl(current)) return Opened.Stop(DownloadResult.UrlRefused)
            val connection = try {
                URL(current).openConnection() as HttpURLConnection
            } catch (e: IOException) {
                return Opened.Stop(DownloadResult.NetworkFailure)
            } catch (e: ClassCastException) {
                return Opened.Stop(DownloadResult.UrlRefused)
            }
            cancel.onCancel { connection.disconnect() }
            val outcome: Opened? = try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (existing > 0) connection.setRequestProperty("Range", "bytes=$existing-")
                val code = connection.responseCode
                when {
                    code in REDIRECTS -> {
                        val next = connection.getHeaderField("Location")?.let { resolve(current, it) }
                        connection.disconnect()
                        if (next == null) {
                            Opened.Stop(DownloadResult.UrlRefused)
                        } else {
                            current = next
                            null
                        }
                    }
                    code == 200 -> {
                        val length = connection.getHeaderField("Content-Length")?.toLongOrNull()
                        if (length != null && length != expectedSize) {
                            connection.disconnect()
                            Opened.Stop(DownloadResult.SizeMismatch)
                        } else {
                            Opened.Body(connection, false)
                        }
                    }
                    code == 206 -> {
                        if (existing > 0 && validRange(connection.getHeaderField("Content-Range"), existing)) {
                            Opened.Body(connection, true)
                        } else {
                            connection.disconnect()
                            Opened.RangeRejected
                        }
                    }
                    code == 416 && existing > 0 -> {
                        connection.disconnect()
                        Opened.RangeRejected
                    }
                    else -> {
                        connection.disconnect()
                        Opened.Stop(DownloadResult.HttpFailure(code))
                    }
                }
            } catch (e: IOException) {
                connection.disconnect()
                Opened.Stop(if (cancel.cancelled) DownloadResult.Cancelled else DownloadResult.NetworkFailure)
            } catch (e: RuntimeException) {
                connection.disconnect()
                Opened.Stop(DownloadResult.NetworkFailure)
            }
            if (outcome != null) return outcome
        }
        return Opened.Stop(DownloadResult.UrlRefused)
    }

    private fun resolve(base: String, location: String): String? = try {
        if (location.length > 4096) null else URI(base).resolve(location).toString()
    } catch (e: URISyntaxException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: MalformedURLException) {
        null
    }

    private fun validRange(header: String?, existing: Long): Boolean {
        val match = header?.trim()?.let { CONTENT_RANGE.matchEntire(it) } ?: return false
        val first = match.groupValues[1].toLongOrNull() ?: return false
        val last = match.groupValues[2].toLongOrNull() ?: return false
        val total = match.groupValues[3].toLongOrNull() ?: return false
        return first == existing && total == expectedSize && last == expectedSize - 1
    }

    private fun copy(body: Opened.Body, existing: Long): DownloadResult? {
        val connection = body.connection
        var done = if (body.append) existing else 0L
        if (cancel.cancelled) {
            cancel.onCancel(null)
            connection.disconnect()
            return DownloadResult.Cancelled
        }
        val target = try {
            FileOutputStream(partFile, body.append)
        } catch (e: IOException) {
            cancel.onCancel(null)
            connection.disconnect()
            return DownloadResult.StorageFailure
        }
        try {
            connection.inputStream.use { input ->
                target.use { out ->
                    val buffer = ByteArray(128 * 1024)
                    onProgress(done, expectedSize)
                    while (true) {
                        if (cancel.cancelled) return DownloadResult.Cancelled
                        val count = input.read(buffer)
                        if (count < 0) break
                        done += count
                        if (done > expectedSize) {
                            out.close()
                            partFile.delete()
                            return DownloadResult.SizeMismatch
                        }
                        try {
                            out.write(buffer, 0, count)
                        } catch (e: IOException) {
                            return DownloadResult.StorageFailure
                        }
                        onProgress(done, expectedSize)
                    }
                }
            }
        } catch (e: IOException) {
            return if (cancel.cancelled) DownloadResult.Cancelled else DownloadResult.NetworkFailure
        } finally {
            cancel.onCancel(null)
            connection.disconnect()
        }
        if (cancel.cancelled) return DownloadResult.Cancelled
        return if (done == expectedSize) null else DownloadResult.NetworkFailure
    }

    companion object {
        const val SPACE_MARGIN = 64L * 1024 * 1024
        private const val MAX_REDIRECTS = 8
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val CONTENT_RANGE = Regex("bytes ([0-9]{1,18})-([0-9]{1,18})/([0-9]{1,18})")
    }
}

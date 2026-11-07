package dev.nekuz.localmind

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

sealed class LanResult {
    data class Ok(val body: ByteArray) : LanResult() {
        val text: String
            get() = String(body, StandardCharsets.UTF_8)

        override fun equals(other: Any?) = other is Ok && other.body.contentEquals(body)
        override fun hashCode() = body.contentHashCode()
    }

    data class Refused(val reason: String) : LanResult()
    data class HttpError(val code: Int, val detail: String) : LanResult()
    data object Redirected : LanResult()
    data object TooLarge : LanResult()
    data object TimedOut : LanResult()
    data object Cancelled : LanResult()
    data object NetworkFailure : LanResult()
    data object Malformed : LanResult()
}

object LanMessages {
    fun of(result: LanResult): String = when (result) {
        is LanResult.Ok -> "Done."
        is LanResult.Refused -> result.reason
        is LanResult.HttpError -> "The server answered with error ${result.code}." + if (result.detail.isEmpty()) "" else " ${result.detail}"
        LanResult.Redirected -> "The server tried to redirect the request, so LocalMind stopped."
        LanResult.TooLarge -> "The server sent more data than allowed, so LocalMind stopped reading."
        LanResult.TimedOut -> "The server took too long, so LocalMind stopped."
        LanResult.Cancelled -> "Stopped."
        LanResult.NetworkFailure -> "Could not reach the server. Check the address and that the PC is on the same network."
        LanResult.Malformed -> "The server sent something LocalMind could not read."
    }
}

class LanHttp(
    private val resolver: HostResolver = SystemResolver,
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 120_000,
    private val totalDeadlineMs: Long = 120_000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    fun get(endpoint: Endpoint, path: String, maxBytes: Int, cancel: CancelToken? = null): LanResult =
        exchange(endpoint, path, null, cancel) { input, deadline -> readLimited(input, maxBytes, cancel, deadline) }

    fun postJson(endpoint: Endpoint, path: String, body: String, maxBytes: Int, cancel: CancelToken? = null): LanResult =
        exchange(endpoint, path, body, cancel) { input, deadline -> readLimited(input, maxBytes, cancel, deadline) }

    fun postLines(
        endpoint: Endpoint,
        path: String,
        body: String,
        maxLineBytes: Int,
        maxTotalBytes: Int,
        cancel: CancelToken?,
        onLine: (String) -> Boolean,
    ): LanResult = exchange(endpoint, path, body, cancel) { input, deadline ->
        readLines(input, maxLineBytes, maxTotalBytes, cancel, deadline, onLine)
    }

    private fun exchange(
        endpoint: Endpoint,
        path: String,
        body: String?,
        cancel: CancelToken?,
        reader: (InputStream, Long) -> LanResult,
    ): LanResult {
        if (!path.startsWith("/") || path.startsWith("//") || path.any { it.code <= 0x20 || it.code >= 0x7F }) {
            return LanResult.Malformed
        }
        val allowed = when (val guard = AddressGuard.check(endpoint, resolver)) {
            is GuardResult.Refused -> return LanResult.Refused(guard.reason)
            is GuardResult.Allowed -> guard
        }
        var connection: HttpURLConnection? = null
        val deadline = clock() + totalDeadlineMs
        try {
            if (cancel?.cancelled == true) return LanResult.Cancelled
            val opened = URL(allowed.baseUrl + path).openConnection() as HttpURLConnection
            connection = opened
            cancel?.onCancel { opened.disconnect() }
            opened.instanceFollowRedirects = false
            opened.connectTimeout = connectTimeoutMs
            opened.readTimeout = minOf(readTimeoutMs.toLong(), totalDeadlineMs).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            opened.useCaches = false
            opened.setRequestProperty("Accept-Encoding", "identity")
            if (body != null) {
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                opened.requestMethod = "POST"
                opened.doOutput = true
                opened.setRequestProperty("Content-Type", "application/json")
                opened.setFixedLengthStreamingMode(bytes.size)
                opened.outputStream.use { it.write(bytes) }
            }
            val code = opened.responseCode
            if (code in 300..399) return LanResult.Redirected
            if (code !in 200..299) {
                val detail = opened.errorStream?.use { readLimited(it, MAX_ERROR_BYTES, cancel, deadline) } as? LanResult.Ok
                return LanResult.HttpError(code, errorDetail(detail?.text))
            }
            return opened.inputStream.use { reader(it, deadline) }
        } catch (e: IOException) {
            return if (cancel?.cancelled == true) LanResult.Cancelled else LanResult.NetworkFailure
        } catch (e: ClassCastException) {
            return LanResult.NetworkFailure
        } catch (e: RuntimeException) {
            return LanResult.NetworkFailure
        } finally {
            cancel?.onCancel(null)
            connection?.disconnect()
        }
    }

    private fun errorDetail(text: String?): String {
        if (text == null) return ""
        val parsed = StrictJson.parseObject(text, MAX_ERROR_BYTES)?.str("error") ?: return ""
        return ForeignText.clean(parsed, 200)
    }

    private fun readLimited(input: InputStream, maxBytes: Int, cancel: CancelToken?, deadline: Long): LanResult {
        val out = ByteArrayOutputStream(minOf(maxBytes, 16_384).coerceAtLeast(16))
        val buffer = ByteArray(16_384)
        var total = 0
        while (true) {
            if (cancel?.cancelled == true) return LanResult.Cancelled
            if (clock() >= deadline) return LanResult.TimedOut
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes || total < 0) return LanResult.TooLarge
            out.write(buffer, 0, count)
        }
        return LanResult.Ok(out.toByteArray())
    }

    private fun readLines(
        input: InputStream,
        maxLineBytes: Int,
        maxTotalBytes: Int,
        cancel: CancelToken?,
        deadline: Long,
        onLine: (String) -> Boolean,
    ): LanResult {
        val line = ByteArrayOutputStream(1024)
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            if (cancel?.cancelled == true) return LanResult.Cancelled
            if (clock() >= deadline) return LanResult.TimedOut
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxTotalBytes) return LanResult.TooLarge
            for (index in 0 until count) {
                val value = buffer[index]
                if (value == NEWLINE) {
                    if (line.size() > 0) {
                        val keepGoing = onLine(String(line.toByteArray(), StandardCharsets.UTF_8))
                        line.reset()
                        if (!keepGoing) return LanResult.Ok(ByteArray(0))
                    }
                } else {
                    if (line.size() >= maxLineBytes) return LanResult.TooLarge
                    line.write(value.toInt())
                }
            }
        }
        if (line.size() > 0) onLine(String(line.toByteArray(), StandardCharsets.UTF_8))
        return LanResult.Ok(ByteArray(0))
    }

    private companion object {
        const val MAX_ERROR_BYTES = 8192
        const val NEWLINE = '\n'.code.toByte()
    }
}

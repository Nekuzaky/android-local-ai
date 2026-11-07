package dev.nekuz.localmind

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

class TestServer(private val handler: (Request, Socket) -> Unit) {
    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int = socket.localPort
    val requests = CopyOnWriteArrayList<Request>()

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                thread(isDaemon = true) {
                    try {
                        client.use {
                            val request = read(it.getInputStream())
                            requests.add(request)
                            handler(request, it)
                            it.getOutputStream().flush()
                        }
                    } catch (e: Exception) {
                        requests.size
                    }
                }
            }
        }
    }

    private fun read(input: InputStream): Request {
        val head = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) break
            head.write(value)
            val bytes = head.toByteArray()
            val size = bytes.size
            if (size >= 4 && bytes[size - 4] == 13.toByte() && bytes[size - 3] == 10.toByte() && bytes[size - 2] == 13.toByte() && bytes[size - 1] == 10.toByte()) break
        }
        val lines = String(head.toByteArray()).split("\r\n")
        val first = lines[0].split(" ")
        val headers = lines.drop(1).filter { it.contains(':') }.associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = input.readNBytes(length)
        return Request(first[0], first[1], headers, body)
    }

    fun close() = socket.close()
}

fun Socket.respond(code: Int, headers: Map<String, String> = emptyMap(), body: ByteArray = ByteArray(0), contentLength: Long? = body.size.toLong()) {
    val out = getOutputStream()
    val text = StringBuilder("HTTP/1.1 $code X\r\nConnection: close\r\n")
    if (contentLength != null) text.append("Content-Length: $contentLength\r\n")
    headers.forEach { (key, value) -> text.append("$key: $value\r\n") }
    text.append("\r\n")
    out.write(text.toString().toByteArray())
    out.write(body)
    out.flush()
}

class ResumableDownloadTest {
    private val dir: File = Files.createTempDirectory("lm").toFile()
    private val data = ByteArray(300_000) { (it % 251).toByte() }
    private val sha = Sha256.ofBytes(data)
    private val part = File(dir, "m.gguf.part")
    private var server: TestServer? = null

    @After
    fun stop() {
        server?.close()
    }

    private fun serve(handler: (Request, Socket) -> Unit): String {
        val created = TestServer(handler)
        server = created
        return "http://127.0.0.1:${created.port}/file"
    }

    private val rangeHandler: (Request, Socket) -> Unit = { request, socket ->
        val range = request.headers["range"]
        if (range == null) {
            socket.respond(200, body = data)
        } else {
            val start = range.removePrefix("bytes=").removeSuffix("-").toInt()
            socket.respond(206, mapOf("Content-Range" to "bytes $start-${data.size - 1}/${data.size}"), data.copyOfRange(start, data.size))
        }
    }

    private fun download(
        url: String,
        size: Long = data.size.toLong(),
        hash: String = sha,
        allow: (String) -> Boolean = { true },
        free: Long = Long.MAX_VALUE,
        token: CancelToken = CancelToken(),
        progress: (Long, Long) -> Unit = { _, _ -> },
    ) = ResumableDownload(url, part, size, hash, allow, { free }, token, progress, connectTimeoutMs = 3000, readTimeoutMs = 3000).run()

    @Test
    fun fullDownload() {
        val url = serve(rangeHandler)
        assertEquals(DownloadResult.Done, download(url))
        assertEquals(data.size.toLong(), part.length())
        assertEquals(sha, Sha256.ofFile(part))
        assertNull(server!!.requests[0].headers["range"])
    }

    @Test
    fun resumesFromPartialFile() {
        part.writeBytes(data.copyOfRange(0, 100_000))
        val url = serve(rangeHandler)
        assertEquals(DownloadResult.Done, download(url))
        assertEquals("bytes=100000-", server!!.requests[0].headers["range"])
        assertEquals(sha, Sha256.ofFile(part))
    }

    @Test
    fun serverIgnoringRangeRestartsCleanly() {
        part.writeBytes(data.copyOfRange(0, 100_000))
        val url = serve { _, socket -> socket.respond(200, body = data) }
        assertEquals(DownloadResult.Done, download(url))
        assertEquals(data.size.toLong(), part.length())
        assertEquals(sha, Sha256.ofFile(part))
    }

    @Test
    fun wrongContentRangeRestartsFromZero() {
        part.writeBytes(data.copyOfRange(0, 100_000))
        val calls = AtomicInteger()
        val url = serve { request, socket ->
            calls.incrementAndGet()
            if (request.headers["range"] != null) {
                socket.respond(206, mapOf("Content-Range" to "bytes 5-${data.size - 1}/${data.size}"), data.copyOfRange(5, data.size))
            } else {
                socket.respond(200, body = data)
            }
        }
        assertEquals(DownloadResult.Done, download(url))
        assertEquals(2, calls.get())
        assertEquals(sha, Sha256.ofFile(part))
    }

    @Test
    fun http416RestartsFromZero() {
        part.writeBytes(data.copyOfRange(0, 100_000))
        val url = serve { request, socket ->
            if (request.headers["range"] != null) socket.respond(416) else socket.respond(200, body = data)
        }
        assertEquals(DownloadResult.Done, download(url))
        assertEquals(sha, Sha256.ofFile(part))
    }

    @Test
    fun partLargerThanExpectedIsDiscarded() {
        part.writeBytes(ByteArray(data.size + 10))
        val url = serve(rangeHandler)
        assertEquals(DownloadResult.Done, download(url))
        assertNull(server!!.requests[0].headers["range"])
    }

    @Test
    fun completePartIsOnlyHashed() {
        part.writeBytes(data)
        val url = serve { _, _ -> throw IllegalStateException("should not be called") }
        assertEquals(DownloadResult.Done, download(url))
        assertEquals(0, server!!.requests.size)
    }

    @Test
    fun unwritableTargetIsAStorageFailureNotANetworkOne() {
        val url = serve(rangeHandler)
        val blocked = File(dir, "missing/m.part")
        val result = ResumableDownload(url, blocked, data.size.toLong(), sha, { true }, { Long.MAX_VALUE }, CancelToken(), connectTimeoutMs = 3000, readTimeoutMs = 3000).run()
        assertEquals(DownloadResult.StorageFailure, result)
    }

    @Test
    fun hashMismatchDeletesTheFile() {
        val url = serve(rangeHandler)
        assertEquals(DownloadResult.HashMismatch, download(url, hash = "0".repeat(64)))
        assertFalse(part.exists())
    }

    @Test
    fun corruptResumeIsCaughtByHash() {
        part.writeBytes(ByteArray(100_000) { 7 })
        val url = serve(rangeHandler)
        assertEquals(DownloadResult.HashMismatch, download(url))
        assertFalse(part.exists())
    }

    @Test
    fun oversizedHeaderIsRefused() {
        val url = serve { _, socket -> socket.respond(200, body = ByteArray(data.size + 5)) }
        assertEquals(DownloadResult.SizeMismatch, download(url))
    }

    @Test
    fun endlessBodyWithoutLengthStopsAtExpectedSize() {
        val url = serve { _, socket -> socket.respond(200, body = ByteArray(data.size + 500_000), contentLength = null) }
        assertEquals(DownloadResult.SizeMismatch, download(url))
        assertFalse(part.exists())
    }

    @Test
    fun truncatedBodyKeepsPartForResume() {
        val url = serve { _, socket -> socket.respond(200, body = data.copyOfRange(0, 50_000), contentLength = null) }
        assertEquals(DownloadResult.NetworkFailure, download(url))
        assertEquals(50_000L, part.length())
    }

    @Test
    fun noSpaceIsRefusedBeforeAnyRequest() {
        val url = serve(rangeHandler)
        val result = download(url, free = 10)
        assertTrue(result is DownloadResult.NoSpace)
        assertEquals(0, server!!.requests.size)
    }

    @Test
    fun spaceCheckCountsThePartAlreadyDownloaded() {
        part.writeBytes(data.copyOfRange(0, 100_000))
        val url = serve(rangeHandler)
        val needed = data.size - 100_000L + ResumableDownload.SPACE_MARGIN
        assertEquals(DownloadResult.Done, download(url, free = needed))
    }

    @Test
    fun refusedUrlMakesNoRequest() {
        val url = serve(rangeHandler)
        assertEquals(DownloadResult.UrlRefused, download(url, allow = { false }))
        assertEquals(0, server!!.requests.size)
    }

    @Test
    fun redirectIsFollowedOnlyWhenAllowed() {
        val target = TestServer(rangeHandler)
        try {
            val targetUrl = "http://127.0.0.1:${target.port}/real"
            val url = serve { _, socket -> socket.respond(302, mapOf("Location" to targetUrl)) }
            assertEquals(DownloadResult.Done, download(url))
            part.delete()
            assertEquals(DownloadResult.UrlRefused, download(url, allow = { it == url }))
            assertFalse(part.exists())
        } finally {
            target.close()
        }
    }

    @Test
    fun relativeRedirectIsResolved() {
        val url = serve { request, socket ->
            if (request.path == "/file") socket.respond(302, mapOf("Location" to "/other")) else socket.respond(200, body = data)
        }
        assertEquals(DownloadResult.Done, download(url))
    }

    @Test
    fun redirectLoopStops() {
        val url = serve { _, socket -> socket.respond(302, mapOf("Location" to "/file")) }
        assertEquals(DownloadResult.UrlRefused, download(url))
        assertTrue(server!!.requests.size <= 10)
    }

    @Test
    fun redirectWithoutLocationStops() {
        val url = serve { _, socket -> socket.respond(302) }
        assertEquals(DownloadResult.UrlRefused, download(url))
    }

    @Test
    fun hostileLocationHeaderStops() {
        val url = serve { _, socket -> socket.respond(302, mapOf("Location" to "ht!tp://bad url")) }
        assertEquals(DownloadResult.UrlRefused, download(url))
    }

    @Test
    fun httpErrorIsReported() {
        val url = serve { _, socket -> socket.respond(500) }
        assertEquals(DownloadResult.HttpFailure(500), download(url))
    }

    @Test
    fun deadServerIsNetworkFailure() {
        val free = ServerSocket(0).use { it.localPort }
        assertEquals(DownloadResult.NetworkFailure, download("http://127.0.0.1:$free/x"))
    }

    @Test
    fun cancelBeforeStart() {
        val url = serve(rangeHandler)
        val token = CancelToken().also { it.cancel() }
        assertEquals(DownloadResult.Cancelled, download(url, token = token))
    }

    @Test
    fun cancelMidwayKeepsPartThenResumeCompletes() {
        val url = serve(rangeHandler)
        val token = CancelToken()
        var seen = 0L
        val result = download(url, token = token, progress = { done, _ ->
            seen = done
            if (done > 100_000) token.cancel()
        })
        assertEquals(DownloadResult.Cancelled, result)
        assertTrue(seen > 100_000)
        assertTrue(part.length() in 1 until data.size.toLong())
        assertEquals(DownloadResult.Done, download(url))
        assertEquals(sha, Sha256.ofFile(part))
    }

    @Test
    fun badParametersAreRefused() {
        assertEquals(DownloadResult.SizeMismatch, download("http://127.0.0.1:1/", size = 0))
        assertEquals(DownloadResult.SizeMismatch, download("http://127.0.0.1:1/", hash = "nothex"))
    }

    @Test
    fun progressIsMonotonicAndComplete() {
        val url = serve(rangeHandler)
        val seen = ArrayList<Long>()
        assertEquals(DownloadResult.Done, download(url, progress = { done, _ -> seen.add(done) }))
        assertEquals(seen.sorted(), seen)
        assertEquals(data.size.toLong(), seen.last())
    }
}

class LanHttpTest {
    private var server: TestServer? = null
    private val http = LanHttp(connectTimeoutMs = 2000, readTimeoutMs = 2000)

    @After
    fun stop() {
        server?.close()
    }

    private fun serve(handler: (Request, Socket) -> Unit): Endpoint {
        val created = TestServer(handler)
        server = created
        return Endpoint("127.0.0.1", created.port)
    }

    @Test
    fun slowDripHitsTheTotalDeadline() {
        val endpoint = serve { _, socket ->
            val out = socket.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            try {
                repeat(100) {
                    out.write('x'.code)
                    out.flush()
                    Thread.sleep(50)
                }
            } catch (e: java.io.IOException) {
                out.hashCode()
            }
        }
        val slow = LanHttp(connectTimeoutMs = 2000, readTimeoutMs = 2000, totalDeadlineMs = 300)
        val started = System.currentTimeMillis()
        assertEquals(LanResult.TimedOut, slow.get(endpoint, "/x", 100_000))
        assertTrue(System.currentTimeMillis() - started < 3000)
    }

    @Test
    fun deadlineUsesTheInjectedClock() {
        val endpoint = serve { _, socket -> socket.respond(200, body = "ok".toByteArray()) }
        var now = 0L
        val frozen = LanHttp(connectTimeoutMs = 2000, readTimeoutMs = 2000, totalDeadlineMs = 10, clock = { now.also { now += 100 } })
        assertEquals(LanResult.TimedOut, frozen.get(endpoint, "/x", 100))
    }

    @Test
    fun publicAddressIsRefusedBeforeConnecting() {
        val outside = LanHttp(resolver = { listOf(byteArrayOf(8, 8, 8, 8)) })
        val result = outside.get(Endpoint("evil.example", 80), "/", 100)
        assertTrue(result is LanResult.Refused)
    }

    @Test
    fun rebindingNameIsRefusedWhenAnyAddressIsPublic() {
        val outside = LanHttp(resolver = { listOf(byteArrayOf(192.toByte(), 168.toByte(), 1, 1), byteArrayOf(8, 8, 8, 8)) })
        assertTrue(outside.get(Endpoint("pc.lan", 80), "/", 100) is LanResult.Refused)
    }

    @Test
    fun getWorksOnLoopback() {
        val endpoint = serve { _, socket -> socket.respond(200, body = "hello".toByteArray()) }
        assertEquals("hello", (http.get(endpoint, "/x", 100) as LanResult.Ok).text)
    }

    @Test
    fun responseIsBounded() {
        val endpoint = serve { _, socket -> socket.respond(200, body = ByteArray(1000)) }
        assertEquals(LanResult.TooLarge, http.get(endpoint, "/x", 100))
    }

    @Test
    fun redirectsAreNeverFollowed() {
        val hits = AtomicInteger()
        val endpoint = serve { _, socket ->
            hits.incrementAndGet()
            socket.respond(302, mapOf("Location" to "http://8.8.8.8/stolen"))
        }
        assertEquals(LanResult.Redirected, http.get(endpoint, "/x", 100))
        assertEquals(LanResult.Redirected, http.postJson(endpoint, "/x", "{}", 100))
        assertEquals(2, hits.get())
    }

    @Test
    fun httpErrorsCarryOnlyACleanedDetail() {
        val endpoint = serve { _, socket -> socket.respond(500, body = """{"error":"bad‮ thing"}""".toByteArray()) }
        val result = http.get(endpoint, "/x", 100) as LanResult.HttpError
        assertEquals(500, result.code)
        assertEquals("bad thing", result.detail)
    }

    @Test
    fun badPathsAreRefused() {
        val endpoint = serve { _, socket -> socket.respond(200) }
        listOf("x", "//evil.com/", "/a b", "/a\r\nHost: evil", "/é").forEach {
            assertEquals(it, LanResult.Malformed, http.get(endpoint, it, 100))
        }
        assertEquals(0, server!!.requests.size)
    }

    @Test
    fun deadServerFails() {
        val free = ServerSocket(0).use { it.localPort }
        assertEquals(LanResult.NetworkFailure, http.get(Endpoint("127.0.0.1", free), "/", 100))
    }

    @Test
    fun cancelledBeforeStart() {
        val endpoint = serve { _, socket -> socket.respond(200) }
        assertEquals(LanResult.Cancelled, http.get(endpoint, "/", 100, CancelToken().also { it.cancel() }))
    }

    @Test
    fun linesAreBounded() {
        val endpoint = serve { _, socket -> socket.respond(200, body = ByteArray(5000) { 'x'.code.toByte() }) }
        assertEquals(LanResult.TooLarge, http.postLines(endpoint, "/", "{}", 100, 100_000, null) { true })
        val total = http.postLines(endpoint, "/", "{}", 100_000, 100, null) { true }
        assertEquals(LanResult.TooLarge, total)
    }

    @Test
    fun messagesExistForEveryResult() {
        listOf(
            LanResult.Ok(ByteArray(0)), LanResult.Refused("no"), LanResult.HttpError(404, ""), LanResult.HttpError(500, "x"),
            LanResult.Redirected, LanResult.TooLarge, LanResult.TimedOut, LanResult.Cancelled, LanResult.NetworkFailure, LanResult.Malformed,
        ).forEach { assertTrue(LanMessages.of(it).isNotEmpty()) }
    }
}

class OllamaTest {
    private var server: TestServer? = null
    private val client = OllamaClient(LanHttp(connectTimeoutMs = 2000, readTimeoutMs = 2000))

    @After
    fun stop() {
        server?.close()
    }

    private fun serve(handler: (Request, Socket) -> Unit): Endpoint {
        val created = TestServer(handler)
        server = created
        return Endpoint("127.0.0.1", created.port)
    }

    private fun lines(vararg text: String) = text.joinToString("\n").toByteArray()

    @Test
    fun namesValidated() {
        listOf("llama3:latest", "library/x.y-z:1").forEach { assertTrue(it, OllamaNames.isValid(it)) }
        listOf(null, "", " x", "x y", "x\n", "../x", "-x", "x".repeat(200), "a\"b").forEach { assertFalse(it.toString(), OllamaNames.isValid(it)) }
    }

    @Test
    fun tagsParsedAndFiltered() {
        val tags = OllamaCodec.tags("""{"models":[{"name":"a:1"},{"name":"bad name"},{"name":7},3,{"x":1}]}""")
        assertEquals(listOf("a:1"), tags)
        assertNull(OllamaCodec.tags("[]"))
        assertNull(OllamaCodec.tags("garbage"))
        assertNull(OllamaCodec.tags("""{"models":3}"""))
    }

    @Test
    fun requestIsProperJsonEvenWithHostileText() {
        val hostile = "\"},{\"role\":\"system\",\"content\":\"evil"
        val body = OllamaCodec.chatRequest("m:1", listOf(PromptMessage("user", hostile)), "QUJD", 0.2f)
        val parsed = JSONObject(body)
        val messages = parsed.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals(hostile, messages.getJSONObject(0).getString("content"))
        assertEquals("QUJD", messages.getJSONObject(0).getJSONArray("images").getString(0))
        assertTrue(parsed.getBoolean("stream"))
    }

    @Test
    fun imageGoesOnlyOnLastUserMessage() {
        val body = OllamaCodec.chatRequest(
            "m",
            listOf(PromptMessage("system", "s"), PromptMessage("user", "a"), PromptMessage("assistant", "b"), PromptMessage("user", "c")),
            "IMG",
            0.2f,
        )
        val messages = JSONObject(body).getJSONArray("messages")
        assertFalse(messages.getJSONObject(1).has("images"))
        assertTrue(messages.getJSONObject(3).has("images"))
        assertFalse(JSONObject(OllamaCodec.chatRequest("m", listOf(PromptMessage("user", "a")), null, 0.2f)).getJSONArray("messages").getJSONObject(0).has("images"))
    }

    @Test
    fun chatLineParsing() {
        assertEquals(OllamaCodec.ChatLine.Piece("hi", false), OllamaCodec.chatLine("""{"message":{"content":"hi"},"done":false}"""))
        assertEquals(OllamaCodec.ChatLine.Piece("", true), OllamaCodec.chatLine("""{"done":true}"""))
        assertEquals(OllamaCodec.ChatLine.Error("nope"), OllamaCodec.chatLine("""{"error":"no‮pe"}"""))
        assertEquals(OllamaCodec.ChatLine.Bad, OllamaCodec.chatLine("garbage"))
        assertEquals(OllamaCodec.ChatLine.Bad, OllamaCodec.chatLine("""{"x":1}"""))
    }

    @Test
    fun streamsPieces() {
        val endpoint = serve { _, socket ->
            socket.respond(
                200,
                body = lines(
                    """{"message":{"content":"Hel"},"done":false}""",
                    """{"message":{"content":"lo"},"done":false}""",
                    """{"message":{"content":""},"done":true}""",
                ),
                contentLength = null,
            )
        }
        val out = StringBuilder()
        val result = client.chat(endpoint, "m", listOf(PromptMessage("user", "x")), null, 0.5f, CancelToken()) { out.append(it) }
        assertTrue(result is PcOutcome.Success)
        assertEquals("Hello", out.toString())
    }

    @Test
    fun streamWithoutDoneIsAFailure() {
        val endpoint = serve { _, socket ->
            socket.respond(200, body = lines("""{"message":{"content":"Hel"},"done":false}"""), contentLength = null)
        }
        val result = client.chat(endpoint, "m", listOf(PromptMessage("user", "x")), null, 0.5f, CancelToken()) {}
        assertTrue(result is PcOutcome.Failure)
    }

    @Test
    fun errorLineAndGarbageStop() {
        val endpoint = serve { _, socket -> socket.respond(200, body = lines("""{"error":"model gone"}""", "more"), contentLength = null) }
        val result = client.chat(endpoint, "m", listOf(PromptMessage("user", "x")), null, 0.5f, CancelToken()) {}
        assertEquals("model gone", (result as PcOutcome.Failure).message)
    }

    @Test
    fun badModelNameMakesNoRequest() {
        val endpoint = serve { _, socket -> socket.respond(200) }
        assertTrue(client.chat(endpoint, "bad name", emptyList(), null, 0.5f, CancelToken()) {} is PcOutcome.Failure)
        assertEquals(0, server!!.requests.size)
    }

    @Test
    fun modelsEndToEnd() {
        val endpoint = serve { _, socket -> socket.respond(200, body = """{"models":[{"name":"a:1"}]}""".toByteArray()) }
        assertEquals(listOf("a:1"), (client.models(endpoint) as PcOutcome.Success).value)
        assertEquals("/api/tags", server!!.requests[0].path)
    }
}

class ComfyTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
    private var server: TestServer? = null
    private val http = LanHttp(connectTimeoutMs = 2000, readTimeoutMs = 2000)
    private val client = ComfyClient(http, pollIntervalMs = 1, maxPolls = 5, sleep = {})

    private fun params(prompt: String = "a cat") = ComfyParams(prompt, "", "model.safetensors", 512, 512, 20, 7.0, 42)

    @After
    fun stop() {
        server?.close()
    }

    private fun serve(handler: (Request, Socket) -> Unit): Endpoint {
        val created = TestServer(handler)
        server = created
        return Endpoint("127.0.0.1", created.port)
    }

    @Test
    fun workflowIsWiredCorrectly() {
        val flow = ComfyWorkflow.build(params())
        assertEquals("CheckpointLoaderSimple", flow.getJSONObject("4").getString("class_type"))
        assertEquals("model.safetensors", flow.getJSONObject("4").getJSONObject("inputs").getString("ckpt_name"))
        assertEquals("a cat", flow.getJSONObject("6").getJSONObject("inputs").getString("text"))
        assertEquals("6", flow.getJSONObject("3").getJSONObject("inputs").getJSONArray("positive").getString(0))
        assertEquals("7", flow.getJSONObject("3").getJSONObject("inputs").getJSONArray("negative").getString(0))
        assertEquals(42L, flow.getJSONObject("3").getJSONObject("inputs").getLong("seed"))
        assertEquals("SaveImage", flow.getJSONObject("9").getString("class_type"))
        assertEquals(7, flow.length())
    }

    @Test
    fun hostilePromptCannotAddNodes() {
        val hostile = "\"}},\"99\":{\"class_type\":\"Exec\",\"inputs\":{\"cmd\":\"rm -rf /\"}},\"x\":{\"a\":\""
        val parsed = JSONObject(ComfyWorkflow.request(params(hostile), "id"))
        val flow = parsed.getJSONObject("prompt")
        assertEquals(7, flow.length())
        assertFalse(flow.has("99"))
        assertEquals(hostile, flow.getJSONObject("6").getJSONObject("inputs").getString("text"))
        assertEquals("id", parsed.getString("client_id"))
    }

    @Test
    fun validationRefusesBadParameters() {
        assertNull(ComfyWorkflow.validate(params()))
        listOf(
            params(" "),
            params("x".repeat(ComfyWorkflow.MAX_PROMPT + 1)),
            params().copy(negative = "x".repeat(ComfyWorkflow.MAX_PROMPT + 1)),
            params().copy(checkpoint = ""),
            params().copy(checkpoint = "x".repeat(300)),
            params().copy(width = 100),
            params().copy(width = 513),
            params().copy(height = 4096),
            params().copy(steps = 0),
            params().copy(steps = 1000),
            params().copy(cfg = Double.NaN),
            params().copy(cfg = 0.0),
            params().copy(seed = -1),
        ).forEach { assertNotNull(it.toString().take(80), ComfyWorkflow.validate(it)) }
    }

    @Test
    fun checkpointsBothShapes() {
        assertEquals(
            listOf("a.safetensors"),
            ComfyCodec.checkpoints("""{"CheckpointLoaderSimple":{"input":{"required":{"ckpt_name":[["a.safetensors","",7]]}}}}"""),
        )
        assertEquals(
            listOf("b.ckpt"),
            ComfyCodec.checkpoints("""{"CheckpointLoaderSimple":{"input":{"required":{"ckpt_name":["COMBO",{"options":["b.ckpt"]}]}}}}"""),
        )
        listOf("garbage", "{}", """{"CheckpointLoaderSimple":7}""", """{"CheckpointLoaderSimple":{"input":{"required":{"ckpt_name":[7]}}}}""").forEach {
            assertNull(it, ComfyCodec.checkpoints(it))
        }
    }

    @Test
    fun promptIdsValidated() {
        assertEquals("abc-1", ComfyCodec.promptId("""{"prompt_id":"abc-1"}"""))
        listOf("""{"prompt_id":"../../x"}""", """{"prompt_id":"a b"}""", """{"prompt_id":7}""", "garbage", "{}").forEach { assertNull(it, ComfyCodec.promptId(it)) }
    }

    @Test
    fun imageNamesValidated() {
        listOf("LocalMind_00001_.png", "a (1).png").forEach { assertTrue(it, ComfyCodec.isSafeName(it)) }
        listOf("../x.png", "a/b.png", "a\\b.png", "..", ".hidden", "a%2fb", "a\nb", "", "x".repeat(300)).forEach { assertFalse(it, ComfyCodec.isSafeName(it)) }
    }

    @Test
    fun statusParsing() {
        val id = "p1"
        assertEquals(ComfyStatus.Pending, ComfyCodec.status("{}", id))
        assertNull(ComfyCodec.status("garbage", id))
        assertTrue(ComfyCodec.status("""{"p1":{"status":{"status_str":"error"}}}""", id) is ComfyStatus.Failed)
        assertEquals(ComfyStatus.Pending, ComfyCodec.status("""{"p1":{"status":{"status_str":"running"}}}""", id))
        val ready = ComfyCodec.status("""{"p1":{"outputs":{"9":{"images":[{"filename":"a.png","subfolder":"","type":"output"}]}}}}""", id)
        assertEquals(ComfyStatus.Ready(ComfyImageRef("a.png", "", "output")), ready)
    }

    @Test
    fun traversalInHistoryIsNotRequested() {
        val hostile = listOf(
            """{"filename":"../../secret.png","subfolder":"","type":"output"}""",
            """{"filename":"a.png","subfolder":"../..","type":"output"}""",
            """{"filename":"a.png","subfolder":"","type":"input"}""",
            """{"filename":"a.png","subfolder":"","type":"../x"}""",
            """{"filename":7,"subfolder":"","type":"output"}""",
        )
        hostile.forEach {
            val text = """{"p1":{"status":{"completed":true},"outputs":{"9":{"images":[$it]}}}}"""
            assertTrue(it, ComfyCodec.status(text, "p1") is ComfyStatus.Failed)
        }
    }

    @Test
    fun viewPathIsEncoded() {
        val path = ComfyCodec.viewPath(ComfyImageRef("a (1).png", "sub dir", "output"))
        assertEquals("/view?filename=a+%281%29.png&subfolder=sub+dir&type=output", path)
    }

    @Test
    fun oversizedHistoryIsRefusedWithoutParsing() {
        val flood = "[" + "0,".repeat(ComfyClient.MAX_JSON_BYTES) + "0]"
        assertNull(ComfyCodec.status(flood, "p1"))
        assertNull(ComfyCodec.checkpoints(flood))
    }

    @Test
    fun endToEnd() {
        val endpoint = serve { request, socket ->
            when {
                request.path == "/prompt" -> socket.respond(200, body = """{"prompt_id":"p1"}""".toByteArray())
                request.path == "/history/p1" -> socket.respond(
                    200,
                    body = """{"p1":{"outputs":{"9":{"images":[{"filename":"a.png","subfolder":"","type":"output"}]}}}}""".toByteArray(),
                )
                request.path.startsWith("/view") -> socket.respond(200, body = png)
                else -> socket.respond(404)
            }
        }
        val outcome = client.generate(endpoint, params(), "client", CancelToken())
        assertEquals(ImageKind.PNG, (outcome as PcOutcome.Success).value.kind)
        val submitted = JSONObject(String(server!!.requests[0].body))
        assertEquals("a cat", submitted.getJSONObject("prompt").getJSONObject("6").getJSONObject("inputs").getString("text"))
    }

    @Test
    fun nonImageBytesAreRefused() {
        val endpoint = serve { request, socket ->
            when {
                request.path == "/prompt" -> socket.respond(200, body = """{"prompt_id":"p1"}""".toByteArray())
                request.path == "/history/p1" -> socket.respond(
                    200,
                    body = """{"p1":{"outputs":{"9":{"images":[{"filename":"a.png","subfolder":"","type":"output"}]}}}}""".toByteArray(),
                )
                else -> socket.respond(200, body = "<html>".toByteArray())
            }
        }
        assertTrue(client.generate(endpoint, params(), "client", CancelToken()) is PcOutcome.Failure)
    }

    @Test
    fun pollingGivesUp() {
        val endpoint = serve { request, socket ->
            if (request.path == "/prompt") socket.respond(200, body = """{"prompt_id":"p1"}""".toByteArray()) else socket.respond(200, body = "{}".toByteArray())
        }
        assertTrue(client.generate(endpoint, params(), "client", CancelToken()) is PcOutcome.Failure)
        assertTrue(server!!.requests.size <= 6)
    }

    @Test
    fun invalidParametersMakeNoRequest() {
        val endpoint = serve { _, socket -> socket.respond(200) }
        assertTrue(client.generate(endpoint, params(" "), "client", CancelToken()) is PcOutcome.Failure)
        assertEquals(0, server!!.requests.size)
    }

    @Test
    fun redirectAtSubmitStops() {
        val endpoint = serve { _, socket -> socket.respond(302, mapOf("Location" to "http://8.8.8.8/")) }
        assertTrue(client.generate(endpoint, params(), "client", CancelToken()) is PcOutcome.Failure)
        assertEquals(1, server!!.requests.size)
    }
}

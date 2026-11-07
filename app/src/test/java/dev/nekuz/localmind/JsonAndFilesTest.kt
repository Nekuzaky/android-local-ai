package dev.nekuz.localmind

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class StrictJsonTest {
    @Test
    fun parsesValues() {
        val root = StrictJson.parseObject("""{"a":1,"b":"x\n\u0041","c":[true,false,null],"d":{"e":-1.5e2}}""")!!
        assertEquals(1L, root.long("a"))
        assertEquals("x\nA", root.str("b"))
        assertEquals(3, root.arr("c")!!.size)
        assertEquals(-150.0, root.obj("d")!!.double("e")!!, 0.0)
    }

    @Test
    fun refusesMalformed() {
        listOf(
            "", " ", "{", "}", "[", "]", "{]", "[}", "{\"a\"}", "{\"a\":}", "{\"a\":1,}", "[1,]", "[,1]", "{a:1}", "{'a':1}", "'x'",
            "01", "1.", ".5", "+1", "1e", "1e+", "--1", "NaN", "Infinity", "-Infinity", "0x10", "tru", "truee", "nul", "\"abc",
            "\"a\nb\"", "\"\\x\"", "\"\\u12\"", "\"\\uZZZZ\"", "{} {}", "1 2", "//x\n1", "/*x*/1", "[1 2]", "{\"a\":1 \"b\":2}", "\u0000",
            "1e999", "-1e999", "\"\\",
        ).forEach { assertNull(it, StrictJson.parse(it)) }
    }

    @Test
    fun refusesDuplicateKeys() {
        assertNull(StrictJson.parse("""{"a":1,"a":2}"""))
        assertNull(StrictJson.parse("""{"a":{"b":1,"b":1}}"""))
    }

    @Test
    fun boundsDepth() {
        assertNotNull(StrictJson.parse("[".repeat(32) + "]".repeat(32)))
        assertNull(StrictJson.parse("[".repeat(33) + "]".repeat(33)))
        assertNull(StrictJson.parse("[".repeat(500_000)))
        assertNull(StrictJson.parse("{\"a\":".repeat(200_000)))
    }

    @Test
    fun boundsNodeCount() {
        val many = "[" + "0,".repeat(200_001) + "0]"
        assertNull(StrictJson.parse(many))
        assertNotNull(StrictJson.parse("[" + "0,".repeat(1000) + "0]"))
    }

    @Test
    fun boundsSize() {
        assertNull(StrictJson.parse("\"" + "x".repeat(100) + "\"", maxChars = 50))
        assertNull(StrictJson.parse(null))
        assertNull(StrictJson.parse("1" + "0".repeat(100)))
    }

    @Test
    fun integerHandling() {
        val root = StrictJson.parseObject("""{"a":9007199254740993,"b":1.5,"c":2.0,"d":1e400}""")
        assertNull(root)
        val ok = StrictJson.parseObject("""{"a":9007199254740993,"b":1.5,"c":2.0,"e":1e30}""")!!
        assertEquals(9007199254740993L, ok.long("a"))
        assertNull(ok.long("b"))
        assertEquals(2L, ok.long("c"))
        assertNull(ok.long("e"))
    }

    @Test
    fun wrongTypesReturnNull() {
        val root = StrictJson.parseObject("""{"a":1,"b":"x"}""")!!
        assertNull(root.str("a"))
        assertNull(root.long("b"))
        assertNull(root.arr("a"))
        assertNull(root.obj("a"))
        assertNull(root.bool("a"))
        assertNull(root.str("missing"))
    }
}

class AtomicFilesTest {
    private val dir: File = Files.createTempDirectory("lm").toFile()

    @Test
    fun writesAndReads() {
        val file = File(dir, "x.json")
        assertTrue(AtomicFiles.write(file, "héllo"))
        assertEquals("héllo", AtomicFiles.readText(file, 100))
        assertTrue(AtomicFiles.write(file, "second"))
        assertEquals("second", AtomicFiles.readText(file, 100))
        assertEquals(listOf("x.json"), dir.list()!!.toList())
    }

    @Test
    fun createsParentAndLeavesNoTemp() {
        val file = File(File(dir, "a/b"), "y")
        assertTrue(AtomicFiles.write(file, "1"))
        assertEquals(1, file.parentFile!!.list()!!.size)
    }

    @Test
    fun readRefusesOversizeAndMissing() {
        val file = File(dir, "big")
        assertTrue(AtomicFiles.write(file, "x".repeat(101)))
        assertNull(AtomicFiles.readText(file, 100))
        assertNull(AtomicFiles.readText(File(dir, "missing"), 100))
        assertNull(AtomicFiles.readText(dir, 100))
    }

    @Test
    fun boundedRead() {
        assertEquals(10, Bounded.readBytes(ByteArrayInputStream(ByteArray(10)), 10)!!.size)
        assertNull(Bounded.readBytes(ByteArrayInputStream(ByteArray(11)), 10))
        assertEquals(0, Bounded.readBytes(ByteArrayInputStream(ByteArray(0)), 10)!!.size)
    }
}

class Sha256Test {
    private val dir: File = Files.createTempDirectory("lm").toFile()

    @Test
    fun knownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.ofBytes("abc".toByteArray()))
        val file = File(dir, "f").also { it.writeBytes("abc".toByteArray()) }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.ofFile(file))
    }

    @Test
    fun missingFileIsNull() {
        assertNull(Sha256.ofFile(File(dir, "missing")))
    }

    @Test
    fun cancelledHashIsNull() {
        val file = File(dir, "f").also { it.writeBytes(ByteArray(1024)) }
        assertNull(Sha256.ofFile(file, CancelToken().also { it.cancel() }))
    }

    @Test
    fun validatesHex() {
        assertTrue(Sha256.isValidHex("a".repeat(64)))
        assertFalse(Sha256.isValidHex("A".repeat(64)))
        assertFalse(Sha256.isValidHex("a".repeat(63)))
        assertFalse(Sha256.isValidHex("g".repeat(64)))
        assertFalse(Sha256.isValidHex(null))
    }
}

class CancelTokenTest {
    @Test
    fun hookRunsOffTheCallingThread() {
        val caller = Thread.currentThread()
        val ran = java.util.concurrent.CountDownLatch(1)
        var thread: Thread? = null
        val token = CancelToken()
        token.onCancel {
            thread = Thread.currentThread()
            ran.countDown()
        }
        token.cancel()
        assertTrue(token.cancelled)
        assertTrue(ran.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(thread !== caller)
    }

    @Test
    fun throwingHookIsHarmless() {
        val token = CancelToken()
        token.onCancel { throw IllegalStateException("x") }
        token.cancel()
        assertTrue(token.cancelled)
    }

    @Test
    fun hookSetAfterCancelRunsAtOnce() {
        var ran = false
        val token = CancelToken().also { it.cancel() }
        token.onCancel { ran = true }
        assertTrue(ran)
        token.onCancel { throw IllegalStateException("x") }
    }
}

class Utf8StreamTest {
    @Test
    fun joinsSplitSequences() {
        val bytes = "aé€😀z".toByteArray(StandardCharsets.UTF_8)
        for (cut in 0..bytes.size) {
            val stream = Utf8Stream()
            val out = stream.feed(bytes.copyOfRange(0, cut)) + stream.feed(bytes.copyOfRange(cut, bytes.size)) + stream.finish()
            assertEquals("aé€😀z", out)
        }
    }

    @Test
    fun oneByteAtATime() {
        val stream = Utf8Stream()
        val out = StringBuilder()
        for (b in "日本語😀".toByteArray(StandardCharsets.UTF_8)) out.append(stream.feed(byteArrayOf(b)))
        out.append(stream.finish())
        assertEquals("日本語😀", out.toString())
    }

    @Test
    fun invalidBytesDoNotThrowOrGrow() {
        val stream = Utf8Stream()
        val out = stream.feed(byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0x80.toByte(), 0xF0.toByte())) + stream.finish()
        assertTrue(out.isNotEmpty())
        repeat(1000) { stream.feed(byteArrayOf(0x80.toByte())) }
        assertTrue(stream.finish().length < 16)
    }
}

class GgufHeaderTest {
    private fun header(version: Int = 3, tensors: Long = 10, keys: Long = 5): ByteArray {
        val out = ByteArray(24)
        "GGUF".toByteArray().copyInto(out)
        for (i in 0 until 4) out[4 + i] = (version ushr (8 * i)).toByte()
        for (i in 0 until 8) out[8 + i] = (tensors ushr (8 * i)).toByte()
        for (i in 0 until 8) out[16 + i] = (keys ushr (8 * i)).toByte()
        return out
    }

    @Test
    fun acceptsValid() {
        assertEquals(GgufCheck.OK, GgufHeader.check(header()))
        assertEquals(GgufCheck.OK, GgufHeader.check(header(version = 2)))
    }

    @Test
    fun refusesBad() {
        assertEquals(GgufCheck.TOO_SHORT, GgufHeader.check(ByteArray(3)))
        assertEquals(GgufCheck.BAD_MAGIC, GgufHeader.check(ByteArray(24)))
        assertEquals(GgufCheck.BAD_VERSION, GgufHeader.check(header(version = 1)))
        assertEquals(GgufCheck.BAD_VERSION, GgufHeader.check(header(version = -1)))
        assertEquals(GgufCheck.ABSURD_COUNTS, GgufHeader.check(header(tensors = -1)))
        assertEquals(GgufCheck.ABSURD_COUNTS, GgufHeader.check(header(keys = Long.MAX_VALUE)))
    }
}

class MemoryGateTest {
    private val gb = 1024L * 1024 * 1024

    @Test
    fun fitsWhenEnough() {
        assertTrue(MemoryGate.check(gb, 4096, 3 * gb) is MemoryCheck.Fits)
    }

    @Test
    fun refusesWhenTooLittle() {
        val check = MemoryGate.check(2 * gb, 4096, 2 * gb)
        assertTrue(check is MemoryCheck.TooBig)
        assertEquals(2 * gb, (check as MemoryCheck.TooBig).availableBytes)
    }

    @Test
    fun catalogueFitsATypicalFreeAmountOnTheTargetPhone() {
        Catalogue.models.forEach { assertTrue(it.name, MemoryGate.check(it.sizeBytes, MemoryGate.CONTEXT_TOKENS, 3 * gb) is MemoryCheck.Fits) }
    }

    @Test
    fun negativeInputIsSafe() {
        assertTrue(MemoryGate.neededBytes(-5, -5) > 0)
        assertTrue(MemoryGate.check(gb, 4096, -1) is MemoryCheck.TooBig)
    }
}

class CpuFeaturesTest {
    @Test
    fun targetPhoneFeatures() {
        val info = "processor\t: 0\nFeatures\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp\n"
        assertTrue(CpuFeatures.missing(info).isEmpty())
    }

    @Test
    fun oldCoreIsReported() {
        assertEquals(listOf("asimddp", "asimdhp"), CpuFeatures.missing("Features : fp asimd aes\n"))
    }

    @Test
    fun anyCoreMissingCounts() {
        val info = "Features : asimddp asimdhp\nFeatures : asimd\n"
        assertEquals(2, CpuFeatures.missing(info).size)
    }

    @Test
    fun unreadableIsNotAFalseRefusal() {
        assertTrue(CpuFeatures.missing(null).isEmpty())
        assertTrue(CpuFeatures.missing("garbage").isEmpty())
    }
}

class ModelRegistryTest {
    private val sha = "a".repeat(64)

    private fun imported(file: String = "import-1.gguf") = InstalledModel("import-1", "Name", file, ModelSource.IMPORT, sha, 100)

    @Test
    fun safeNames() {
        assertTrue(ModelFiles.isSafeName("abc.gguf"))
        assertTrue(ModelFiles.isSafeName("import-12ab.gguf"))
        listOf(null, "", "../x.gguf", "a/b.gguf", "a\\b.gguf", "x.gguf.part", ".gguf", "A.gguf", "a..b.gguf", "a b.gguf", "x.bin", "a".repeat(100) + ".gguf", "/etc/passwd").forEach {
            assertFalse(it.toString(), ModelFiles.isSafeName(it))
        }
    }

    @Test
    fun gateChecksImports() {
        assertEquals(ModelGateResult.OK, ModelFiles.gate(imported(), 100, sha))
        assertEquals(ModelGateResult.WRONG_SIZE, ModelFiles.gate(imported(), 99, sha))
        assertEquals(ModelGateResult.WRONG_HASH, ModelFiles.gate(imported(), 100, "b".repeat(64)))
        assertEquals(ModelGateResult.MISSING, ModelFiles.gate(imported(), null, null))
        assertEquals(ModelGateResult.BAD_NAME, ModelFiles.gate(imported("../x.gguf"), 100, sha))
    }

    @Test
    fun gatePinsCatalogueToTheBuiltInHashEvenIfTheRegistryIsTampered() {
        val pinned = Catalogue.models.first()
        val tampered = InstalledModel(pinned.id, pinned.name, pinned.localFile, ModelSource.CATALOGUE, sha, 100)
        assertEquals(ModelGateResult.WRONG_SIZE, ModelFiles.gate(tampered, 100, sha))
        assertEquals(ModelGateResult.WRONG_HASH, ModelFiles.gate(tampered, pinned.sizeBytes, sha))
        assertEquals(ModelGateResult.OK, ModelFiles.gate(tampered, pinned.sizeBytes, pinned.sha256))
        val unknown = tampered.copy(id = "unknown")
        assertEquals(ModelGateResult.NOT_IN_CATALOGUE, ModelFiles.gate(unknown, 100, sha))
    }

    @Test
    fun codecRoundTrip() {
        val list = listOf(imported())
        assertEquals(list, ModelRegistryCodec.decode(ModelRegistryCodec.encode(list)))
    }

    @Test
    fun codecSurvivesCorruption() {
        listOf(null, "", "garbage", "{", "[]", """{"models":7}""", """{"models":[7,null,{}]}""").forEach {
            assertTrue(it.toString(), ModelRegistryCodec.decode(it).isEmpty())
        }
    }

    @Test
    fun codecDropsHostileEntries() {
        val bad = """{"models":[
            {"id":"a","name":"x","file":"../../evil.gguf","source":"IMPORT","sha256":"${sha}","size":1},
            {"id":"b","name":"x","file":"b.gguf","source":"IMPORT","sha256":"nothex","size":1},
            {"id":"c","name":"x","file":"c.gguf","source":"NOPE","sha256":"${sha}","size":1},
            {"id":"d","name":"x","file":"d.gguf","source":"IMPORT","sha256":"${sha}","size":-1},
            {"id":"e","name":"\u202Eevil","file":"e.gguf","source":"IMPORT","sha256":"${sha}","size":1},
            {"id":"e","name":"dup","file":"f.gguf","source":"IMPORT","sha256":"${sha}","size":1}
        ]}"""
        val decoded = ModelRegistryCodec.decode(bad)
        assertEquals(1, decoded.size)
        assertEquals("evil", decoded[0].name)
    }
}

class EnginePolicyTest {
    @Test
    fun onlyImportsRequireIsolation() {
        assertTrue(EnginePolicy.requiresIsolation(ModelSource.IMPORT))
        assertFalse(EnginePolicy.requiresIsolation(ModelSource.CATALOGUE))
    }

    @Test
    fun importIsRefusedOutsideAnIsolatedEngine() {
        assertTrue(EnginePolicy.mustRefuse(requireIsolated = true, engineIsolated = false))
        assertFalse(EnginePolicy.mustRefuse(requireIsolated = true, engineIsolated = true))
        assertFalse(EnginePolicy.mustRefuse(requireIsolated = false, engineIsolated = false))
    }

    @Test
    fun describesWhatHappened() {
        assertEquals("isolated engine, memory-mapped", EnginePolicy.describe(EnginePolicy.flags(true, true)))
        assertEquals("isolated engine, read into memory", EnginePolicy.describe(EnginePolicy.flags(true, false)))
        assertEquals("engine, NOT isolated, read into memory", EnginePolicy.describe(EnginePolicy.flags(false, false)))
    }
}

class OrphanCleanupTest {
    @Test
    fun cleansOnlyWhenRegistryIsAbsentOrParses() {
        assertTrue(ModelRegistryCodec.mayCleanOrphans(false, null))
        assertTrue(ModelRegistryCodec.mayCleanOrphans(true, ModelRegistryCodec.encode(emptyList())))
        assertFalse(ModelRegistryCodec.mayCleanOrphans(true, null))
        assertFalse(ModelRegistryCodec.mayCleanOrphans(true, ""))
        assertFalse(ModelRegistryCodec.mayCleanOrphans(true, "garbage"))
        assertFalse(ModelRegistryCodec.mayCleanOrphans(true, "{}"))
    }
}

class SettingsCodecTest {
    @Test
    fun roundTrip() {
        val settings = AppSettings(true, "192.168.1.2:11434", "m:latest", "192.168.1.2:8188", "c.safetensors", "id", "abc-1", "content://x")
        assertEquals(settings, SettingsCodec.decode(SettingsCodec.encode(settings)))
    }

    @Test
    fun corruptionGivesDefaults() {
        listOf(null, "", "{", "[]", "\u0000").forEach { assertEquals(AppSettings(), SettingsCodec.decode(it)) }
    }

    @Test
    fun hostileFieldsDropped() {
        val out = SettingsCodec.decode("""{"usePc":"yes","ollamaModel":"../x\n","conversationId":"../../x"}""")
        assertFalse(out.usePc)
        assertEquals("", out.ollamaModel)
        assertEquals("", out.conversationId)
    }
}

class ArrayHelpersTest {
    @Test
    fun imageSniff() {
        assertEquals(ImageKind.PNG, ImageSniff.kind(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)))
        assertEquals(ImageKind.JPEG, ImageSniff.kind(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertEquals(ImageKind.WEBP, ImageSniff.kind("RIFFxxxxWEBP".toByteArray()))
        assertNull(ImageSniff.kind("<svg onload=x>".toByteArray()))
        assertNull(ImageSniff.kind(ByteArray(0)))
        assertNull(ImageSniff.kind("RIFF".toByteArray()))
    }

    @Test
    fun comfyImageEquality() {
        assertEquals(ComfyImage(byteArrayOf(1), ImageKind.PNG), ComfyImage(byteArrayOf(1), ImageKind.PNG))
        assertArrayEquals(byteArrayOf(1), ComfyImage(byteArrayOf(1), ImageKind.PNG).bytes)
    }

    @Test
    fun sizes() {
        assertEquals("5 B", Sizes.human(5))
        assertEquals("1 KB", Sizes.human(1024))
        assertEquals("2 MB", Sizes.human(2L * 1024 * 1024))
        assertEquals("1.50 GB", Sizes.human(1024L * 1024 * 1024 * 3 / 2))
        assertEquals("0 B", Sizes.human(-1))
    }
}

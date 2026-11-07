package dev.nekuz.localmind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypedPlanTest {
    private fun parsed(text: String) = (TypedPlan.parse(text) as PlanParse.Parsed).segments

    @Test
    fun exampleFromTheBrief() {
        assertEquals(
            listOf(PlanSegment(1, 10_000, 40_000), PlanSegment(2, 0, null)),
            parsed("keep 0:10 to 0:40 of clip 1, then all of clip 2"),
        )
    }

    @Test
    fun variants() {
        assertEquals(listOf(PlanSegment(3, 0, null)), parsed("clip 3"))
        assertEquals(listOf(PlanSegment(1, 0, null)), parsed("  the whole of clip 1 "))
        assertEquals(listOf(PlanSegment(1, 0, null)), parsed("entire clip 1"))
        assertEquals(listOf(PlanSegment(1, 90_000, 120_000)), parsed("1:30 to 2:00 of clip 1"))
        assertEquals(listOf(PlanSegment(2, 5_000, 7_500)), parsed("clip 2 from 5s to 7.5"))
        assertEquals(listOf(PlanSegment(2, 5_000, null)), parsed("clip 2 from 5"))
        assertEquals(listOf(PlanSegment(2, 5_000, null)), parsed("5 to the end of clip 2"))
        assertEquals(listOf(PlanSegment(2, 5_000, null)), parsed("Clip 2: 5 to end"))
        assertEquals(listOf(PlanSegment(1, 0, 10_000)), parsed("the first 10s of clip 1"))
        assertEquals(listOf(PlanSegment(1, 60_000, 90_000)), parsed("KEEP 1m TO 1m30s OF CLIP 1"))
        assertEquals(listOf(PlanSegment(1, 3_661_000, 3_662_000)), parsed("1:01:01 - 1:01:02 of clip 1"))
    }

    @Test
    fun separators() {
        val expected = listOf(PlanSegment(1, 0, null), PlanSegment(2, 0, null), PlanSegment(3, 0, null))
        assertEquals(expected, parsed("clip 1, clip 2, clip 3"))
        assertEquals(expected, parsed("clip 1 then clip 2 then clip 3"))
        assertEquals(expected, parsed("clip 1; clip 2; clip 3"))
        assertEquals(expected, parsed("clip 1\nclip 2\nclip 3"))
        assertEquals(expected, parsed("clip 1, and then clip 2, then clip 3"))
    }

    @Test
    fun refusesWhatItCannotRead() {
        listOf(
            "", "   ", "make it pretty", "add a blur to clip 1", "clip", "clip one", "clip 0", "clip 21", "clip 999", "clip -1",
            "0:10 to of clip 1", "to 0:40 of clip 1", "0:61 to 1:00 of clip 1", "1:00:61 to 1:00:62 of clip 1", "0:10 to 0:40",
            "delete clip 1", "clip 1; rm -rf /", "clip 1 && clip 2", "{\"segments\":[]}", "<script>", "../clip 1",
            "99999999 to 1 of clip 1",
        ).forEach { assertTrue(it, TypedPlan.parse(it) is PlanParse.Rejected) }
    }

    @Test
    fun boundsLengthAndCount() {
        assertTrue(TypedPlan.parse("clip 1, ".repeat(500)) is PlanParse.Rejected)
        assertTrue(TypedPlan.parse((1..40).joinToString(",") { "clip 1" }) is PlanParse.Rejected)
        assertEquals(PlanLimits.MAX_SEGMENTS, parsed((1..PlanLimits.MAX_SEGMENTS).joinToString(",") { "clip 1" }).size)
    }

    @Test
    fun regexesDoNotBlowUp() {
        val started = System.currentTimeMillis()
        listOf("1".repeat(900), "clip ".repeat(190), "1:".repeat(450), "m".repeat(900), " ".repeat(990) + "x", "0 to ".repeat(190), "then ".repeat(190)).forEach {
            TypedPlan.parse(it)
        }
        assertTrue(System.currentTimeMillis() - started < 3000)
    }

    @Test
    fun times() {
        assertEquals(10_000L, TypedPlan.time("10"))
        assertEquals(10_000L, TypedPlan.time("10s"))
        assertEquals(70_000L, TypedPlan.time("1:10"))
        assertEquals(3_670_000L, TypedPlan.time("1:01:10"))
        assertEquals(1_250L, TypedPlan.time("1.25"))
        assertEquals(1_500L, TypedPlan.time("1.5"))
        assertEquals(90_000L, TypedPlan.time("1m30s"))
        assertEquals(60_000L, TypedPlan.time("1m"))
        listOf("", "abc", "1:2:3:4", "1:60", "1:60:00", "1m60s", "-1", "1e5", "9".repeat(30), "999999999999", "99999999m").forEach {
            assertNull(it, TypedPlan.time(it))
        }
    }
}

class PlanJsonTest {
    private fun parsed(text: String) = (PlanJson.parse(text) as PlanParse.Parsed).segments

    @Test
    fun readsSchema() {
        assertEquals(
            listOf(PlanSegment(1, 10_000, 40_000), PlanSegment(2, 0, null)),
            parsed("""{"segments":[{"clip":1,"start":10,"end":40},{"clip":2}]}"""),
        )
        assertEquals(listOf(PlanSegment(1, 1_500, null)), parsed("""{"segments":[{"clip":1,"start":1.5}]}"""))
        assertEquals(listOf(PlanSegment(1, 0, 2_000)), parsed("""{"segments":[{"clip":1.0,"end":2}]}"""))
    }

    @Test
    fun refusesHostilePlans() {
        listOf(
            "",
            "null",
            "[]",
            "{}",
            """{"segments":null}""",
            """{"segments":{}}""",
            """{"segments":[]}""",
            """{"segments":[1]}""",
            """{"segments":[null]}""",
            """{"segments":[{}]}""",
            """{"segments":[{"clip":"1"}]}""",
            """{"segments":[{"clip":1.5}]}""",
            """{"segments":[{"clip":0}]}""",
            """{"segments":[{"clip":-1}]}""",
            """{"segments":[{"clip":21}]}""",
            """{"segments":[{"clip":1e300}]}""",
            """{"segments":[{"clip":1,"start":-1}]}""",
            """{"segments":[{"clip":1,"start":"0"}]}""",
            """{"segments":[{"clip":1,"start":null}]}""",
            """{"segments":[{"clip":1,"end":null}]}""",
            """{"segments":[{"clip":1,"end":1e308}]}""",
            """{"segments":[{"clip":1,"end":360001}]}""",
            """{"segments":[{"clip":1,"effect":"blur"}]}""",
            """{"segments":[{"clip":1,"path":"/sdcard/secret"}]}""",
            """{"segments":[{"clip":1}],"run":"rm -rf /"}""",
            """{"segments":[{"clip":1}],"segments":[{"clip":2}]}""",
            """{"segments":[{"clip":1,"clip":2}]}""",
            """{"segments":[{"clip":NaN}]}""",
            """{"segments":[{"clip":1,"start":Infinity}]}""",
            """{'segments':[{'clip':1}]}""",
            """{"segments":[{"clip":1},]}""",
            """{"segments":[[[[[[[[{"clip":1}]]]]]]]]}""",
            "[".repeat(100_000),
        ).forEach { assertTrue(it.take(80), PlanJson.parse(it) is PlanParse.Rejected) }
    }

    @Test
    fun tooManySegments() {
        val items = (0..PlanLimits.MAX_SEGMENTS).joinToString(",") { """{"clip":1}""" }
        assertTrue(PlanJson.parse("""{"segments":[$items]}""") is PlanParse.Rejected)
    }

    @Test
    fun extractsFromTypicalModelOutput() {
        val fenced = "Here is the plan:\n```json\n{\"segments\":[{\"clip\":1,\"start\":1}]}\n```\nEnjoy!"
        assertEquals(listOf(PlanSegment(1, 1_000, null)), (PlanJson.fromModelOutput(fenced) as PlanParse.Parsed).segments)
    }

    @Test
    fun modelOutputWithSeveralObjectsOrNoneIsRefused() {
        listOf(
            "",
            "no json here",
            "{\"segments\":[{\"clip\":1}]} {\"segments\":[{\"clip\":2}]}",
            "}{",
            "{\"segments\":[{\"clip\":1}]",
            "x".repeat(PlanLimits.MAX_MODEL_OUTPUT + 1),
            "{".repeat(50),
        ).forEach { assertTrue(it.take(50), PlanJson.fromModelOutput(it) is PlanParse.Rejected) }
    }

    @Test
    fun bracesInsideStringsDoNotConfuseExtraction() {
        assertEquals("""{"a":"}{"}""", PlanJson.soleObject("""x {"a":"}{"} y"""))
        assertEquals("""{"a":"\"}"}""", PlanJson.soleObject("""{"a":"\"}"}"""))
        assertNull(PlanJson.soleObject("""{"a":"unterminated}"""))
    }

    @Test
    fun encodeRoundTrips() {
        val valid = listOf(ResolvedSegment(0, 1_500, 2_000), ResolvedSegment(1, 0, 10_000))
        val back = (PlanJson.parse(PlanJson.encode(valid)) as PlanParse.Parsed).segments
        assertEquals(listOf(PlanSegment(1, 1_500, 2_000), PlanSegment(2, 0, 10_000)), back)
    }
}

class PlanValidatorTest {
    private val durations = listOf(60_000L, 10_000L)

    private fun reasons(vararg segments: PlanSegment) =
        (PlanValidator.validate(segments.toList(), durations) as PlanCheck.Invalid).reasons

    @Test
    fun resolvesOpenEnds() {
        val check = PlanValidator.validate(listOf(PlanSegment(1, 10_000, 40_000), PlanSegment(2, 0, null)), durations)
        assertEquals(
            listOf(ResolvedSegment(0, 10_000, 40_000), ResolvedSegment(1, 0, 10_000)),
            (check as PlanCheck.Valid).segments,
        )
    }

    @Test
    fun allowsSameClipTwiceAndExactEnd() {
        val check = PlanValidator.validate(listOf(PlanSegment(1, 0, 60_000), PlanSegment(1, 0, 100)), durations)
        assertTrue(check is PlanCheck.Valid)
    }

    @Test
    fun refusesOutOfRange() {
        assertTrue(reasons(PlanSegment(3, 0, null))[0].contains("clip 3"))
        assertEquals(1, reasons(PlanSegment(0, 0, null)).size)
        assertEquals(1, reasons(PlanSegment(-1, 0, null)).size)
        assertEquals(1, reasons(PlanSegment(1, 0, 60_001)).size)
        assertEquals(1, reasons(PlanSegment(2, 10_000, null)).size)
        assertEquals(1, reasons(PlanSegment(2, 11_000, null)).size)
        assertEquals(1, reasons(PlanSegment(1, 40_000, 10_000)).size)
        assertEquals(1, reasons(PlanSegment(1, 10_000, 10_000)).size)
        assertEquals(1, reasons(PlanSegment(1, 10_000, 10_050)).size)
        assertEquals(1, reasons(PlanSegment(1, -5, 10_000)).size)
        assertEquals(1, reasons(PlanSegment(1, Long.MAX_VALUE, null)).size)
        assertEquals(1, reasons(PlanSegment(1, 0, Long.MAX_VALUE)).size)
    }

    @Test
    fun collectsAllReasons() {
        assertEquals(2, reasons(PlanSegment(9, 0, null), PlanSegment(1, 5, 1), PlanSegment(1, 0, 1_000)).size)
    }

    @Test
    fun emptyInputs() {
        assertTrue(PlanValidator.validate(emptyList(), durations) is PlanCheck.Invalid)
        assertTrue(PlanValidator.validate(listOf(PlanSegment(1, 0, null)), emptyList()) is PlanCheck.Invalid)
        assertTrue(PlanValidator.validate(listOf(PlanSegment(1, 0, null)), listOf(0L)) is PlanCheck.Invalid)
        assertTrue(PlanValidator.validate(listOf(PlanSegment(1, 0, null)), listOf(-5L)) is PlanCheck.Invalid)
        val many = List(PlanLimits.MAX_SEGMENTS + 1) { PlanSegment(1, 0, null) }
        assertTrue(PlanValidator.validate(many, durations) is PlanCheck.Invalid)
    }

    @Test
    fun endToEndHostileModelPlanCannotPassValidation() {
        val parsed = PlanJson.fromModelOutput("""{"segments":[{"clip":1,"start":0,"end":99999}]}""") as PlanParse.Parsed
        assertTrue(PlanValidator.validate(parsed.segments, durations) is PlanCheck.Invalid)
    }
}

class DurationsTest {
    @Test
    fun formats() {
        assertEquals("0:00.0", Durations.format(0))
        assertEquals("1:05.5", Durations.format(65_500))
        assertEquals("1:01:01.0", Durations.format(3_661_000))
        assertEquals("0:00.0", Durations.format(-1))
    }
}

class CodecMatchTest {
    private val video = TrackSpec("video/avc", width = 1920, height = 1080, rotation = 90, csdHash = "aa")
    private val audio = TrackSpec("audio/mp4a-latm", sampleRate = 48000, channels = 2, csdHash = "bb")

    @Test
    fun sameFormatPasses() {
        assertTrue(CodecMatch.check(listOf(ClipSpec(video, audio), ClipSpec(video, audio))).isEmpty())
        assertTrue(CodecMatch.check(listOf(ClipSpec(video, null))).isEmpty())
    }

    @Test
    fun differencesAreRefused() {
        listOf(
            ClipSpec(video.copy(mime = "video/hevc"), audio),
            ClipSpec(video.copy(width = 1280), audio),
            ClipSpec(video.copy(height = 720), audio),
            ClipSpec(video.copy(rotation = 0), audio),
            ClipSpec(video.copy(csdHash = "cc"), audio),
            ClipSpec(video, audio.copy(sampleRate = 44100)),
            ClipSpec(video, audio.copy(channels = 1)),
            ClipSpec(video, audio.copy(csdHash = "dd")),
            ClipSpec(video, null),
        ).forEach { assertEquals(it.toString(), 1, CodecMatch.check(listOf(ClipSpec(video, audio), it)).size) }
    }

    @Test
    fun unsupportedCodecsAreRefused() {
        assertEquals(1, CodecMatch.check(listOf(ClipSpec(video.copy(mime = "video/x-vnd.on2.vp9"), audio))).size)
        assertEquals(1, CodecMatch.check(listOf(ClipSpec(video, audio.copy(mime = "audio/opus")))).size)
        assertEquals(1, CodecMatch.check(listOf(ClipSpec(null, audio))).size)
        assertEquals(1, CodecMatch.check(emptyList()).size)
    }

    @Test
    fun reasonsUseTheRealClipNumbers() {
        val reasons = CodecMatch.check(listOf(ClipSpec(video, audio), ClipSpec(video.copy(width = 640), audio)), listOf(1, 3))
        assertEquals(listOf("Clip 3 has a different video format from clip 1, so it cannot be joined without re-encoding."), reasons)
        val unsupported = CodecMatch.check(listOf(ClipSpec(video.copy(mime = "video/x-vnd.on2.vp9"), audio)), listOf(4))
        assertTrue(unsupported.single().startsWith("Clip 4 "))
    }

    @Test
    fun hostileMimeIsCleanedInTheReason() {
        val reason = CodecMatch.check(listOf(ClipSpec(video.copy(mime = "video/‮evil\n"), null))).single()
        assertTrue(reason.contains("video/evil"))
    }
}

class SampleFilterTest {
    @Test
    fun video() {
        assertEquals(SampleAction.WRITE, SampleFilter.video(100, 100, 200))
        assertEquals(SampleAction.SKIP, SampleFilter.video(50, 100, 200))
        assertEquals(SampleAction.STOP, SampleFilter.video(200, 100, 200))
        assertEquals(SampleAction.STOP, SampleFilter.video(-1, 100, 200))
    }

    @Test
    fun bFramesAfterTheEndFrameInDecodeOrderAreNeverWritten() {
        val decodeOrder = listOf(100L, 400L, 200L, 300L, 800L, 600L, 500L, 700L)
        val written = ArrayList<Long>()
        for (time in decodeOrder) {
            val action = SampleFilter.video(time, 100, 750)
            if (action == SampleAction.STOP) break
            if (action == SampleAction.WRITE) written.add(time)
        }
        assertEquals(listOf(100L, 400L, 200L, 300L), written)
    }

    @Test
    fun audio() {
        assertEquals(SampleAction.WRITE, SampleFilter.audio(100, 100, 200))
        assertEquals(SampleAction.SKIP, SampleFilter.audio(99, 100, 200))
        assertEquals(SampleAction.STOP, SampleFilter.audio(200, 100, 200))
        assertEquals(SampleAction.STOP, SampleFilter.audio(-1, 100, 200))
    }

    @Test
    fun nextOffsetAdvancesAndBoundsGap() {
        assertEquals(1_033_333L, SampleFilter.nextOffset(0, 0, 1_000_000, 33_333))
        assertEquals(5_033_333L, SampleFilter.nextOffset(4_000_000, 500_000, 1_500_000, 33_333))
        assertEquals(1L, SampleFilter.nextOffset(0, 0, 0, 0))
        assertEquals(2_000_000L, SampleFilter.nextOffset(0, 0, 1_000_000, Long.MAX_VALUE))
        assertEquals(7L, SampleFilter.nextOffset(7, 100, 50, 10))
    }
}

class ClipGrantsTest {
    private fun clip(uri: String) = StoredClip(uri, "n", 1)

    @Test
    fun releasesOnlyWhatNoRemainingClipUses() {
        assertEquals(listOf("content://a"), ClipGrants.releasable(listOf(clip("content://a")), listOf(clip("content://b"))))
        assertTrue(ClipGrants.releasable(listOf(clip("content://a")), listOf(clip("content://a"))).isEmpty())
        assertEquals(listOf("content://a"), ClipGrants.releasable(listOf(clip("content://a"), clip("content://a")), emptyList()))
    }
}

class ClipListCodecTest {
    @Test
    fun roundTrip() {
        val clips = listOf(StoredClip("content://a/1", "a.mp4", 5), StoredClip("content://a/2", "b.mp4", 6))
        assertEquals(clips, ClipListCodec.decode(ClipListCodec.encode(clips)))
    }

    @Test
    fun corruption() {
        listOf(null, "", "{", "[]", """{"clips":7}""").forEach { assertTrue(ClipListCodec.decode(it).isEmpty()) }
    }

    @Test
    fun hostileEntriesDropped() {
        val text = """{"clips":[
            {"uri":"file:///sdcard/secret","name":"x","durationMs":1},
            {"uri":"http://evil","name":"x","durationMs":1},
            {"uri":"content://ok","name":"‮name","durationMs":1},
            {"uri":"content://ok","name":"x","durationMs":0},
            {"uri":"content://ok","name":"x","durationMs":-1},
            {"uri":7,"name":"x","durationMs":1}
        ]}"""
        val decoded = ClipListCodec.decode(text)
        assertEquals(1, decoded.size)
        assertEquals("name", decoded[0].name)
    }
}

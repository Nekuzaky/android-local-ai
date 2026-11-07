package dev.nekuz.localmind

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class PlanSegment(val clip: Int, val startMs: Long, val endMs: Long?)

data class ResolvedSegment(val clipIndex: Int, val startMs: Long, val endMs: Long)

sealed class PlanParse {
    data class Parsed(val segments: List<PlanSegment>) : PlanParse()
    data class Rejected(val reason: String) : PlanParse()
}

sealed class PlanCheck {
    data class Valid(val segments: List<ResolvedSegment>) : PlanCheck()
    data class Invalid(val reasons: List<String>) : PlanCheck()
}

object PlanLimits {
    const val MAX_SEGMENTS = 32
    const val MAX_CLIPS = 20
    const val MIN_SEGMENT_MS = 100L
    const val MAX_SECONDS = 360_000.0
    const val MAX_COMMAND = 1000
    const val MAX_MODEL_OUTPUT = 16_384
}

object PlanJson {
    private val ROOT_KEYS = setOf("segments")
    private val SEGMENT_KEYS = setOf("clip", "start", "end")

    fun fromModelOutput(output: String): PlanParse {
        if (output.length > PlanLimits.MAX_MODEL_OUTPUT) return PlanParse.Rejected("The model's answer was too long.")
        val body = soleObject(output) ?: return PlanParse.Rejected("The model did not answer with one JSON object.")
        return parse(body)
    }

    fun parse(text: String): PlanParse {
        val root = StrictJson.parseObject(text, PlanLimits.MAX_MODEL_OUTPUT, 6)
            ?: return PlanParse.Rejected("The plan is not valid JSON.")
        if (root.fields.keys != ROOT_KEYS) return PlanParse.Rejected("The plan may only contain a segments list.")
        val items = root.arr("segments") ?: return PlanParse.Rejected("segments must be a list.")
        if (items.isEmpty()) return PlanParse.Rejected("The plan has no segments.")
        if (items.size > PlanLimits.MAX_SEGMENTS) return PlanParse.Rejected("The plan has too many segments.")
        val segments = ArrayList<PlanSegment>()
        for ((index, item) in items.withIndex()) {
            val label = "Segment ${index + 1}"
            val entry = item as? JsonValue.JObj ?: return PlanParse.Rejected("$label is not an object.")
            if (!SEGMENT_KEYS.containsAll(entry.fields.keys)) return PlanParse.Rejected("$label has an unknown field.")
            val clipValue = entry.fields["clip"] as? JsonValue.JNum ?: return PlanParse.Rejected("$label needs a clip number.")
            val clip = clipValue.exactLong ?: return PlanParse.Rejected("$label has a clip number that is not whole.")
            if (clip < 1 || clip > PlanLimits.MAX_CLIPS) return PlanParse.Rejected("$label has a clip number out of range.")
            val start = if (entry.fields.containsKey("start")) {
                seconds(entry.fields["start"]) ?: return PlanParse.Rejected("$label has an invalid start.")
            } else {
                0L
            }
            val end = if (entry.fields.containsKey("end")) {
                seconds(entry.fields["end"]) ?: return PlanParse.Rejected("$label has an invalid end.")
            } else {
                null
            }
            segments.add(PlanSegment(clip.toInt(), start, end))
        }
        return PlanParse.Parsed(segments)
    }

    private fun seconds(value: JsonValue?): Long? {
        val number = (value as? JsonValue.JNum)?.value ?: return null
        if (number.isNaN() || number.isInfinite() || number < 0 || number > PlanLimits.MAX_SECONDS) return null
        return Math.round(number * 1000)
    }

    fun soleObject(text: String): String? {
        var first = -1
        var depth = 0
        var inString = false
        var escaped = false
        var end = -1
        for (index in text.indices) {
            val c = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> if (depth > 0) inString = true
                '{' -> {
                    if (depth == 0) {
                        if (first >= 0) return null
                        first = index
                    }
                    depth++
                    if (depth > 8) return null
                }
                '}' -> {
                    if (depth == 0) return null
                    depth--
                    if (depth == 0) end = index
                }
            }
        }
        if (first < 0 || end < 0 || depth != 0) return null
        return text.substring(first, end + 1)
    }

    fun encode(segments: List<ResolvedSegment>): String {
        val list = JSONArray()
        for (segment in segments) {
            list.put(
                JSONObject()
                    .put("clip", segment.clipIndex + 1)
                    .put("start", segment.startMs / 1000.0)
                    .put("end", segment.endMs / 1000.0),
            )
        }
        return JSONObject().put("segments", list).toString()
    }
}

object TypedPlan {
    private const val T = "([0-9]{1,6}(?::[0-9]{1,2}){0,2}(?:\\.[0-9]{1,3})?s?|[0-9]{1,4}m(?:[0-9]{1,2}s)?)"
    private const val TO = "\\s*(?:to|until|-)\\s*"
    private const val END = "(?:the\\s+)?end"
    private val SEPARATOR = Regex("\\s*(?:,\\s*and\\s+then\\b|,\\s*then\\b|\\band\\s+then\\b|\\bthen\\b|;|,|\\n)\\s*")
    private val LEADING = Regex("^(?:and\\s+|keep\\s+|use\\s+|take\\s+|add\\s+|play\\s+)+")
    private val WHOLE = Regex("^(?:all\\s+of\\s+|(?:the\\s+)?whole\\s+(?:of\\s+)?|(?:the\\s+)?entire\\s+|full\\s+)?clip\\s*([0-9]{1,3})$")
    private val RANGE_OF = Regex("^(?:from\\s+)?$T$TO(?:$T|$END)\\s+(?:of|from|in)\\s+clip\\s*([0-9]{1,3})$")
    private val CLIP_RANGE = Regex("^clip\\s*([0-9]{1,3})\\s*:?\\s*(?:from\\s+)?$T$TO(?:$T|$END)$")
    private val FIRST = Regex("^(?:the\\s+)?first\\s+$T\\s+of\\s+clip\\s*([0-9]{1,3})$")
    private val FROM_ONLY = Regex("^clip\\s*([0-9]{1,3})\\s+from\\s+$T$")

    fun parse(command: String): PlanParse {
        if (command.length > PlanLimits.MAX_COMMAND) return PlanParse.Rejected("The command is too long.")
        val lower = command.lowercase(Locale.ROOT).trim()
        if (lower.isEmpty()) return PlanParse.Rejected("Type a plan first.")
        val parts = lower.split(SEPARATOR).map { it.trim().replace(LEADING, "").trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return PlanParse.Rejected("Type a plan first.")
        if (parts.size > PlanLimits.MAX_SEGMENTS) return PlanParse.Rejected("The plan has too many segments.")
        val segments = ArrayList<PlanSegment>()
        for (part in parts) {
            val segment = segment(part)
                ?: return PlanParse.Rejected("Could not read \"${ForeignText.clean(part, 60)}\". Try: keep 0:10 to 0:40 of clip 1, then all of clip 2")
            segments.add(segment)
        }
        return PlanParse.Parsed(segments)
    }

    private fun segment(part: String): PlanSegment? {
        WHOLE.matchEntire(part)?.let { return PlanSegment(clip(it.groupValues[1]) ?: return null, 0, null) }
        RANGE_OF.matchEntire(part)?.let {
            val start = time(it.groupValues[1]) ?: return null
            val end = it.groupValues[2].takeIf { text -> text.isNotEmpty() }?.let { text -> time(text) ?: return null }
            return PlanSegment(clip(it.groupValues[3]) ?: return null, start, end)
        }
        CLIP_RANGE.matchEntire(part)?.let {
            val start = time(it.groupValues[2]) ?: return null
            val end = it.groupValues[3].takeIf { text -> text.isNotEmpty() }?.let { text -> time(text) ?: return null }
            return PlanSegment(clip(it.groupValues[1]) ?: return null, start, end)
        }
        FIRST.matchEntire(part)?.let {
            return PlanSegment(clip(it.groupValues[2]) ?: return null, 0, time(it.groupValues[1]) ?: return null)
        }
        FROM_ONLY.matchEntire(part)?.let {
            return PlanSegment(clip(it.groupValues[1]) ?: return null, time(it.groupValues[2]) ?: return null, null)
        }
        return null
    }

    private fun clip(text: String): Int? = text.toIntOrNull()?.takeIf { it in 1..PlanLimits.MAX_CLIPS }

    fun time(raw: String): Long? {
        val text = raw.trim().lowercase(Locale.ROOT)
        if (text.isEmpty() || text.length > 12) return null
        if (text.contains('m')) {
            val minutes = text.substringBefore('m').toLongOrNull() ?: return null
            val rest = text.substringAfter('m').removeSuffix("s")
            val seconds = if (rest.isEmpty()) 0L else rest.toLongOrNull() ?: return null
            if (seconds >= 60) return null
            return checked(minutes * 60_000 + seconds * 1000)
        }
        val body = text.removeSuffix("s")
        val pieces = body.split(':')
        if (pieces.size > 3) return null
        val last = pieces.last()
        val whole = last.substringBefore('.').toLongOrNull() ?: return null
        val fraction = if (last.contains('.')) last.substringAfter('.').padEnd(3, '0').take(3).toLongOrNull() ?: return null else 0L
        val lead = pieces.dropLast(1).map { it.toLongOrNull() ?: return null }
        if (lead.isNotEmpty() && whole >= 60) return null
        if (lead.size == 2 && lead[1] >= 60) return null
        var total = 0L
        for (value in lead) total = total * 60 + value
        return checked((total * 60 + whole) * 1000 + fraction)
    }

    private fun checked(ms: Long): Long? = ms.takeIf { it >= 0 && it <= (PlanLimits.MAX_SECONDS * 1000).toLong() }
}

object PlanValidator {
    fun validate(segments: List<PlanSegment>, durationsMs: List<Long>): PlanCheck {
        val reasons = ArrayList<String>()
        val resolved = ArrayList<ResolvedSegment>()
        if (durationsMs.isEmpty()) return PlanCheck.Invalid(listOf("Add at least one clip first."))
        if (segments.isEmpty()) return PlanCheck.Invalid(listOf("The plan has no segments."))
        if (segments.size > PlanLimits.MAX_SEGMENTS) return PlanCheck.Invalid(listOf("The plan has too many segments."))
        for ((index, segment) in segments.withIndex()) {
            val label = "Segment ${index + 1}"
            if (segment.clip < 1 || segment.clip > durationsMs.size) {
                reasons.add("$label uses clip ${segment.clip}, but there are ${durationsMs.size} clips.")
                continue
            }
            val duration = durationsMs[segment.clip - 1]
            if (duration <= 0) {
                reasons.add("$label uses a clip with an unknown length.")
                continue
            }
            val end = segment.endMs ?: duration
            when {
                segment.startMs < 0 || end < 0 -> reasons.add("$label has a negative time.")
                segment.startMs >= end -> reasons.add("$label starts at or after its end.")
                end > duration -> reasons.add("$label ends at ${Durations.format(end)}, but clip ${segment.clip} lasts ${Durations.format(duration)}.")
                end - segment.startMs < PlanLimits.MIN_SEGMENT_MS -> reasons.add("$label is too short.")
                else -> resolved.add(ResolvedSegment(segment.clip - 1, segment.startMs, end))
            }
        }
        return if (reasons.isEmpty()) PlanCheck.Valid(resolved) else PlanCheck.Invalid(reasons)
    }
}

object Durations {
    fun format(ms: Long): String {
        val value = ms.coerceAtLeast(0)
        val total = value / 1000
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        val tenths = (value % 1000) / 100
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d.%d", hours, minutes, seconds, tenths)
        } else {
            String.format(Locale.ROOT, "%d:%02d.%d", minutes, seconds, tenths)
        }
    }
}

data class TrackSpec(
    val mime: String,
    val width: Int = 0,
    val height: Int = 0,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val rotation: Int = 0,
    val csdHash: String = "",
)

data class ClipSpec(val video: TrackSpec?, val audio: TrackSpec?)

object CodecMatch {
    private val VIDEO = setOf("video/avc", "video/hevc", "video/mp4v-es", "video/3gpp")
    private val AUDIO = setOf("audio/mp4a-latm", "audio/3gpp", "audio/amr-wb")

    fun check(clips: List<ClipSpec>, numbers: List<Int> = clips.indices.map { it + 1 }): List<String> {
        val reasons = ArrayList<String>()
        if (clips.isEmpty()) return listOf("No clips are used.")
        clips.forEachIndexed { index, clip ->
            val label = "Clip ${numbers.getOrElse(index) { index + 1 }}"
            val video = clip.video
            if (video == null) {
                reasons.add("$label has no video track.")
            } else if (video.mime !in VIDEO) {
                reasons.add("$label uses ${ForeignText.clean(video.mime, 40)}, which cannot be saved losslessly to MP4 here.")
            }
            val audio = clip.audio
            if (audio != null && audio.mime !in AUDIO) {
                reasons.add("$label has ${ForeignText.clean(audio.mime, 40)} audio, which cannot be saved losslessly to MP4 here.")
            }
        }
        if (reasons.isNotEmpty()) return reasons
        val first = clips.first()
        for (index in 1 until clips.size) {
            val other = clips[index]
            val label = "Clip ${numbers.getOrElse(index) { index + 1 }}"
            val base = numbers.getOrElse(0) { 1 }
            if (other.video != first.video) reasons.add("$label has a different video format from clip $base, so it cannot be joined without re-encoding.")
            if (other.audio != first.audio) reasons.add("$label has a different audio format from clip $base, so it cannot be joined without re-encoding.")
        }
        return reasons
    }
}

enum class SampleAction { WRITE, SKIP, STOP }

object SampleFilter {
    fun video(timeUs: Long, startUs: Long, endUs: Long): SampleAction = when {
        timeUs < 0 || timeUs >= endUs -> SampleAction.STOP
        timeUs < startUs -> SampleAction.SKIP
        else -> SampleAction.WRITE
    }

    fun audio(timeUs: Long, startUs: Long, endUs: Long): SampleAction = when {
        timeUs < 0 || timeUs >= endUs -> SampleAction.STOP
        timeUs < startUs -> SampleAction.SKIP
        else -> SampleAction.WRITE
    }

    fun nextOffset(offsetUs: Long, startUs: Long, lastTimeUs: Long, frameGapUs: Long): Long {
        if (lastTimeUs < startUs) return offsetUs
        val gap = frameGapUs.coerceIn(1, MAX_GAP_US)
        return offsetUs + (lastTimeUs - startUs) + gap
    }

    private const val MAX_GAP_US = 1_000_000L
}

data class StoredClip(val uri: String, val name: String, val durationMs: Long)

object ClipGrants {
    fun releasable(removed: List<StoredClip>, remaining: List<StoredClip>): List<String> {
        val kept = remaining.map { it.uri }.toSet()
        return removed.map { it.uri }.distinct().filter { it !in kept }
    }
}

object ClipListCodec {
    private const val MAX_URI = 2048

    fun encode(clips: List<StoredClip>): String {
        val list = JSONArray()
        for (clip in clips.take(PlanLimits.MAX_CLIPS)) {
            list.put(JSONObject().put("uri", clip.uri).put("name", clip.name).put("durationMs", clip.durationMs))
        }
        return JSONObject().put("clips", list).toString()
    }

    fun decode(text: String?): List<StoredClip> {
        val root = StrictJson.parseObject(text, 256 * 1024) ?: return emptyList()
        val items = root.arr("clips") ?: return emptyList()
        val out = ArrayList<StoredClip>()
        for (item in items.take(PlanLimits.MAX_CLIPS)) {
            val entry = item as? JsonValue.JObj ?: continue
            val uri = entry.str("uri")?.takeIf { it.startsWith("content://") && it.length <= MAX_URI } ?: continue
            val duration = entry.long("durationMs")?.takeIf { it > 0 } ?: continue
            out.add(StoredClip(uri, ForeignText.label(entry.str("name"), "Clip"), duration))
        }
        return out
    }
}

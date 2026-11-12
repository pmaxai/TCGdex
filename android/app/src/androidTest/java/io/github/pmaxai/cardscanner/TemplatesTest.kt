package io.github.pmaxai.cardscanner

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pmaxai.cardscanner.recording.RecordingDir
import io.github.pmaxai.cardscanner.recording.RecordingUploader
import io.github.pmaxai.cardscanner.recording.S3Client
import io.github.pmaxai.cardscanner.recording.S3Config
import io.github.pmaxai.cardscanner.recording.Submission
import io.github.pmaxai.cardscanner.recording.SubmissionForm
import io.github.pmaxai.cardscanner.recording.Templates
import io.github.pmaxai.cardscanner.recording.TemplatesIndex
import io.github.pmaxai.cardscanner.recording.TemplatesSource
import io.github.pmaxai.cardscanner.recording.UploadState
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/**
 * Templates (requirements R2–R6, R9): the pipeline's templates list, the bucket → saved copy →
 * built-in order, the inputs form's rules, submission.json, and its upload before the marker.
 */
@RunWith(AndroidJUnit4::class)
class TemplatesTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    companion object {
        /** the requirements' example: the list once "Shorts · Hype" is approved */
        const val DOC = """{
  "schema": "pokepov.templates.v1",
  "templates": [
    {"id": "longform-smooth", "name": "Long Form · Smooth", "default": true,
     "description": "The whole recording with live commentary and lo-fi music. For YouTube.",
     "inputs": ["note", "always", "never", "voice", "music", "title"]},
    {"id": "shorts-hype", "name": "Shorts · Hype", "default": false,
     "description": "The best cards of the lot as a 30–60 s countdown, best card last. For YouTube Shorts, Instagram, TikTok and Facebook.",
     "inputs": ["note", "paid_eur", "cards", "always", "never", "title"]}
  ],
  "inputs": {
    "note":     {"type": "text",   "label": "Note for the voice", "max": 300,
                 "help": "Facts the voice may use, such as where the lot is from"},
    "paid_eur": {"type": "number", "label": "What we paid (€)", "min": 0},
    "cards":    {"type": "number", "label": "Cards in the Short", "min": 2, "max": 6, "default": 4},
    "always":   {"type": "cards",  "label": "Always show"},
    "never":    {"type": "cards",  "label": "Never show"},
    "voice":    {"type": "choice", "label": "Voice", "options": ["ai", "own", "none"], "default": "ai"},
    "music":    {"type": "toggle", "label": "Music", "default": true},
    "title":    {"type": "text",   "label": "Title idea", "max": 90}
  }
}"""
    }

    @Test
    fun theListIsReadAsThePipelineWritesIt() {
        val idx = TemplatesIndex.parse(DOC)
        assertEquals(listOf("longform-smooth", "shorts-hype"), idx.templates.map { it.id })
        assertEquals(listOf(true, false), idx.templates.map { it.default })
        assertEquals("Long Form · Smooth", idx.templates[0].name)
        // R4: one field per input of the ticked templates, each once
        assertEquals(listOf("note", "always", "never", "voice", "music", "title"), idx.fieldsFor(setOf("longform-smooth")).map { it.id })
        assertEquals(listOf("note", "always", "never", "voice", "music", "title", "paid_eur", "cards"),
            idx.fieldsFor(setOf("longform-smooth", "shorts-hype")).map { it.id })
        val cards = idx.inputs.getValue("cards")
        assertEquals(2.0, cards.min); assertEquals(6.0, cards.max); assertEquals(4, (cards.default as Number).toInt())
        assertEquals(listOf("ai", "own", "none"), idx.inputs.getValue("voice").options)
        assertEquals(true, idx.inputs.getValue("music").default)
    }

    /** R9: an input of a type the app doesn't know is not shown; fields it doesn't know are ignored. */
    @Test
    fun unknownTypesAndFieldsAreIgnored() {
        val o = JSONObject(DOC)
        o.put("generated_by", "pipeline 2.1")
        o.getJSONArray("templates").getJSONObject(0).put("inputs", o.getJSONArray("templates").getJSONObject(0).getJSONArray("inputs").put("mood"))
            .put("thumbnail", "x.png")
        o.getJSONObject("inputs").put("mood", JSONObject().put("type", "slider").put("label", "Mood"))
        o.getJSONObject("inputs").getJSONObject("note").put("rows", 4)
        val idx = TemplatesIndex.parse(o.toString())
        assertEquals(listOf("note", "always", "never", "voice", "music", "title"), idx.fieldsFor(setOf("longform-smooth")).map { it.id })
        assertEquals(300.0, idx.inputs.getValue("note").max)
    }

    /** R2: another schema is an error, so the saved copy or the built-in default is used. */
    @Test
    fun anotherSchemaIsAnError() {
        val wrong = DOC.replace("pokepov.templates.v1", "pokepov.templates.v2")
        assertTrue(runCatching { TemplatesIndex.parse(wrong) }.isFailure)
        val cache = File(ctx.cacheDir, "templates-${System.nanoTime()}/index.json")
        val l = Templates.load({ wrong.toByteArray() }, cache)
        assertEquals(TemplatesSource.BUILT_IN, l.source)
        assertTrue("the reason: ${l.error}", l.error!!.contains("schema"))
        assertFalse("not saved", cache.exists())
    }

    /** R2: the bucket's list is saved; offline the saved one; without one only "Long Form · Smooth". */
    @Test
    fun bucketThenSavedCopyThenBuiltIn() {
        val cache = File(ctx.cacheDir, "templates-${System.nanoTime()}/index.json")
        val fresh = Templates.load({ DOC.toByteArray() }, cache)
        assertEquals(TemplatesSource.BUCKET, fresh.source)
        assertNull(fresh.error)
        assertTrue("saved on the phone", cache.exists())
        val offline = Templates.load({ throw java.net.UnknownHostException("sos-at-vie-1.exo.io") }, cache)
        assertEquals(TemplatesSource.SAVED, offline.source)
        assertEquals("offline", offline.error)
        assertEquals(listOf("longform-smooth", "shorts-hype"), offline.index.templates.map { it.id })
        cache.delete()
        val first = Templates.load({ throw java.net.UnknownHostException("sos-at-vie-1.exo.io") }, cache)
        assertEquals(TemplatesSource.BUILT_IN, first.source)
        assertEquals(1, first.index.templates.size)
        val t = first.index.templates[0]
        assertEquals("longform-smooth", t.id); assertEquals("Long Form · Smooth", t.name); assertTrue(t.default); assertTrue(t.inputs.isEmpty())
        assertEquals(TemplatesSource.BUILT_IN, Templates.load(null, cache).source)
        cache.parentFile?.deleteRecursively()
    }

    /** "Default only": Submit with nothing changed – the default template, no inputs. */
    @Test
    fun nothingChangedMeansNoInputs() {
        val idx = TemplatesIndex.parse(DOC)
        val fields = idx.fieldsFor(setOf("longform-smooth"))
        val raw = fields.associate { it.id to SubmissionForm.initial(it) }
        val inputs = SubmissionForm.inputs(fields, raw)
        assertEquals(0, inputs.length())
        val s = Submission.build(listOf("longform-smooth"), inputs, "1.2.61", Instant.parse("2026-11-12T18:30:00.123Z"))
        assertEquals("tcgdex.submission.v1", s.getString("schema"))
        assertEquals("2026-11-12T18:30:00Z", s.getString("submitted_at"))
        assertEquals("1.2.61", s.getString("app_version"))
        assertEquals("""[{"id":"longform-smooth"}]""", s.getJSONArray("templates").toString())
        assertFalse("no inputs", s.has("inputs"))
    }

    /** "Two templates with inputs": typed as the pipeline reads them, empty and unchanged ones left out. */
    @Test
    fun twoTemplatesWithInputs() {
        val idx = TemplatesIndex.parse(DOC)
        val fields = idx.fieldsFor(setOf("longform-smooth", "shorts-hype"))
        val raw = fields.associate { it.id to SubmissionForm.initial(it) }.toMutableMap()
        raw["note"] = "  Bought at the Naschmarkt flea market  "
        raw["paid_eur"] = "40"
        raw["always"] = listOf(12)
        raw["never"] = listOf(3)
        raw["music"] = false
        raw["title"] = "   "
        val inputs = SubmissionForm.inputs(fields, raw)
        assertEquals("only the filled-in and changed ones: $inputs", setOf("note", "paid_eur", "always", "never", "music"), inputs.keys().asSequence().toSet())
        assertEquals("Bought at the Naschmarkt flea market", inputs.getString("note"))
        assertEquals("a whole number stays whole", 40L, inputs.get("paid_eur"))
        assertEquals("[12]", inputs.getJSONArray("always").toString())
        assertEquals("[3]", inputs.getJSONArray("never").toString())
        assertEquals(false, inputs.get("music"))
        val s = Submission.build(listOf("longform-smooth", "shorts-hype"), inputs, "1.2.61")
        assertEquals("""[{"id":"longform-smooth"},{"id":"shorts-hype"}]""", s.getJSONArray("templates").toString())
        assertEquals("Bought at the Naschmarkt flea market", s.getJSONObject("inputs").getString("note"))
        Log.i("Templates", "submission.json: $s")
    }

    @Test
    fun numbersAndTextFollowTheirLimits() {
        val idx = TemplatesIndex.parse(DOC)
        val cards = idx.inputs.getValue("cards"); val paid = idx.inputs.getValue("paid_eur"); val title = idx.inputs.getValue("title")
        assertEquals("Between 2 and 6", SubmissionForm.problem(cards, "7"))
        assertEquals("Not a number", SubmissionForm.problem(cards, "four"))
        assertNull(SubmissionForm.problem(cards, "4"))
        assertNull(SubmissionForm.problem(cards, ""))
        assertEquals("At least 0", SubmissionForm.problem(paid, "-5"))
        val o = SubmissionForm.inputs(listOf(paid, cards, title), mapOf("paid_eur" to "12,50", "cards" to "7", "title" to "x".repeat(100)))
        assertEquals(12.5, o.getDouble("paid_eur"), 1e-9)
        assertFalse("out of range: not submitted", o.has("cards"))
        assertEquals(90, o.getString("title").length)
    }

    /** A card can't be in both "Always show" and "Never show". */
    @Test
    fun aCardIsNeverInBothAlwaysAndNever() {
        val fields = TemplatesIndex.parse(DOC).fieldsFor(setOf("longform-smooth"))
        var raw: Map<String, Any?> = fields.associate { it.id to SubmissionForm.initial(it) }
        raw = SubmissionForm.tickCard(fields, raw, "always", 12, true)
        raw = SubmissionForm.tickCard(fields, raw, "always", 5, true)
        assertEquals(listOf(12, 5), raw["always"])
        raw = SubmissionForm.tickCard(fields, raw, "never", 12, true)
        assertEquals(listOf(5), raw["always"])
        assertEquals(listOf(12), raw["never"])
        raw = SubmissionForm.tickCard(fields, raw, "never", 12, false)
        assertEquals(emptyList<Int>(), raw["never"])
    }

    /** R5, R6: submission.json goes up with the recording, before _complete.json, and is listed there. */
    @Test
    fun theSubmissionGoesUpBeforeTheMarker() {
        val dir = RecordingDir(File(ctx.cacheDir, "sub-${System.nanoTime()}").apply { mkdirs() })
        dir.events.writeText("""{"schema":"tcgdex.recording.v1","video":{"audio":"none"},"cards":[{"entry":3,"hit":false,"card":{"name":"Pikachu","set":"Base Set","number_text":"58/102"},"price":{"value":1.5,"currency":"EUR"}}]}""")
        dir.packCsv.writeText("No,Name\r\n1,Pikachu\r\n")
        dir.video.writeBytes(ByteArray(2048) { it.toByte() })
        assertEquals(listOf("events.json", "pack.csv", "video.mp4"), dir.uploadFiles().map { it.name })
        Submission.write(dir.dir, Submission.build(listOf("longform-smooth"), JSONObject().put("never", org.json.JSONArray(listOf(3))), "ci"))
        assertEquals(listOf("events.json", "pack.csv", "submission.json", "video.mp4"), dir.uploadFiles().map { it.name })
        assertEquals(listOf(3), Submission.packOf(dir.events).map { it.entry })
        assertFalse(Submission.hasMicSound(dir.events, dir.header))

        val endpoint = InstrumentationRegistry.getArguments().getString("s3Endpoint").orEmpty()
        assumeTrue("no S3 for the upload in this run", endpoint.isNotBlank())
        val cfg = S3Config(endpoint, "us-east-1", "recordings-test", "templates-test/", pathStyle = true, accessKeyId = "minioadmin",
            secret = "minioadmin", allowHttp = true)
        val c = S3Client(cfg)
        RecordingUploader(c, dir.id, UploadState(dir.uploadState), "ci").run(dir.uploadFiles())
        val marker = JSONObject(String(c.getObject("templates-test/recordings/${dir.id}/_complete.json")))
        val sub = marker.getJSONObject("files").getJSONObject("submission.json")
        assertEquals(dir.submission.length(), sub.getLong("bytes"))
        assertEquals(RecordingUploader.sha256Hex(dir.submission), sub.getString("sha256"))
        assertArrayEquals(dir.submission.readBytes(), c.getObject("templates-test/recordings/${dir.id}/submission.json"))
        // R2 against a real bucket: <prefix>templates/index.json
        c.putObject(Templates.key(cfg), DOC.toByteArray(), "application/json")
        val cache = File(ctx.cacheDir, "templates-${System.nanoTime()}/index.json")
        val l = Templates.load({ c.getObject(Templates.key(cfg)) }, cache)
        assertEquals(TemplatesSource.BUCKET, l.source)
        assertEquals(2, l.index.templates.size)
        Log.i("Templates", "uploaded ${dir.id}: ${marker.getJSONObject("files")}; list from ${Templates.key(cfg)}: ${l.index.templates.map { it.id }}")
        dir.dir.deleteRecursively(); cache.parentFile?.deleteRecursively()
    }
}

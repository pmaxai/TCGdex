package io.github.pmaxai.cardscanner

import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pmaxai.cardscanner.recording.RecordingUploader
import io.github.pmaxai.cardscanner.recording.S3Client
import io.github.pmaxai.cardscanner.recording.S3Config
import io.github.pmaxai.cardscanner.recording.S3Exception
import io.github.pmaxai.cardscanner.recording.SigV4
import io.github.pmaxai.cardscanner.recording.UploadFile
import io.github.pmaxai.cardscanner.recording.UploadState
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** S3 for recordings: the signature (AWS's published examples), multipart uploads, resume, errors; MinIO when the CI runs one. */
@RunWith(AndroidJUnit4::class)
class S3Test {
    // ------------------------------------------------------------------ signature: AWS's examples
    @Test
    fun signatureMatchesAwsTestSuiteGetVanilla() {
        val s = SigV4.sign("GET", "/", emptyList(), mapOf("Host" to "example.amazonaws.com", "X-Amz-Date" to "20150830T123600Z"),
            SigV4.EMPTY_SHA256, "20150830T123600Z", "us-east-1", "service", "AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY")
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, " +
            "SignedHeaders=host;x-amz-date, Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31", s.authorization)
    }

    private val s3Key = "AKIAIOSFODNN7EXAMPLE"
    private val s3Secret = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"

    @Test
    fun signatureMatchesS3GetObjectExample() {
        val s = SigV4.sign("GET", "/test.txt", emptyList(), mapOf("host" to "examplebucket.s3.amazonaws.com", "range" to "bytes=0-9",
            "x-amz-content-sha256" to SigV4.EMPTY_SHA256, "x-amz-date" to "20130524T000000Z"),
            SigV4.EMPTY_SHA256, "20130524T000000Z", "us-east-1", "s3", s3Key, s3Secret)
        assertTrue(s.authorization, s.authorization.endsWith("Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"))
    }

    @Test
    fun signatureMatchesS3PutObjectExample() {
        val body = "Welcome to Amazon S3.".toByteArray()
        val hash = SigV4.hex(SigV4.sha256(body))
        assertEquals("44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072", hash)
        val s = SigV4.sign("PUT", SigV4.uriEncode("/test\$file.text", keepSlash = true), emptyList(), mapOf(
            "date" to "Fri, 24 May 2013 00:00:00 GMT", "host" to "examplebucket.s3.amazonaws.com", "x-amz-content-sha256" to hash,
            "x-amz-date" to "20130524T000000Z", "x-amz-storage-class" to "REDUCED_REDUNDANCY"),
            hash, "20130524T000000Z", "us-east-1", "s3", s3Key, s3Secret)
        assertTrue(s.canonicalRequest, s.canonicalRequest.startsWith("PUT\n/test%24file.text\n\n"))
        assertTrue(s.authorization, s.authorization.endsWith("Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd"))
    }

    @Test
    fun signatureMatchesS3ListObjectsExample() {
        val s = SigV4.sign("GET", "/", listOf("prefix" to "J", "max-keys" to "2"), mapOf("host" to "examplebucket.s3.amazonaws.com",
            "x-amz-content-sha256" to SigV4.EMPTY_SHA256, "x-amz-date" to "20130524T000000Z"),
            SigV4.EMPTY_SHA256, "20130524T000000Z", "us-east-1", "s3", s3Key, s3Secret)
        assertTrue(s.canonicalRequest.contains("\nmax-keys=2&prefix=J\n"))
        assertTrue(s.authorization, s.authorization.endsWith("Signature=34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7"))
    }

    @Test
    fun prefixesAndEncoding() {
        assertEquals("tcgdex/", S3Config.normalizePrefix("tcgdex"))
        assertEquals("a/b/", S3Config.normalizePrefix("/a/b/"))
        assertEquals("", S3Config.normalizePrefix("  "))
        assertEquals("recordings/20261109T140322Z-7f3a/video.mp4", SigV4.uriEncode("recordings/20261109T140322Z-7f3a/video.mp4", true))
        assertEquals("a%20b%2Fc", SigV4.uriEncode("a b/c", false))
    }

    // ------------------------------------------------------------------ a simulated S3
    /** An S3 in memory: objects, multipart uploads, and failures to hand out first. */
    private class FakeS3 : Dispatcher() {
        val objects = ConcurrentHashMap<String, ByteArray>()
        val uploads = ConcurrentHashMap<String, ConcurrentHashMap<Int, ByteArray>>()
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        /** "PUT video.mp4 part 3" -> answers to give before the normal one */
        val failures = ConcurrentHashMap<String, MutableList<MockResponse>>()
        var nextId = 0

        private fun key(r: RecordedRequest) = r.requestUrl!!.encodedPath.removePrefix("/bucket/")
        private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b)

        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl!!
            val key = key(request)
            val part = url.queryParameter("partNumber")
            val label = "${request.method} ${key.substringAfterLast('/')}" + (part?.let { " part $it" } ?: "") +
                (if (url.queryParameterNames.contains("uploads")) " create" else "") +
                (if (request.method == "POST" && url.queryParameter("uploadId") != null) " complete" else "") +
                (if (request.method == "GET" && url.queryParameter("uploadId") != null) " list" else "") +
                (if (request.method == "DELETE" && url.queryParameter("uploadId") != null) " abort" else "")
            log += label
            failures[label]?.let { synchronized(it) { if (it.isNotEmpty()) return it.removeAt(0) } }
            if (request.getHeader("Authorization")?.startsWith("AWS4-HMAC-SHA256 Credential=key/") != true) return error(403, "AccessDenied")
            val body = request.body.readByteArray()
            if (request.getHeader("x-amz-content-sha256") != SigV4.hex(SigV4.sha256(body))) return error(400, "XAmzContentSHA256Mismatch")
            if (body.isNotEmpty() && request.getHeader("Content-MD5") != Base64.encodeToString(md5(body), Base64.NO_WRAP)) return error(400, "BadDigest")
            val uploadId = url.queryParameter("uploadId")
            return when {
                request.method == "POST" && url.queryParameterNames.contains("uploads") -> {
                    val id = "up${++nextId}"; uploads[id] = ConcurrentHashMap()
                    MockResponse().setBody("<InitiateMultipartUploadResult><UploadId>$id</UploadId></InitiateMultipartUploadResult>")
                }
                request.method == "PUT" && part != null -> {
                    val parts = uploads[uploadId] ?: return error(404, "NoSuchUpload")
                    parts[part.toInt()] = body
                    MockResponse().setHeader("ETag", "\"${SigV4.hex(md5(body))}\"")
                }
                request.method == "GET" && uploadId != null -> {
                    val parts = uploads[uploadId] ?: return error(404, "NoSuchUpload")
                    MockResponse().setBody("<ListPartsResult><IsTruncated>false</IsTruncated>" + parts.toSortedMap().entries.joinToString("") { (n, b) ->
                        "<Part><PartNumber>$n</PartNumber><ETag>&quot;${SigV4.hex(md5(b))}&quot;</ETag><Size>${b.size}</Size></Part>"
                    } + "</ListPartsResult>")
                }
                request.method == "POST" && uploadId != null -> {
                    val parts = uploads.remove(uploadId) ?: return error(404, "NoSuchUpload")
                    val numbers = Regex("<PartNumber>(\\d+)</PartNumber>").findAll(String(body)).map { it.groupValues[1].toInt() }.toList()
                    if (numbers != (1..parts.size).toList()) return error(400, "InvalidPart")
                    objects[key] = numbers.flatMap { parts[it]!!.toList() }.toByteArray()
                    MockResponse().setBody("<CompleteMultipartUploadResult><Key>$key</Key></CompleteMultipartUploadResult>")
                }
                request.method == "DELETE" && uploadId != null -> { uploads.remove(uploadId); MockResponse().setResponseCode(204) }
                request.method == "PUT" -> { objects[key] = body; MockResponse().setHeader("ETag", "\"${SigV4.hex(md5(body))}\"") }
                request.method == "DELETE" -> { objects.remove(key); MockResponse().setResponseCode(204) }
                request.method == "GET" -> objects[key]?.let { MockResponse().setBody(okio.Buffer().write(it)) } ?: error(404, "NoSuchKey")
                else -> error(400, "Unsupported")
            }
        }

        fun error(code: Int, s3code: String) = MockResponse().setResponseCode(code).setBody("<Error><Code>$s3code</Code><Message>$s3code</Message></Error>")
    }

    private lateinit var server: MockWebServer
    private lateinit var s3: FakeS3
    private lateinit var dir: File

    @Before fun start() {
        s3 = FakeS3()
        server = MockWebServer().apply { dispatcher = s3; start() }
        dir = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "s3test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After fun stop() { server.shutdown(); dir.deleteRecursively() }

    private fun client() = S3Client(S3Config(server.url("/").toString(), "eu-central-1", "bucket", "tcgdex/", pathStyle = true,
        accessKeyId = "key", secret = "secret", allowHttp = true), sleep = {})

    private fun file(name: String, size: Int) = File(dir, name).apply { writeBytes(Random(size).nextBytes(size)) }

    private val partSize = 100 * 1024

    private fun files() = listOf(
        UploadFile("events.json", file("events.json", 3_000), "application/json"),
        UploadFile("pack.csv", file("pack.csv", 800), "text/csv"),
        UploadFile("video.mp4", file("video.mp4", 450 * 1024 + 17), "video/mp4"),
        UploadFile("camera.mp4", file("camera.mp4", 250 * 1024), "video/mp4"),
    )

    private fun uploader(state: File = File(dir, "upload.json")) =
        RecordingUploader(client(), "20261109T140322Z-7f3a", UploadState(state), "1.2.30", partSize = partSize, parallel = 3)

    private fun sha(f: File) = SigV4.hex(MessageDigest.getInstance("SHA-256").digest(f.readBytes()))

    @Test
    fun aRecordingGoesUpInOrderWithTheMarkerLast() {
        val fs = files()
        var progressed = 0L
        RecordingUploader(client(), "20261109T140322Z-7f3a", UploadState(File(dir, "upload.json")), "1.2.30", partSize, 3) { done, _ -> progressed = done }
            .run(fs)
        val folder = "tcgdex/recordings/20261109T140322Z-7f3a/"
        fs.forEach { assertArrayEquals(it.name, it.file.readBytes(), s3.objects[folder + it.name]) }
        val log = s3.log.toList()
        val order = listOf("PUT events.json", "PUT pack.csv", "POST video.mp4 create", "POST video.mp4 complete",
            "POST camera.mp4 create", "POST camera.mp4 complete", "PUT _complete.json").map { log.indexOf(it) }
        assertTrue("order: $log", order.all { it >= 0 } && order == order.sorted())
        assertEquals("the marker is the very last request", "PUT _complete.json", log.last())
        assertEquals(5, log.count { it.startsWith("PUT video.mp4 part") })
        val marker = JSONObject(String(s3.objects[folder + "_complete.json"]!!))
        assertEquals("tcgdex.recording.v1", marker.getString("schema"))
        fs.forEach {
            val e = marker.getJSONObject("files").getJSONObject(it.name)
            assertEquals(it.file.length(), e.getLong("bytes"))
            assertEquals(sha(it.file), e.getString("sha256"))
        }
        assertEquals(fs.sumOf { it.file.length() }, progressed)
        assertTrue(UploadState(File(dir, "upload.json")).complete)
    }

    @Test
    fun aBrokenUploadContinuesAtTheMissingParts() {
        val fs = files()
        // part 3 of the video fails three times in a row: the first run gives up there
        s3.failures["PUT video.mp4 part 3"] = MutableList(3) { MockResponse().setResponseCode(500) }
        try { uploader().run(fs); fail("no error") } catch (e: java.io.IOException) { /* gave up for now */ }
        assertFalse(s3.objects.containsKey("tcgdex/recordings/20261109T140322Z-7f3a/video.mp4"))
        val there = s3.uploads.values.single().keys.toSet()
        assertFalse(3 in there)

        // the next run (a new start of the app) lists the parts and sends only what is missing
        s3.log.clear()
        uploader().run(fs)
        assertTrue(s3.log.contains("GET video.mp4 list"))
        val sent = s3.log.filter { it.startsWith("PUT video.mp4 part") }.map { it.substringAfterLast(' ').toInt() }.toSet()
        assertEquals("only the missing parts", (1..5).toSet() - there, sent)
        assertTrue("events.json isn't sent twice", s3.log.none { it == "PUT events.json" })
        assertArrayEquals(fs[2].file.readBytes(), s3.objects["tcgdex/recordings/20261109T140322Z-7f3a/video.mp4"])
    }

    @Test
    fun lostLocalPartsAreTakenFromS3() {
        val fs = files()
        s3.failures["POST video.mp4 complete"] = MutableList(3) { MockResponse().setResponseCode(503) }
        try { uploader().run(fs); fail() } catch (e: java.io.IOException) { /* later */ }
        // the phone lost which parts it had sent (but not the upload id)
        val state = UploadState(File(dir, "upload.json"))
        state.setParts("video.mp4", emptyMap())
        s3.log.clear()
        uploader().run(fs)
        assertEquals("nothing sent again", 0, s3.log.count { it.startsWith("PUT video.mp4 part") })
        assertArrayEquals(fs[2].file.readBytes(), s3.objects["tcgdex/recordings/20261109T140322Z-7f3a/video.mp4"])
    }

    @Test
    fun temporaryErrorsAreTriedAgainPermanentOnesStop() {
        s3.failures["PUT events.json"] = mutableListOf(MockResponse().setResponseCode(503), MockResponse().setResponseCode(500))
        uploader().run(files())
        assertEquals(3, s3.log.count { it == "PUT events.json" })

        s3.objects.clear(); s3.log.clear()
        s3.failures["PUT events.json"] = mutableListOf(s3.error(403, "AccessDenied"))
        try { uploader(File(dir, "other.json")).run(files()); fail() } catch (e: S3Exception) {
            assertTrue(e.permanent)
            assertEquals("This key may not write to the bucket/prefix", e.friendly)
        }
        assertEquals("no retry on 403", 1, s3.log.count { it == "PUT events.json" })
    }

    @Test
    fun deletingDuringAnUploadAbortsItsParts() {
        val fs = files()
        s3.failures["PUT video.mp4 part 2"] = MutableList(3) { MockResponse().setResponseCode(500) }
        val u = uploader()
        try { u.run(fs); fail() } catch (e: java.io.IOException) { /* stopped */ }
        assertEquals(1, s3.uploads.size)
        u.abortOpenUploads()
        assertTrue(s3.log.contains("DELETE video.mp4 abort"))
        assertTrue("no orphaned parts", s3.uploads.isEmpty())
    }

    @Test
    fun uploadingAgainOverwritesTheSameKeys() {
        uploader(File(dir, "a.json")).run(files())
        val keys = s3.objects.keys.toSet()
        uploader(File(dir, "b.json")).run(files())
        assertEquals(keys, s3.objects.keys.toSet())
        assertEquals(5, keys.size)
    }

    @Test
    fun saveAndTestWritesAndDeletes() {
        assertTrue(client().connectionTest().startsWith("Connected · bucket · write OK"))
        assertTrue(s3.log.any { it.startsWith("PUT ") } && s3.log.any { it.startsWith("DELETE ") })
        assertTrue("nothing left behind", s3.objects.isEmpty())
    }

    // ------------------------------------------------------------------ a real S3: MinIO in the CI
    @Test
    fun minio() {
        val endpoint = InstrumentationRegistry.getArguments().getString("s3Endpoint").orEmpty()
        assumeTrue("no MinIO (CI only)", endpoint.isNotBlank())
        val cfg = S3Config(endpoint, "us-east-1", "recordings-test", "ci/", pathStyle = true, accessKeyId = "minioadmin",
            secret = "minioadmin", allowHttp = true)
        val c = S3Client(cfg)
        assertTrue(c.connectionTest().startsWith("Connected"))

        val video = file("video.mp4", 11 * 1024 * 1024 + 123)
        val fs = listOf(UploadFile("events.json", file("events.json", 2_000), "application/json"), UploadFile("video.mp4", video, "video/mp4"))
        val id = "ci-${System.currentTimeMillis()}"
        // a part already there from an earlier attempt, the phone only knows the upload id
        val key = "ci/recordings/$id/video.mp4"
        val uploadId = c.createMultipartUpload(key, "video/mp4")
        c.uploadPart(key, uploadId, 1, video.readBytes().copyOf(5 * 1024 * 1024))
        val state = UploadState(File(dir, "minio.json")).apply { update("video.mp4") { it.put("uploadId", uploadId) } }
        RecordingUploader(c, id, state, "ci", partSize = 5 * 1024 * 1024, parallel = 3).run(fs)
        assertArrayEquals(video.readBytes(), c.getObject(key))
        val marker = JSONObject(String(c.getObject("ci/recordings/$id/_complete.json")))
        assertEquals(sha(video), marker.getJSONObject("files").getJSONObject("video.mp4").getString("sha256"))

        try { S3Client(cfg.copy(secret = "wrong-secret")).putObject("ci/x.txt", "x".toByteArray()); fail() } catch (e: S3Exception) {
            assertEquals("SignatureDoesNotMatch", e.code); assertEquals("Secret key is wrong", e.friendly)
        }
        try { S3Client(cfg.copy(bucket = "no-such-bucket-here")).putObject("ci/x.txt", "x".toByteArray()); fail() } catch (e: S3Exception) {
            assertEquals("NoSuchBucket", e.code); assertEquals("Bucket not found", e.friendly)
        }
        try { S3Client(cfg.copy(accessKeyId = "nobody")).putObject("ci/x.txt", "x".toByteArray()); fail() } catch (e: S3Exception) {
            assertEquals("InvalidAccessKeyId", e.code)
        }
    }
}

package io.github.pmaxai.cardscanner.recording

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

/** One file of a recording, in upload order. Big ones (the videos) go up in parts. */
data class UploadFile(val name: String, val file: File, val contentType: String)

/**
 * What has been uploaded of one recording, saved on the phone after every step (a part, a
 * file), so an upload continues where it stopped: after a restart, a reboot or a lost
 * connection. S3's own list of parts is asked as well, in case the last step wasn't saved.
 */
class UploadState(private val file: File) {
    private val o: JSONObject = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }

    @Synchronized fun entry(name: String): JSONObject =
        o.optJSONObject("files")?.optJSONObject(name) ?: JSONObject().also { e ->
            (o.optJSONObject("files") ?: JSONObject().also { o.put("files", it) }).put(name, e)
        }

    @Synchronized fun update(name: String, f: (JSONObject) -> Unit) { f(entry(name)); save() }
    @Synchronized fun parts(name: String): Map<Int, String> =
        entry(name).optJSONObject("parts")?.let { p -> p.keys().asSequence().associate { it.toInt() to p.getString(it) } } ?: emptyMap()
    @Synchronized fun addPart(name: String, number: Int, etag: String) =
        update(name) { e -> (e.optJSONObject("parts") ?: JSONObject().also { e.put("parts", it) }).put(number.toString(), etag) }
    @Synchronized fun setParts(name: String, parts: Map<Int, String>) =
        update(name) { e -> e.put("parts", JSONObject().apply { parts.forEach { (n, t) -> put(n.toString(), t) } }) }
    /** multipart uploads that were started and not finished (to abort them when the recording is deleted) */
    @Synchronized fun openUploads(): List<Pair<String, String>> =
        o.optJSONObject("files")?.let { f -> f.keys().asSequence().mapNotNull { n ->
            val e = f.getJSONObject(n)
            if (!e.optBoolean("done") && e.has("uploadId")) n to e.getString("uploadId") else null
        }.toList() } ?: emptyList()
    @get:Synchronized val complete: Boolean get() = o.optBoolean("complete")
    @Synchronized fun markComplete() { o.put("complete", true); save() }

    @Synchronized private fun save() {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }
}

/**
 * Uploads a recording into `<prefix>recordings/<id>/`: the files in the given order (events.json,
 * pack.csv, video.mp4, camera.mp4), then `_complete.json` with every file's size and SHA-256,
 * last: the pipeline's only trigger, so it never sees half a recording. Big files go up as
 * multipart uploads in fixed parts, several at once; every part and file carries Content-MD5.
 * Errors are thrown: the caller decides between waiting and trying again (temporary) and
 * stopping (an S3Exception that is [S3Exception.permanent]).
 */
class RecordingUploader(
    private val client: S3Client,
    private val recordingId: String,
    private val state: UploadState,
    private val appVersion: String,
    private val partSize: Int = 16 * 1024 * 1024,
    private val parallel: Int = 3,
    private val progress: (done: Long, total: Long) -> Unit = { _, _ -> },
) {
    val folder: String get() = "${client.cfg.prefix}recordings/$recordingId/"
    @Volatile private var cancelled = false

    fun cancel() { cancelled = true }

    fun run(files: List<UploadFile>) {
        val total = files.sumOf { it.file.length() }
        val done = AtomicLong(0)
        for (f in files) {
            check(!cancelled) { "cancelled" }
            val size = f.file.length()
            // size and checksum for _complete.json, worked out once
            if (!state.entry(f.name).has("sha256")) {
                val sha = sha256Hex(f.file)
                state.update(f.name) { it.put("sha256", sha).put("bytes", size) }
            }
            if (state.entry(f.name).optBoolean("done")) { done.addAndGet(size); progress(done.get(), total); continue }
            if (size <= partSize) {
                client.putObject(folder + f.name, f.file.readBytes(), f.contentType)
            } else {
                multipart(f, size, done, total)
            }
            state.update(f.name) { it.put("done", true).remove("uploadId") }
            if (size <= partSize) done.addAndGet(size)
            progress(done.get(), total)
        }
        check(!cancelled) { "cancelled" }
        val marker = JSONObject().put("recording_id", recordingId).put("schema", Recording.SCHEMA).put("app_version", appVersion)
            .put("uploaded_at", Instant.now().toString())
            .put("files", JSONObject().apply {
                files.forEach { f -> val e = state.entry(f.name); put(f.name, JSONObject().put("bytes", e.getLong("bytes")).put("sha256", e.getString("sha256"))) }
            })
        client.putObject(folder + "_complete.json", marker.toString(2).toByteArray(Charsets.UTF_8), "application/json")
        state.markComplete()
    }

    private fun multipart(f: UploadFile, size: Long, done: AtomicLong, total: Long) {
        val key = folder + f.name
        val count = ((size + partSize - 1) / partSize).toInt()
        var uploadId = state.entry(f.name).optString("uploadId").takeIf { it.isNotEmpty() }
        if (uploadId != null) {
            // what S3 has is what counts (the last part may have finished without being saved here)
            val have = try { client.listParts(key, uploadId) } catch (e: S3Exception) {
                if (e.code == "NoSuchUpload") null else throw e
            }
            if (have == null) { uploadId = null; state.setParts(f.name, emptyMap()) }
            else state.setParts(f.name, have.filter { p -> p.size < 0 || p.size == partLength(p.number, size) }.associate { it.number to it.etag })
        }
        if (uploadId == null) {
            uploadId = client.createMultipartUpload(key, f.contentType)
            state.update(f.name) { it.put("uploadId", uploadId).put("parts", JSONObject()) }
        }
        val have = state.parts(f.name)
        have.keys.forEach { n -> done.addAndGet(partLength(n, size)) }
        progress(done.get(), total)
        val missing = (1..count).filter { it !in have }
        val pool = Executors.newFixedThreadPool(parallel.coerceAtLeast(1))
        try {
            val buffers = java.util.concurrent.ArrayBlockingQueue<ByteArray>(parallel).apply { repeat(parallel) { add(ByteArray(partSize)) } }
            val jobs: List<Future<*>> = missing.map { n ->
                pool.submit {
                    check(!cancelled) { "cancelled" }
                    val buf = buffers.take()
                    try {
                        val len = partLength(n, size).toInt()
                        RandomAccessFile(f.file, "r").use { raf -> raf.seek((n - 1).toLong() * partSize); raf.readFully(buf, 0, len) }
                        val etag = client.uploadPart(key, uploadId, n, buf, len)
                        state.addPart(f.name, n, etag)
                        progress(done.addAndGet(len.toLong()), total)
                    } finally { buffers.put(buf) }
                }
            }
            jobs.forEach { j -> try { j.get() } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e } }
        } finally { pool.shutdownNow() }
        val parts = state.parts(f.name).map { (n, t) -> S3Part(n, t, partLength(n, size)) }
        check(parts.size == count) { "parts missing: ${count - parts.size}" }
        client.completeMultipartUpload(key, uploadId, parts)
    }

    private fun partLength(n: Int, size: Long): Long = minOf(partSize.toLong(), size - (n - 1).toLong() * partSize)

    /** Deleting a recording during its upload: no orphaned parts stay in the bucket. */
    fun abortOpenUploads() {
        state.openUploads().forEach { (name, id) -> runCatching { client.abortMultipartUpload(folder + name, id) } }
    }

    companion object {
        fun sha256Hex(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().buffered(1 shl 20).use { s ->
                val buf = ByteArray(1 shl 20)
                while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return SigV4.hex(md.digest())
        }
    }
}

package io.github.pmaxai.cardscanner.recording

import android.util.Base64
import io.github.pmaxai.cardscanner.shop.ServerClock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Uploads to any S3-compatible store (AWS S3, Exoscale SOS, MinIO, R2, Wasabi) with a
 * hand-written Signature Version 4 on OkHttp: no SDK. Only what recording needs: put and
 * delete an object, and multipart uploads (create, part, list, complete, abort).
 */
object SigV4 {
    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    fun sha256(b: ByteArray, len: Int = b.size): ByteArray = MessageDigest.getInstance("SHA-256").apply { update(b, 0, len) }.digest()
    fun md5Base64(b: ByteArray, len: Int = b.size): String =
        Base64.encodeToString(MessageDigest.getInstance("MD5").apply { update(b, 0, len) }.digest(), Base64.NO_WRAP)
    val EMPTY_SHA256: String = hex(sha256(ByteArray(0)))

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray(Charsets.UTF_8))

    /** AWS's URI encoding: unreserved characters stay, everything else %XX (upper case); '/' stays in paths. */
    fun uriEncode(s: String, keepSlash: Boolean): String {
        val out = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xff).toChar()
            when {
                c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~' -> out.append(c)
                c == '/' && keepSlash -> out.append(c)
                else -> out.append("%%%02X".format(b.toInt() and 0xff))
            }
        }
        return out.toString()
    }

    /** "a=1&b=" from raw (unencoded) pairs, sorted as AWS wants. */
    fun canonicalQuery(params: List<Pair<String, String>>): String =
        params.map { (k, v) -> uriEncode(k, false) to uriEncode(v, false) }.sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { (k, v) -> "$k=$v" }

    data class Signed(val authorization: String, val canonicalRequest: String, val stringToSign: String)

    /**
     * Signs a request. [canonicalUri] is the already encoded path, [headers] the headers to sign
     * (names any case; host and x-amz-date must be among them), [payloadHash] hex SHA-256 of the body.
     */
    fun sign(
        method: String, canonicalUri: String, query: List<Pair<String, String>>, headers: Map<String, String>,
        payloadHash: String, amzDate: String, region: String, service: String, accessKey: String, secret: String,
    ): Signed {
        val h = headers.entries.associate { it.key.lowercase().trim() to it.value.trim().replace(Regex("\\s+"), " ") }.toSortedMap()
        val signedHeaders = h.keys.joinToString(";")
        val canonical = listOf(method, canonicalUri.ifEmpty { "/" }, canonicalQuery(query),
            h.entries.joinToString("") { "${it.key}:${it.value}\n" }, signedHeaders, payloadHash).joinToString("\n")
        val day = amzDate.take(8)
        val scope = "$day/$region/$service/aws4_request"
        val toSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${hex(sha256(canonical.toByteArray(Charsets.UTF_8)))}"
        var key = hmac("AWS4$secret".toByteArray(Charsets.UTF_8), day)
        key = hmac(key, region)
        key = hmac(key, service)
        key = hmac(key, "aws4_request")
        val signature = hex(hmac(key, toSign))
        return Signed("AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature", canonical, toSign)
    }

    fun amzDate(epochMs: Long): String =
        SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(epochMs))
}

/** Where recordings go. [prefix] is "" or ends with "/". */
data class S3Config(
    val endpoint: String,
    val region: String,
    val bucket: String,
    val prefix: String,
    val pathStyle: Boolean,
    val accessKeyId: String,
    val secret: String,
    val allowHttp: Boolean = false,
) {
    override fun toString() = "S3Config($endpoint, $region, $bucket, prefix=$prefix, pathStyle=$pathStyle, key=$accessKeyId, secret=…)"

    companion object {
        /** "tcgdex" -> "tcgdex/", "/a/b/" -> "a/b/", "" -> "" */
        fun normalizePrefix(p: String): String = p.trim().trim('/').let { if (it.isEmpty()) "" else "$it/" }
    }
}

class S3Exception(val status: Int, val code: String, message: String?) : IOException(message ?: code) {
    /** a setting is wrong: retrying won't help until it changes */
    val permanent: Boolean
        get() = status in setOf(400, 401, 403, 404, 405) && code != "RequestTimeTooSkewed" && code != "NoSuchUpload"

    val friendly: String
        get() = when (code) {
            "InvalidAccessKeyId" -> "Access key ID unknown"
            "SignatureDoesNotMatch" -> "Secret key is wrong"
            "AccessDenied" -> "This key may not write to the bucket/prefix"
            "NoSuchBucket" -> "Bucket not found"
            "RequestTimeTooSkewed" -> "Phone clock is off"
            "AuthorizationHeaderMalformed" -> "Region doesn't fit this endpoint" + (message?.let { " ($it)" } ?: "")
            "PermanentRedirect" -> "The bucket is in another region or needs another endpoint"
            else -> "S3 answered $status ($code)" + (message?.takeIf { it != code }?.let { ": $it" } ?: "")
        }
}

data class S3Part(val number: Int, val etag: String, val size: Long)

class S3Client(
    val cfg: S3Config,
    http: OkHttpClient? = null,
    val clock: ServerClock = ServerClock(),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val http = http ?: OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).writeTimeout(90, TimeUnit.SECONDS)
        .build()
    private val base: HttpUrl = cfg.endpoint.trim().trimEnd('/').toHttpUrlOrNull()
        ?.also { require(it.scheme == "https" || cfg.allowHttp) { "only https (or tick Allow http for testing)" } }
        ?: throw IllegalArgumentException("not an endpoint URL: ${cfg.endpoint}")

    fun putObject(key: String, body: ByteArray, contentType: String = "application/octet-stream"): String =
        call("PUT", key, emptyList(), body, body.size, contentType).etag

    fun deleteObject(key: String) { call("DELETE", key, emptyList(), null, 0, null) }

    fun getObject(key: String): ByteArray = call("GET", key, emptyList(), null, 0, null).bytes

    fun createMultipartUpload(key: String, contentType: String): String {
        val r = call("POST", key, listOf("uploads" to ""), ByteArray(0), 0, contentType)
        return xml(r.text, "UploadId") ?: throw S3Exception(r.status, "NoUploadId", "no UploadId in the answer")
    }

    fun uploadPart(key: String, uploadId: String, number: Int, body: ByteArray, len: Int = body.size): String =
        call("PUT", key, listOf("partNumber" to number.toString(), "uploadId" to uploadId), body, len, null).etag

    /** The parts S3 already has (paged). */
    fun listParts(key: String, uploadId: String): List<S3Part> {
        val out = mutableListOf<S3Part>()
        var marker: String? = null
        do {
            val q = mutableListOf("uploadId" to uploadId, "max-parts" to "1000")
            marker?.let { q += "part-number-marker" to it }
            val text = call("GET", key, q, null, 0, null).text
            Regex("<Part>(.*?)</Part>", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { m ->
                val p = m.groupValues[1]
                out += S3Part(xml(p, "PartNumber")!!.toInt(), xml(p, "ETag")!!.replace("&quot;", "\""), xml(p, "Size")?.toLongOrNull() ?: -1)
            }
            marker = if (xml(text, "IsTruncated") == "true") xml(text, "NextPartNumberMarker") else null
        } while (marker != null)
        return out
    }

    fun completeMultipartUpload(key: String, uploadId: String, parts: List<S3Part>) {
        val body = buildString {
            append("<CompleteMultipartUpload>")
            parts.sortedBy { it.number }.forEach { append("<Part><PartNumber>${it.number}</PartNumber><ETag>${it.etag}</ETag></Part>") }
            append("</CompleteMultipartUpload>")
        }.toByteArray(Charsets.UTF_8)
        val r = call("POST", key, listOf("uploadId" to uploadId), body, body.size, "application/xml")
        // S3 may answer 200 and still report an error in the body
        if (r.text.contains("<Error>")) throw S3Exception(500, xml(r.text, "Code") ?: "InternalError", xml(r.text, "Message"))
    }

    fun abortMultipartUpload(key: String, uploadId: String) {
        try { call("DELETE", key, listOf("uploadId" to uploadId), null, 0, null) }
        catch (e: S3Exception) { if (e.code != "NoSuchUpload") throw e }
    }

    /** Save & test: writes a small object under the prefix and deletes it again. */
    fun connectionTest(): String {
        val key = cfg.prefix + "_connection-test/" + java.util.UUID.randomUUID() + ".txt"
        putObject(key, "connection test from the scanner app".toByteArray(), "text/plain")
        val deleted = runCatching { deleteObject(key) }.isSuccess
        return "Connected · ${cfg.bucket} · write OK" + if (deleted) "" else " (the test file couldn't be deleted: the key may only write)"
    }

    private class Answer(val status: Int, val etag: String, val bytes: ByteArray) { val text: String get() = String(bytes, Charsets.UTF_8) }

    private fun url(key: String, query: List<Pair<String, String>>): Pair<HttpUrl, String> {
        val enc = SigV4.uriEncode(key, keepSlash = true)
        val path = if (cfg.pathStyle) "/${SigV4.uriEncode(cfg.bucket, false)}/$enc" else "/$enc"
        val host = if (cfg.pathStyle) base.host else "${cfg.bucket}.${base.host}"
        val prefixPath = base.encodedPath.trimEnd('/')
        val b = HttpUrl.Builder().scheme(base.scheme).host(host).port(base.port).encodedPath(prefixPath + path)
        if (query.isNotEmpty()) b.encodedQuery(SigV4.canonicalQuery(query))
        return b.build() to (prefixPath + path)
    }

    /** One request, signed; 5xx and dropped connections are tried 3 times, a wrong clock is corrected once. */
    private fun call(method: String, key: String, query: List<Pair<String, String>>, body: ByteArray?, len: Int, contentType: String?): Answer {
        var attempt = 0
        var clockFixed = false
        while (true) {
            attempt++
            try {
                return once(method, key, query, body, len, contentType)
            } catch (e: S3Exception) {
                if (e.code == "RequestTimeTooSkewed" && !clockFixed) { clockFixed = true; continue }
                if ((e.status >= 500 || e.status == 429) && attempt < 3) { sleep(1000L shl (attempt - 1)); continue }
                throw e
            } catch (e: IOException) {
                if (attempt < 3) { sleep(1000L shl (attempt - 1)); continue }
                throw e
            }
        }
    }

    private fun once(method: String, key: String, query: List<Pair<String, String>>, body: ByteArray?, len: Int, contentType: String?): Answer {
        val (url, canonicalUri) = url(key, query)
        val payloadHash = if (body == null) SigV4.EMPTY_SHA256 else SigV4.hex(SigV4.sha256(body, len))
        val amzDate = SigV4.amzDate(clock.nowSeconds() * 1000)
        val hostHeader = if (url.port == HttpUrl.defaultPort(url.scheme)) url.host else "${url.host}:${url.port}"
        val headers = linkedMapOf("host" to hostHeader, "x-amz-date" to amzDate, "x-amz-content-sha256" to payloadHash)
        if (body != null && len > 0) headers["content-md5"] = SigV4.md5Base64(body, len)
        if (contentType != null) headers["content-type"] = contentType
        val signed = SigV4.sign(method, canonicalUri, query, headers, payloadHash, amzDate, cfg.region, "s3", cfg.accessKeyId, cfg.secret)
        val rb = Request.Builder().url(url)
        headers.forEach { (k, v) -> if (k != "host") rb.header(k, v) }
        rb.header("Authorization", signed.authorization)
        val reqBody = when {
            body != null -> body.toRequestBody(contentType?.toMediaTypeOrNull(), 0, len)
            method == "POST" || method == "PUT" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        rb.method(method, reqBody)
        http.newCall(rb.build()).execute().use { res ->
            res.headers.getDate("Date")?.let { clock.observe(it) }
            val bytes = res.body?.bytes() ?: ByteArray(0)
            if (!res.isSuccessful) {
                val text = String(bytes, Charsets.UTF_8)
                throw S3Exception(res.code, xml(text, "Code") ?: "http_${res.code}", xml(text, "Message"))
            }
            return Answer(res.code, res.header("ETag") ?: "", bytes)
        }
    }

    companion object {
        fun xml(text: String, tag: String): String? =
            Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)?.trim()
    }
}

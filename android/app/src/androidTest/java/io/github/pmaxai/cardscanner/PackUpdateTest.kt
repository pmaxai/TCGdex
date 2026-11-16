package io.github.pmaxai.cardscanner

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pmaxai.cardscanner.account.AccountApi
import io.github.pmaxai.cardscanner.data.PackUpdate
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The store editions' nightly data pack (`GET /pack`): only changed files are downloaded, each
 * checked; an interrupted download goes on where it stopped; a complete set waits and takes the
 * pack's place at the next start; a pack for another model is left alone.
 */
@RunWith(AndroidJUnit4::class)
class PackUpdateTest {
    private lateinit var server: MockWebServer
    private lateinit var root: File
    private val fetched = CopyOnWriteArrayList<String>()
    @Volatile private var cutOnce: String? = null
    @Volatile private var model = "mnv3_art"

    private val catalogOld = ByteArray(300_000) { (it % 7).toByte() }
    private val catalogNew = ByteArray(300_000) { (it % 11).toByte() }
    private val index = ByteArray(200_000) { (it % 13).toByte() }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun manifest(version: String, catalog: ByteArray, m: String = "mnv3_art") = JSONObject().put("version", version).put("model", m).put("dim", 512)
        .put("files", JSONObject().put("catalog.sqlite", JSONObject().put("bytes", catalog.size).put("sha256", sha(catalog)))
            .put("index.f16", JSONObject().put("bytes", index.size).put("sha256", sha(index))))

    private val newVersion = "2026-11-16T00:11:25Z"
    private fun newManifest() = manifest(newVersion, catalogNew, model).toString().toByteArray()

    private fun files(): Map<String, ByteArray> = mapOf("catalog.sqlite" to catalogNew, "index.f16" to index, "pack.json" to newManifest())

    @Before fun setUp() {
        root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "pack-update-test").apply { deleteRecursively(); mkdirs() }
        // the pack in use: yesterday's
        File(root, "pack").apply { mkdirs() }.let { d ->
            File(d, "catalog.sqlite").writeBytes(catalogOld)
            File(d, "index.f16").writeBytes(index)
            File(d, "pack.json").writeText(manifest("2026-11-15T00:10:00Z", catalogOld).toString())
        }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                if (path == "/api/app/v1/pack") {
                    val a = JSONArray()
                    files().forEach { (n, b) -> a.put(JSONObject().put("name", n).put("size", b.size).put("sha256", sha(b))
                        .put("url", server.url("/files/$n?sig=x").toString())) }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject().put("version", newVersion).put("files", a).toString())
                }
                val name = path.removePrefix("/files/")
                val b = files()[name] ?: return MockResponse().setResponseCode(404)
                val range = request.getHeader("Range")
                fetched += name + (range?.let { " $it" } ?: "")
                if (cutOnce == name && range == null) {
                    cutOnce = null
                    // half of it, then the connection breaks
                    return MockResponse().setBody(Buffer().write(b)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                }
                if (range != null) {
                    val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                    return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $from-${b.size - 1}/${b.size}")
                        .setBody(Buffer().write(b, from, b.size - from))
                }
                return MockResponse().setBody(Buffer().write(b))
            }
        }
        server.start()
    }

    @After fun tearDown() { server.shutdown(); root.deleteRecursively() }

    private fun api() = AccountApi("http://${server.hostName}:${server.port}", "test")

    @Test
    fun onlyChangedFilesAreDownloadedAndUsedFromTheNextStart() {
        assertEquals(newVersion, PackUpdate.download(root, api(), "token"))
        assertEquals("the unchanged index comes from the pack in use", listOf("catalog.sqlite", "pack.json"), fetched.sorted())
        assertEquals(newVersion, PackUpdate.waiting(root))
        // nothing newer: nothing again
        assertNull(PackUpdate.download(root, api(), "token"))
        assertEquals(2, fetched.size)
        // the next start
        PackUpdate.promote(root, manifest("2026-11-01T00:00:00Z", catalogOld))
        assertArrayEquals(catalogNew, File(root, "pack/catalog.sqlite").readBytes())
        assertArrayEquals(index, File(root, "pack/index.f16").readBytes())
        assertEquals(newVersion, PackUpdate.versionOf(File(root, "pack")))
        assertNull("nothing waits any more", PackUpdate.waiting(root))
        assertFalse(File(root, "pack/ready").exists())
    }

    @Test
    fun aBrokenDownloadGoesOnWhereItStopped() {
        cutOnce = "catalog.sqlite"
        val first = runCatching { PackUpdate.download(root, api(), "token") }
        Log.i("PackUpdate", "first try: $first, fetched $fetched")
        assertTrue("the first try stops", first.isFailure)
        assertEquals(newVersion, PackUpdate.download(root, api(), "token"))
        assertTrue("the second asks for the rest: $fetched", fetched.any { it.startsWith("catalog.sqlite bytes=") })
        PackUpdate.promote(root, manifest("2026-11-01T00:00:00Z", catalogOld))
        assertArrayEquals(catalogNew, File(root, "pack/catalog.sqlite").readBytes())
    }

    @Test
    fun aPackForAnotherModelIsLeftAlone() {
        model = "dinov2s_art"
        assertNull(PackUpdate.download(root, api(), "token"))
        assertNull(PackUpdate.waiting(root))
        assertEquals("only its manifest was fetched", listOf("pack.json"), fetched.toList())
        assertArrayEquals(catalogOld, File(root, "pack/catalog.sqlite").readBytes())
    }

    @Test
    fun aWaitingPackForAnotherModelIsNotUsed() {
        assertEquals(newVersion, PackUpdate.download(root, api(), "token"))
        // the app was updated meanwhile and brings another model
        PackUpdate.promote(root, manifest("2026-11-17T00:00:00Z", catalogOld, m = "dinov2s_art"))
        assertArrayEquals("the pack in use stays", catalogOld, File(root, "pack/catalog.sqlite").readBytes())
        assertNull(PackUpdate.waiting(root))
    }
}

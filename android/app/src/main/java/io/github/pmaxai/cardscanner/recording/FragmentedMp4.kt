package io.github.pmaxai.cardscanner.recording

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * A fragmented MP4 writer for one H.264 video track and an optional AAC audio track.
 * Every second (at each video keyframe) a fragment (moof + mdat) is appended, so the file is
 * playable up to its last fragment at any moment: a crash or force-stop loses about a
 * second, and [repair] cuts off a half-written fragment. Android's MediaMuxer only writes
 * plain MP4, whose index comes at the very end; that is why this exists.
 *
 * Times: samples come with presentation times in microseconds on one clock (the encoders'
 * timestamps, CLOCK_MONOTONIC); the first video sample is time 0 of the file.
 */
class FragmentedMp4Writer(private val file: File, private val withAudio: Boolean) {
    private class Sample(val ptsUs: Long, val data: ByteArray, val key: Boolean)

    private val out = FileOutputStream(file)
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var started = false
    private var seq = 0
    private var mvhdDurationAt = -1L
    private var mehdAt = -1L
    private val video = ArrayList<Sample>()
    private val audio = ArrayList<Sample>()
    /** pts of the first video sample: time 0 */
    var zeroUs: Long = -1
        private set
    private var lastVideoPtsUs = -1L
    private var audioRate = 48000
    private var audioSamplesWritten = 0L
    private var audioStartTicks = -1L
    private var frameUs = 33_333L
    var bytesWritten = 0L
        private set

    @Synchronized fun setVideoFormat(f: MediaFormat) { videoFormat = f; maybeStart() }
    @Synchronized fun setAudioFormat(f: MediaFormat) { audioFormat = f; audioRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); maybeStart() }

    /** An encoded video frame (Annex B, as MediaCodec gives it). */
    @Synchronized fun writeVideo(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 || info.size == 0) return
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (zeroUs < 0) { if (!key) return; zeroUs = info.presentationTimeUs }
        if (info.presentationTimeUs <= lastVideoPtsUs) return          // never backwards
        val bytes = ByteArray(info.size)
        buf.position(info.offset); buf.get(bytes)
        // a new keyframe: what came before it is one fragment
        if (key && video.isNotEmpty() && started) flush(info.presentationTimeUs)
        if (lastVideoPtsUs >= 0) frameUs = (info.presentationTimeUs - lastVideoPtsUs).coerceIn(1_000, 1_000_000)
        lastVideoPtsUs = info.presentationTimeUs
        video += Sample(info.presentationTimeUs, annexBToLengthPrefixed(bytes), key)
    }

    /** An encoded AAC frame (1024 samples). */
    @Synchronized fun writeAudio(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 || info.size == 0) return
        if (zeroUs < 0 || info.presentationTimeUs < zeroUs) return     // nothing before the first picture
        val bytes = ByteArray(info.size)
        buf.position(info.offset); buf.get(bytes)
        audio += Sample(info.presentationTimeUs, bytes, true)
    }

    /** The last fragment, the total duration into the header, and the file closed. Returns the duration in ms. */
    @Synchronized fun finish(): Long {
        if (started && video.isNotEmpty()) flush(lastVideoPtsUs + frameUs)
        out.flush(); out.fd.sync(); out.close()
        val durationMs = if (zeroUs >= 0 && lastVideoPtsUs >= 0) (lastVideoPtsUs + frameUs - zeroUs) / 1000 else 0
        if (mvhdDurationAt >= 0) RandomAccessFile(file, "rw").use { raf ->
            raf.seek(mvhdDurationAt); raf.writeInt(durationMs.toInt())
            raf.seek(mehdAt); raf.writeLong(durationMs)
        }
        return durationMs
    }

    @Synchronized fun abandon() { runCatching { out.close() } }

    private fun maybeStart() {
        if (started || videoFormat == null || (withAudio && audioFormat == null)) return
        val head = ByteArrayOutputStream()
        head.write(box("ftyp") { it.write("isom".toByteArray()); it.writeInt(512); listOf("isom", "iso6", "iso2", "avc1", "mp41").forEach { b -> it.write(b.toByteArray()) } })
        val moovStart = head.size().toLong()
        val moov = moov()
        head.write(moov.bytes)
        mvhdDurationAt = moovStart + moov.mvhdDurationAt
        mehdAt = moovStart + moov.mehdAt
        write(head.toByteArray())
        started = true
    }

    private fun write(b: ByteArray) { out.write(b); bytesWritten += b.size }

    /** Writes the buffered video samples (pts < [untilUs] for audio too) as one fragment. */
    private fun flush(nextVideoPtsUs: Long) {
        if (video.isEmpty()) return
        val v = ArrayList(video); video.clear()
        val a = audio.filter { it.ptsUs < nextVideoPtsUs }
        audio.removeAll(a.toSet())
        val vDur = v.mapIndexed { i, s -> (if (i + 1 < v.size) v[i + 1].ptsUs else nextVideoPtsUs) - s.ptsUs }
        val vData = v.sumOf { it.data.size }
        val aData = a.sumOf { it.data.size }
        val seqNo = ++seq
        if (a.isNotEmpty() && audioStartTicks < 0) audioStartTicks = us(a.first().ptsUs - zeroUs, audioRate)
        // moof's size first (data offsets point past it)
        fun moof(dataStart: Long): ByteArray = box("moof") { m ->
            m.write(fullBox("mfhd", 0, 0) { it.writeInt(seqNo) })
            m.write(box("traf") { t ->
                t.write(fullBox("tfhd", 0, 0x020000) { it.writeInt(1) })
                t.write(fullBox("tfdt", 1, 0) { it.writeLong(us(v.first().ptsUs - zeroUs, VIDEO_TIMESCALE)) })
                t.write(fullBox("trun", 0, 0x000701) {
                    it.writeInt(v.size); it.writeInt(dataStart.toInt())
                    v.forEachIndexed { i, s ->
                        it.writeInt(us(vDur[i], VIDEO_TIMESCALE).toInt()); it.writeInt(s.data.size)
                        it.writeInt(if (s.key) 0x02000000 else 0x01010000)
                    }
                })
            })
            if (a.isNotEmpty()) m.write(box("traf") { t ->
                t.write(fullBox("tfhd", 0, 0x020000) { it.writeInt(2) })
                t.write(fullBox("tfdt", 1, 0) { it.writeLong(audioStartTicks + audioSamplesWritten) })
                t.write(fullBox("trun", 0, 0x000701) {
                    it.writeInt(a.size); it.writeInt((dataStart + vData).toInt())
                    a.forEach { s -> it.writeInt(1024); it.writeInt(s.data.size); it.writeInt(0x02000000) }
                })
            })
        }
        val size = moof(0).size
        val m = moof(size + 8L)
        val mdatHeader = ByteBuffer.allocate(8).putInt(8 + vData + aData).put("mdat".toByteArray()).array()
        write(m); write(mdatHeader)
        v.forEach { write(it.data) }
        a.forEach { write(it.data) }
        out.flush()
        audioSamplesWritten += a.size * 1024L
    }

    private class Moov(val bytes: ByteArray, val mvhdDurationAt: Long, val mehdAt: Long)

    private fun moov(): Moov {
        val vf = videoFormat!!
        val w = vf.getInteger(MediaFormat.KEY_WIDTH)
        val h = vf.getInteger(MediaFormat.KEY_HEIGHT)
        // SPS and PPS: csd-0 and csd-1, or both in csd-0 (some encoders)
        val nals = listOfNotNull(vf.getByteBuffer("csd-0"), vf.getByteBuffer("csd-1")).flatMap { nalsOf(it) }
        val sps = nals.first { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 }
        val pps = nals.first { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 }
        val trakV = box("trak") { t ->
            t.write(fullBox("tkhd", 0, 3) { tkhd(it, 1, 0, w, h) })
            t.write(box("mdia") { md ->
                md.write(fullBox("mdhd", 0, 0) { mdhd(it, VIDEO_TIMESCALE) })
                md.write(hdlr("vide", "VideoHandler"))
                md.write(box("minf") { mi ->
                    mi.write(fullBox("vmhd", 0, 1) { it.writeShort(0); it.writeShort(0); it.writeShort(0); it.writeShort(0) })
                    mi.write(dinf())
                    mi.write(box("stbl") { st ->
                        st.write(fullBox("stsd", 0, 0) { sd ->
                            sd.writeInt(1)
                            sd.write(box("avc1") { e ->
                                e.write(ByteArray(6)); e.writeShort(1)
                                e.write(ByteArray(16)); e.writeShort(w); e.writeShort(h)
                                e.writeInt(0x00480000); e.writeInt(0x00480000); e.writeInt(0); e.writeShort(1)
                                e.write(ByteArray(32)); e.writeShort(0x18); e.writeShort(-1)
                                e.write(box("avcC") { c ->
                                    c.writeByte(1); c.writeByte(sps[1].toInt()); c.writeByte(sps[2].toInt()); c.writeByte(sps[3].toInt())
                                    c.writeByte(0xFF); c.writeByte(0xE1); c.writeShort(sps.size); c.write(sps)
                                    c.writeByte(1); c.writeShort(pps.size); c.write(pps)
                                })
                            })
                        })
                        emptyTables(st)
                    })
                })
            })
        }
        val trakA = audioFormat?.let { af ->
            val channels = af.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val asc = af.getByteBuffer("csd-0")!!.let { b -> ByteArray(b.remaining()).also { b.duplicate().get(it) } }
            box("trak") { t ->
                t.write(fullBox("tkhd", 0, 3) { tkhd(it, 2, 0x0100, 0, 0) })
                t.write(box("mdia") { md ->
                    md.write(fullBox("mdhd", 0, 0) { mdhd(it, audioRate) })
                    md.write(hdlr("soun", "SoundHandler"))
                    md.write(box("minf") { mi ->
                        mi.write(fullBox("smhd", 0, 0) { it.writeShort(0); it.writeShort(0) })
                        mi.write(dinf())
                        mi.write(box("stbl") { st ->
                            st.write(fullBox("stsd", 0, 0) { sd ->
                                sd.writeInt(1)
                                sd.write(box("mp4a") { e ->
                                    e.write(ByteArray(6)); e.writeShort(1)
                                    e.write(ByteArray(8)); e.writeShort(channels); e.writeShort(16); e.writeShort(0); e.writeShort(0)
                                    e.writeInt(audioRate shl 16)
                                    e.write(fullBox("esds", 0, 0) { es ->
                                        val dsi = descriptor(0x05, asc)
                                        val dcd = descriptor(0x04, ByteArrayOutputStream().also { b -> DataOutputStream(b).run {
                                            writeByte(0x40); writeByte(0x15); writeByte(0); writeShort(0)
                                            writeInt(128_000); writeInt(128_000); write(dsi)
                                        } }.toByteArray())
                                        val sl = descriptor(0x06, byteArrayOf(0x02))
                                        es.write(descriptor(0x03, ByteArrayOutputStream().also { b -> DataOutputStream(b).run {
                                            writeShort(0); writeByte(0); write(dcd); write(sl)
                                        } }.toByteArray()))
                                    })
                                })
                            })
                            emptyTables(st)
                        })
                    })
                })
            }
        }
        val mvhd = fullBox("mvhd", 0, 0) { m ->
            m.writeInt(0); m.writeInt(0); m.writeInt(1000); m.writeInt(0)          // duration patched at the end
            m.writeInt(0x00010000); m.writeShort(0x0100); m.write(ByteArray(10)); matrix(m); m.write(ByteArray(24))
            m.writeInt(if (trakA != null) 3 else 2)
        }
        val mehd = fullBox("mehd", 1, 0) { it.writeLong(0) }                         // patched at the end
        val mvex = box("mvex") { mx ->
            mx.write(mehd)
            mx.write(fullBox("trex", 0, 0) { it.writeInt(1); it.writeInt(1); it.writeInt(0); it.writeInt(0); it.writeInt(0) })
            if (trakA != null) mx.write(fullBox("trex", 0, 0) { it.writeInt(2); it.writeInt(1); it.writeInt(0); it.writeInt(0); it.writeInt(0) })
        }
        val body = ByteArrayOutputStream()
        body.write(mvhd); body.write(trakV); trakA?.let { body.write(it) }
        val mvexAt = 8L + body.size()
        body.write(mvex)
        val bytes = ByteBuffer.allocate(8 + body.size()).putInt(8 + body.size()).put("moov".toByteArray()).put(body.toByteArray()).array()
        // mvhd starts at 8: header 8 + version/flags 4 + creation 4 + modification 4 + timescale 4
        return Moov(bytes, 8L + 24, mvexAt + 8 + 12)
    }

    private fun emptyTables(st: DataOutputStream) {
        st.write(fullBox("stts", 0, 0) { it.writeInt(0) })
        st.write(fullBox("stsc", 0, 0) { it.writeInt(0) })
        st.write(fullBox("stsz", 0, 0) { it.writeInt(0); it.writeInt(0) })
        st.write(fullBox("stco", 0, 0) { it.writeInt(0) })
    }

    private fun tkhd(d: DataOutputStream, id: Int, volume: Int, w: Int, h: Int) {
        d.writeInt(0); d.writeInt(0); d.writeInt(id); d.writeInt(0); d.writeInt(0)
        d.write(ByteArray(8)); d.writeShort(0); d.writeShort(0); d.writeShort(volume); d.writeShort(0)
        matrix(d); d.writeInt(w shl 16); d.writeInt(h shl 16)
    }

    private fun mdhd(d: DataOutputStream, timescale: Int) {
        d.writeInt(0); d.writeInt(0); d.writeInt(timescale); d.writeInt(0); d.writeShort(0x55C4); d.writeShort(0)
    }

    private fun hdlr(type: String, name: String) = fullBox("hdlr", 0, 0) {
        it.writeInt(0); it.write(type.toByteArray()); it.write(ByteArray(12)); it.write((name + "\u0000").toByteArray())
    }

    private fun dinf() = box("dinf") { di -> di.write(fullBox("dref", 0, 0) { it.writeInt(1); it.write(fullBox("url ", 0, 1) {}) }) }

    private fun matrix(d: DataOutputStream) {
        intArrayOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000).forEach { d.writeInt(it) }
    }

    companion object {
        const val VIDEO_TIMESCALE = 90_000

        private fun us(us: Long, timescale: Int): Long = Math.round(us * timescale / 1_000_000.0)

        fun box(type: String, fill: (DataOutputStream) -> Unit): ByteArray {
            val body = ByteArrayOutputStream()
            DataOutputStream(body).also(fill).flush()
            return ByteBuffer.allocate(8 + body.size()).putInt(8 + body.size()).put(type.toByteArray(Charsets.US_ASCII)).put(body.toByteArray()).array()
        }

        fun fullBox(type: String, version: Int, flags: Int, fill: (DataOutputStream) -> Unit): ByteArray =
            box(type) { it.writeInt((version shl 24) or (flags and 0xFFFFFF)); fill(it) }

        private fun descriptor(tag: Int, body: ByteArray): ByteArray {
            val n = body.size
            return byteArrayOf(tag.toByte(), (0x80 or (n shr 21 and 0x7F)).toByte(), (0x80 or (n shr 14 and 0x7F)).toByte(),
                (0x80 or (n shr 7 and 0x7F)).toByte(), (n and 0x7F).toByte()) + body
        }

        /** The NAL units of an Annex B buffer (without start codes). */
        fun nalsOf(b: ByteBuffer): List<ByteArray> {
            val a = ByteArray(b.remaining()).also { b.duplicate().get(it) }
            val lp = annexBToLengthPrefixed(a)
            val out = ArrayList<ByteArray>()
            var i = 0
            while (i + 4 <= lp.size) {
                val n = ByteBuffer.wrap(lp, i, 4).int
                if (n < 0 || i + 4 + n > lp.size) break
                out += lp.copyOfRange(i + 4, i + 4 + n); i += 4 + n
            }
            return out
        }

        /** Annex B (start codes) to MP4's 4-byte length prefixes. */
        fun annexBToLengthPrefixed(a: ByteArray): ByteArray {
            val starts = ArrayList<IntArray>()          // [start of NAL, length of the start code]
            var i = 0
            while (i + 3 <= a.size) {
                if (a[i] == 0.toByte() && a[i + 1] == 0.toByte()) {
                    if (a[i + 2] == 1.toByte()) { starts += intArrayOf(i + 3, 3); i += 3; continue }
                    if (i + 4 <= a.size && a[i + 2] == 0.toByte() && a[i + 3] == 1.toByte()) { starts += intArrayOf(i + 4, 4); i += 4; continue }
                }
                i++
            }
            if (starts.isEmpty()) return ByteBuffer.allocate(4 + a.size).putInt(a.size).put(a).array()   // already one NAL
            val out = ByteArrayOutputStream(a.size + 16)
            val d = DataOutputStream(out)
            starts.forEachIndexed { k, s ->
                val end = if (k + 1 < starts.size) starts[k + 1][0] - starts[k + 1][1] else a.size
                d.writeInt(end - s[0]); d.write(a, s[0], end - s[0])
            }
            return out.toByteArray()
        }

        /**
         * After a crash: cut off a fragment that was only half written, so every player reads the
         * file. Returns the length kept (0: not an MP4 we can use).
         */
        fun repair(file: File): Long {
            RandomAccessFile(file, "rw").use { raf ->
                val len = raf.length()
                var pos = 0L
                var lastGood = 0L
                var sawMoov = false
                while (pos + 8 <= len) {
                    raf.seek(pos)
                    val size = raf.readInt().toLong() and 0xFFFFFFFFL
                    val type = ByteArray(4).also { raf.readFully(it) }.toString(Charsets.US_ASCII)
                    if (size < 8 || pos + size > len) break
                    if (type == "moov") sawMoov = true
                    pos += size
                    // a fragment is complete once its mdat is complete
                    if (type == "moov" || type == "mdat") lastGood = pos
                }
                if (!sawMoov) return 0
                if (lastGood < len) raf.setLength(lastGood)
                return lastGood
            }
        }
    }
}

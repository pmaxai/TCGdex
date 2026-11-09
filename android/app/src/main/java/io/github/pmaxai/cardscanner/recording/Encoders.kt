package io.github.pmaxai.cardscanner.recording

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import android.view.Surface
import java.io.File
import kotlin.math.roundToInt

/** Raw sound for the recording: 16-bit mono PCM with the time of its first sample (System.nanoTime's clock). */
interface PcmSource {
    val sampleRate: Int
    fun start()
    /** Fills [buf]; returns the bytes read and the time of the first sample, or null when stopped. */
    fun read(buf: ByteArray): Pair<Int, Long>?
    fun stop()
}

/** The microphone. Times from AudioRecord's own timestamps (CLOCK_MONOTONIC), so sound and picture stay together. */
class MicSource(override val sampleRate: Int = 48_000) : PcmSource {
    private var rec: AudioRecord? = null
    private var frames = 0L
    private val ts = AudioTimestamp()

    @SuppressLint("MissingPermission")      // asked for when the microphone is switched on
    override fun start() {
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        rec = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, 2048 * 4)).also { it.startRecording() }
    }

    override fun read(buf: ByteArray): Pair<Int, Long>? {
        val r = rec ?: return null
        val n = r.read(buf, 0, buf.size)
        if (n <= 0) return null
        val first = frames
        frames += n / 2
        val t = if (r.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS)
            ts.nanoTime + (first - ts.framePosition) * 1_000_000_000L / sampleRate
        else System.nanoTime() - (n / 2) * 1_000_000_000L / sampleRate
        return n to t
    }

    override fun stop() { runCatching { rec?.stop() }; rec?.release(); rec = null }
}

/**
 * The recording's encoders: H.264 from a Surface (the screen, via MediaProjection's virtual
 * display) and AAC from a [PcmSource], into a [FragmentedMp4Writer]. A constant 30 fps through
 * a [FramePacer] (frames above it dropped, a still screen repeats its last frame), a keyframe
 * every second, no B-frames. Where GL can't be set up, the screen goes straight into the
 * encoder (then the frame rate follows the screen, at most 30 where the encoder can limit it).
 */
class Encoders(
    val file: File,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    private val audioSource: PcmSource?,
    val fps: Int = 30,
    /** the first frame is in the file: its time (System.nanoTime's clock) is the video's 0 */
    private val onFirstFrame: (Long) -> Unit = {},
) {
    val writer = FragmentedMp4Writer(file, audioSource != null)
    private var video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private var audio: MediaCodec? = null
    /** where the screen is drawn (the pacer's surface, or the encoder's own) */
    lateinit var inputSurface: Surface
        private set
    private var codecSurface: Surface? = null
    private var pacer: FramePacer? = null
    /** true: frames go through the [FramePacer] (constant frame rate) */
    val paced: Boolean get() = pacer != null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    @Volatile private var stopping = false
    @Volatile var failure: Throwable? = null
        private set

    private fun videoFormat(paced: Boolean) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        // the pacer repeats and drops frames itself; the encoder's own repeat would add frames between its ticks
        if (!paced) setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps)
        if (Build.VERSION.SDK_INT >= 29) {
            if (!paced) setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
    }

    fun prepare(): Encoders {
        // paced: a constant frame rate; if GL can't be set up on this phone, the screen goes straight in
        val paced = runCatching {
            video.configure(videoFormat(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val cs = video.createInputSurface()
            codecSurface = cs
            FramePacer(cs, width, height, fps) { t -> failure = t }.start()
        }.onFailure { Log.w(TAG, "no frame pacer, the screen goes straight into the encoder", it) }.getOrNull()
        if (paced != null) {
            pacer = paced
            inputSurface = paced.surface
        } else {
            runCatching { codecSurface?.release() }
            runCatching { video.release() }
            video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            video.configure(videoFormat(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = video.createInputSurface().also { codecSurface = it }
        }
        audioSource?.let { src ->
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, src.sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { it.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
        }
        return this
    }

    fun start() {
        video.start()
        videoThread = Thread({ drainVideo() }, "rec-video").also { it.start() }
        audio?.let { a ->
            a.start()
            audioSource!!.start()
            audioThread = Thread({ runAudio(a, audioSource) }, "rec-audio").also { it.start() }
        }
    }

    /** Ends both streams and the file; returns the video's length in ms. */
    fun stop(): Long {
        stopping = true
        pacer?.stop()                      // its last frame is in before the end of the stream
        runCatching { video.signalEndOfInputStream() }
        videoThread?.join(5_000)
        audioThread?.join(5_000)
        runCatching { video.stop() }; video.release()
        audio?.let { runCatching { it.stop() }; it.release() }
        runCatching { codecSurface?.release() }
        return writer.finish()
    }

    private fun drainVideo() {
        val info = MediaCodec.BufferInfo()
        var first = true
        try {
            while (true) {
                val i = video.dequeueOutputBuffer(info, 10_000)
                when {
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> writer.setVideoFormat(video.outputFormat)
                    i >= 0 -> {
                        val buf = video.getOutputBuffer(i)!!
                        writer.writeVideo(buf, info)
                        video.releaseOutputBuffer(i, false)
                        if (first && writer.zeroUs >= 0) { first = false; onFirstFrame(writer.zeroUs * 1000) }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        } catch (t: Throwable) {
            if (!stopping) { failure = t; Log.e(TAG, "video encoder", t) }
        }
    }

    private fun runAudio(codec: MediaCodec, src: PcmSource) {
        val info = MediaCodec.BufferInfo()
        val pcm = ByteArray(2048)          // 1024 samples, mono 16-bit
        var eosSent = false
        try {
            while (true) {
                if (!eosSent) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val chunk = if (stopping) null else src.read(pcm)
                        if (chunk == null) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eosSent = true
                        } else {
                            val b = codec.getInputBuffer(i)!!; b.clear(); b.put(pcm, 0, chunk.first)
                            codec.queueInputBuffer(i, 0, chunk.first, chunk.second / 1000, 0)
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, if (eosSent) 10_000 else 0)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> writer.setAudioFormat(codec.outputFormat)
                    o >= 0 -> {
                        writer.writeAudio(codec.getOutputBuffer(o)!!, info)
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (t: Throwable) {
            if (!stopping) { failure = t; Log.e(TAG, "audio encoder", t) }
        } finally {
            src.stop()
        }
    }

    companion object {
        private const val TAG = "Recording"

        /**
         * The recording's size: the screen's aspect, the long side at most [longSide], and one the
         * phone's H.264 encoder takes (else the next smaller one, never a failed recording).
         */
        fun chooseSize(screenW: Int, screenH: Int, longSide: Int): Pair<Int, Int> {
            val caps = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
                .mapNotNull { runCatching { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }.getOrNull() }
            val landscape = screenW >= screenH
            val long = maxOf(screenW, screenH).toDouble()
            val short = minOf(screenW, screenH).toDouble()
            val tries = (listOf(longSide) + listOf(1920, 1600, 1440, 1280, 1024, 960, 854, 720, 640)).filter { it <= longSide }.distinct()
            for (l in tries) {
                val lw = minOf(l.toDouble(), long)
                val sw = (lw * short / long)
                // multiples of 16 (encoders like them), the long side as close as possible
                val a = (lw / 16).roundToInt() * 16
                val b = (sw / 16).roundToInt() * 16
                val (w, h) = if (landscape) a to b else b to a
                if (caps.isEmpty() || caps.any { c -> runCatching { c.isSizeSupported(w, h) && c.areSizeAndRateSupported(w, h, 30.0) }.getOrDefault(false) })
                    return w to h
            }
            return if (landscape) 1280 to 720 else 720 to 1280
        }
    }
}

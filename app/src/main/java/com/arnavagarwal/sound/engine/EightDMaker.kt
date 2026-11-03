package com.arnavagarwal.sound.engine

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import com.arnavagarwal.sound.core.EightDCopy
import com.arnavagarwal.sound.core.Resample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The 8D copy card on the phone (laptop: desktop EightD.kt + tools/eightd/eightd.py; his rule "same to same").
 * A song file he picks -> decoded by Android's own decoders -> 44.1 kHz -> core EightDCopy (the laptop's render,
 * sample for sample) -> "<song> - 8D.wav" in the Music folder. His song file is only read, never changed.
 */
object EightDMaker {
    sealed class State {
        object Idle : State()
        data class Working(val song: String, val progress: Double) : State()
        /** [saved] = the name Android really gave the copy ("x - 8D (1)" when "x - 8D" is already there). */
        data class Done(val saved: String, val uri: Uri) : State()
        data class Failed(val why: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private class Fail(why: String) : Exception(why)

    fun start(ctx: Context, song: Uri) {
        if (_state.value is State.Working) return
        val app = ctx.applicationContext
        val name = displayName(app, song)
        _state.value = State.Working(name, 0.0)
        Thread {
            _state.value = try { make(app, song, name).let { State.Done(displayName(app, it), it) } }
            catch (e: Fail) { State.Failed(e.message ?: "it stopped") }
            catch (e: Throwable) { Log.e("EightD", "failed", e); State.Failed(e.message ?: e.javaClass.simpleName) }
        }.apply { isDaemon = true; this.name = "eightd" }.start()
    }

    private fun displayName(ctx: Context, uri: Uri): String {
        val raw = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "song"
        return raw.substringBeforeLast('.').ifBlank { "song" }
    }

    private fun report(name: String, p: Double) { _state.value = State.Working(name, p.coerceIn(0.0, 1.0)) }

    private fun make(ctx: Context, song: Uri, name: String): Uri {
        val tmp = ctx.cacheDir
        val decoded = File.createTempFile("eightd-in", ".f32", tmp)
        val resampled = File.createTempFile("eightd-44k", ".f32", tmp)
        try {
            val rate = decode(ctx, song, decoded) { report(name, 0.15 * it) }
            val source: File = if (rate == EightDCopy.RATE) decoded else {
                EightDCopy.FilePcm(decoded).use { src -> writePcm(Resample.Pcm(src, rate, EightDCopy.RATE), resampled) { report(name, 0.15 + 0.1 * it) } }
                resampled
            }
            return EightDCopy.FilePcm(source).use { pcm ->
                if (pcm.n < EightDCopy.RATE / 10) throw Fail("the file has almost no sound in it")
                if (silent(pcm)) throw Fail("the file is silent")
                save(ctx, "$name - 8D.wav") { out ->
                    EightDCopy.writeWav(out, pcm.n) { emit ->
                        EightDCopy.render(EightDCopy.Kit.bundled(), pcm, tmp, progress = { report(name, 0.25 + 0.75 * it) }, sink = emit)
                    }
                }
            }
        } finally {
            decoded.delete(); resampled.delete()
        }
    }

    private fun silent(pcm: EightDCopy.Pcm): Boolean {
        val l = DoubleArray(65536); val r = DoubleArray(65536)
        var i = 0L
        while (i < pcm.n) {
            pcm.read(i, l, r)
            for (k in 0 until 65536) if (kotlin.math.abs(l[k]) > 1e-5 || kotlin.math.abs(r[k]) > 1e-5) return false
            i += 65536
        }
        return true
    }

    /** Writes interleaved float32 stereo. */
    private fun writePcm(pcm: EightDCopy.Pcm, dst: File, progress: (Double) -> Unit) {
        BufferedOutputStream(FileOutputStream(dst), 1 shl 16).use { out ->
            val l = DoubleArray(65536); val r = DoubleArray(65536)
            val bb = ByteBuffer.allocate(65536 * 8).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0L
            while (i < pcm.n) {
                val cnt = minOf(65536L, pcm.n - i).toInt()
                pcm.read(i, l, r)
                bb.clear()
                for (k in 0 until cnt) { bb.putFloat(l[k].toFloat()); bb.putFloat(r[k].toFloat()) }
                out.write(bb.array(), 0, bb.position())
                i += cnt
                progress(i.toDouble() / pcm.n)
            }
        }
    }

    /** Decodes the first sound track to interleaved float32 stereo (mono doubled, extra channels dropped); returns its rate. */
    private fun decode(ctx: Context, uri: Uri, dst: File, progress: (Double) -> Unit): Int {
        val ex = MediaExtractor()
        try {
            try { ex.setDataSource(ctx, uri, null) } catch (e: Exception) { throw Fail("that file could not be opened") }
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw Fail("there is no sound in that file")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var enc = AudioFormat.ENCODING_PCM_16BIT
            val codec = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) { throw Fail("this phone cannot read $mime files") }
            try {
                codec.configure(fmt, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inDone = false
                BufferedOutputStream(FileOutputStream(dst), 1 shl 16).use { out ->
                    var tmp = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)
                    while (true) {
                        if (!inDone) {
                            val ii = codec.dequeueInputBuffer(10_000)
                            if (ii >= 0) {
                                val n = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                                if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                                else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                            }
                        }
                        val oi = codec.dequeueOutputBuffer(info, 10_000)
                        if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val of = codec.outputFormat
                            rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) enc = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else if (oi >= 0) {
                            val buf = codec.getOutputBuffer(oi)!!.order(ByteOrder.LITTLE_ENDIAN)
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            if (enc != AudioFormat.ENCODING_PCM_16BIT && enc != AudioFormat.ENCODING_PCM_FLOAT)
                                throw Fail("this file's sound format is not supported")
                            val bytesPer = if (enc == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            val frames = info.size / (bytesPer * channels)
                            if (tmp.capacity() < frames * 8) tmp = ByteBuffer.allocate(frames * 8).order(ByteOrder.LITTLE_ENDIAN)
                            tmp.clear()
                            for (f in 0 until frames) {
                                var l = 0f; var r = 0f
                                for (c in 0 until channels) {
                                    val v = if (bytesPer == 4) buf.float else buf.short / 32768f
                                    if (c == 0) l = v
                                    if (c == 1) r = v
                                }
                                if (channels == 1) r = l
                                tmp.putFloat(l); tmp.putFloat(r)
                            }
                            out.write(tmp.array(), 0, tmp.position())
                            if (durUs > 0) progress(info.presentationTimeUs.toDouble() / durUs)
                            codec.releaseOutputBuffer(oi, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }; codec.release()
            }
            return rate
        } finally {
            ex.release()
        }
    }

    /** Saves into the phone's Music folder (Android 10+), or the app's own music folder on Android 9. */
    private fun save(ctx: Context, fileName: String, write: (java.io.OutputStream) -> Unit): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cr = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw Fail("the Music folder could not be written")
            try {
                cr.openOutputStream(uri)!!.use(write)
                cr.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
                return uri
            } catch (e: Throwable) {
                runCatching { cr.delete(uri, null, null) }   // no half-written copy left behind
                throw e
            }
        }
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: throw Fail("no storage to save to")
        val f = File(dir, fileName)
        try { FileOutputStream(f).use(write) } catch (e: Throwable) { f.delete(); throw e }
        return androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    }
}

package com.voltec.giro360

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Codifica uma sequência de quadros (Bitmaps) em um arquivo MP4 (H.264).
 *
 * Usa MediaCodec com formato de cor YUV420Flexible e a API de Image (respeitando
 * os strides de cada aparelho), evitando OpenGL/EGL. Tudo isolado e com fallback
 * em quem chama — se algo falhar, o vídeo original é mantido.
 */
class Mp4FrameEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    outFile: File
) {
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private var trackIndex = -1
    private var muxerStarted = false
    private var frameIndex = 0
    private val bufferInfo = MediaCodec.BufferInfo()

    init {
        val mime = "video/avc"
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            setInteger(MediaFormat.KEY_BIT_RATE, width * height * 4)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec = MediaCodec.createEncoderByType(mime)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun encodeFrame(bitmap: Bitmap) {
        val scaled = if (bitmap.width != width || bitmap.height != height)
            Bitmap.createScaledBitmap(bitmap, width, height, true) else bitmap

        val inIndex = codec.dequeueInputBuffer(10_000)
        if (inIndex >= 0) {
            val image = codec.getInputImage(inIndex)
            if (image != null) {
                fillImageFromBitmap(image, scaled)
            }
            val ptsUs = frameIndex * 1_000_000L / fps
            val size = width * height * 3 / 2
            codec.queueInputBuffer(inIndex, 0, size, ptsUs, 0)
            frameIndex++
        }
        drain(false)
        if (scaled !== bitmap) scaled.recycle()
    }

    fun finishAndMux() {
        val inIndex = codec.dequeueInputBuffer(10_000)
        if (inIndex >= 0) {
            codec.queueInputBuffer(
                inIndex, 0, 0, frameIndex * 1_000_000L / fps,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
        }
        drain(true)
        release()
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return // ainda há mais a codificar
                    // em EOS, continua esperando o último buffer
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outIndex >= 0 -> {
                    val encoded: ByteBuffer = codec.getOutputBuffer(outIndex) ?: continue
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun release() {
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        try { if (muxerStarted) muxer.stop() } catch (_: Exception) {}
        try { muxer.release() } catch (_: Exception) {}
    }

    /** Converte ARGB -> YUV420 (BT.601) escrevendo direto nos planos da Image. */
    private fun fillImageFromBitmap(image: android.media.Image, bitmap: Bitmap) {
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRow = yPlane.rowStride; val yPix = yPlane.pixelStride
        val uRow = uPlane.rowStride; val uPix = uPlane.pixelStride
        val vRow = vPlane.rowStride; val vPix = vPlane.pixelStride

        for (y in 0 until height) {
            for (x in 0 until width) {
                val c = argb[y * width + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val yy = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuf.position(y * yRow + x * yPix)
                yBuf.put(yy.coerceIn(0, 255).toByte())
                if (y % 2 == 0 && x % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val uvRow = y / 2; val uvCol = x / 2
                    uBuf.position(uvRow * uRow + uvCol * uPix)
                    uBuf.put(u.coerceIn(0, 255).toByte())
                    vBuf.position(uvRow * vRow + uvCol * vPix)
                    vBuf.put(v.coerceIn(0, 255).toByte())
                }
            }
        }
    }
}

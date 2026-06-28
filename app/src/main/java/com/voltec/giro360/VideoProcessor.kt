package com.voltec.giro360

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "Giro360.Processor"

/**
 * Pós-processamento dos vídeos gravados.
 *
 * Tudo aqui é "melhor esforço": se qualquer etapa falhar (codec do aparelho não
 * suportado, formato de música incompatível, etc.) devolvemos o vídeo original
 * em vez de quebrar o app.
 */
object VideoProcessor {

    /**
     * Aplica os ajustes do evento ao vídeo gravado e devolve o caminho final.
     * Por enquanto: adiciona música de fundo quando houver. Boomerang real e
     * renderização de câmera lenta entram na próxima onda.
     */
    fun process(
        context: Context,
        inputPath: String,
        effect: Effect,
        musicUri: Uri?
    ): String {
        var current = inputPath
        if (effect == Effect.BOOMERANG) {
            current = runCatching { makeBoomerang(current) }
                .getOrElse {
                    Log.w(TAG, "Falha ao gerar boomerang, mantendo vídeo normal", it)
                    current
                }
        }
        if (musicUri != null) {
            current = runCatching { addMusic(context, current, musicUri) }
                .getOrElse {
                    Log.w(TAG, "Falha ao adicionar música, mantendo áudio original", it)
                    current
                }
        }
        return current
    }

    /**
     * Gera o efeito boomerang: reproduz os quadros na ida e depois na volta,
     * criando um loop "vai-e-volta". Recodifica num MP4 novo (sem áudio).
     */
    private fun makeBoomerang(inputPath: String): String {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(inputPath)
        try {
            val durMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: return inputPath
            val durUs = durMs * 1000

            val fps = 18
            // usa no máximo os primeiros 2,5s para manter o loop curto e leve
            val clipUs = min(durUs, 2_500_000L)
            val frameCount = ((clipUs / 1_000_000.0) * fps).toInt().coerceAtLeast(2)
            val stepUs = clipUs / frameCount

            val first = frameAt(retriever, 0) ?: return inputPath
            // dimensões alvo (largura ~480, par, preservando proporção)
            val targetW = min(480, first.width).let { if (it % 2 == 0) it else it - 1 }
            val targetH = (first.height * targetW.toFloat() / first.width)
                .roundToInt().let { if (it % 2 == 0) it else it - 1 }
            first.recycle()

            val outFile = File(File(inputPath).parentFile, "boom_${File(inputPath).name}")
            val encoder = Mp4FrameEncoder(targetW, targetH, fps, outFile)

            // ida
            for (i in 0 until frameCount) {
                val bmp = frameAt(retriever, i * stepUs) ?: continue
                encoder.encodeFrame(bmp)
                bmp.recycle()
            }
            // volta (sem repetir as pontas, pra não "travar" no loop)
            for (i in frameCount - 2 downTo 1) {
                val bmp = frameAt(retriever, i * stepUs) ?: continue
                encoder.encodeFrame(bmp)
                bmp.recycle()
            }
            encoder.finishAndMux()

            File(inputPath).delete()
            return outFile.absolutePath
        } finally {
            retriever.release()
        }
    }

    private fun frameAt(retriever: MediaMetadataRetriever, timeUs: Long): Bitmap? =
        retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)

    /**
     * Substitui o áudio do vídeo por uma trilha de música (faixa de áudio AAC).
     * Copia os quadros de vídeo já comprimidos (rápido, sem reencodar) e junta o
     * primeiro fluxo de áudio do arquivo de música. Funciona com músicas .m4a/.aac
     * (contêiner MP4/AAC). Outros formatos caem no fallback.
     */
    private fun addMusic(context: Context, videoPath: String, musicUri: Uri): String {
        val outFile = File(File(videoPath).parentFile, "music_${File(videoPath).name}")

        val videoExtractor = MediaExtractor().apply { setDataSource(videoPath) }
        val musicExtractor = MediaExtractor().apply {
            setDataSource(context, musicUri, null)
        }

        try {
            // localiza a faixa de vídeo
            val videoTrack = firstTrackOfType(videoExtractor, "video/") ?: return videoPath
            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val videoDurationUs = videoFormat.getLongOrZero(MediaFormat.KEY_DURATION)

            // localiza a faixa de áudio da música
            val musicTrack = firstTrackOfType(musicExtractor, "audio/") ?: return videoPath
            val musicFormat = musicExtractor.getTrackFormat(musicTrack)
            val audioMime = musicFormat.getString(MediaFormat.KEY_MIME) ?: ""
            // MediaMuxer (mp4) só grava áudio AAC.
            if (!audioMime.contains("mp4a") && !audioMime.contains("aac")) return videoPath

            val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outVideoIndex = muxer.addTrack(videoFormat)
            val outAudioIndex = muxer.addTrack(musicFormat)
            muxer.start()

            val buffer = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()

            // copia vídeo
            videoExtractor.selectTrack(videoTrack)
            copyTrack(videoExtractor, muxer, outVideoIndex, buffer, info, Long.MAX_VALUE)

            // copia áudio (limitado à duração do vídeo, repetindo se a música for curta)
            musicExtractor.selectTrack(musicTrack)
            copyTrack(musicExtractor, muxer, outAudioIndex, buffer, info, videoDurationUs)

            muxer.stop()
            muxer.release()

            // sucesso: apaga o original e devolve o novo
            File(videoPath).delete()
            return outFile.absolutePath
        } finally {
            videoExtractor.release()
            musicExtractor.release()
        }
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        outIndex: Int,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        maxDurationUs: Long
    ) {
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val time = extractor.sampleTime
            if (time > maxDurationUs) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = time
            info.flags = extractor.sampleFlagsCompat()
            muxer.writeSampleData(outIndex, buffer, info)
            extractor.advance()
        }
    }

    private fun firstTrackOfType(extractor: MediaExtractor, prefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return null
    }

    private fun MediaFormat.getLongOrZero(key: String): Long =
        if (containsKey(key)) getLong(key) else 0L

    private fun MediaExtractor.sampleFlagsCompat(): Int {
        var flags = 0
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        return flags
    }
}

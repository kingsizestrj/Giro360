package com.voltec.giro360

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
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
        musicUri: Uri?,
        boomerangFps: Int = 20,
        boomerangWidth: Int = 480,
        onStatus: (String) -> Unit = {}
    ): String {
        var current = inputPath
        when (effect) {
            Effect.BOOMERANG -> {
                current = runCatching {
                    makeBoomerang(current, boomerangFps, boomerangWidth, onStatus)
                }.getOrElse {
                    Log.w(TAG, "Falha ao gerar boomerang, mantendo vídeo normal", it)
                    current
                }
            }
            Effect.SLOW -> {
                onStatus("Aplicando câmera lenta…")
                current = runCatching { makeSlowMotion(current, 2.0f) }
                    .getOrElse {
                        Log.w(TAG, "Falha na câmera lenta, mantendo vídeo normal", it)
                        current
                    }
            }
            Effect.REVERSE -> {
                current = runCatching { makeReverse(current, boomerangFps, boomerangWidth, onStatus) }
                    .getOrElse {
                        Log.w(TAG, "Falha no reverso, mantendo vídeo normal", it)
                        current
                    }
            }
            Effect.NORMAL -> {}
        }
        if (musicUri != null) {
            onStatus("Juntando música…")
            current = runCatching { addMusic(context, current, musicUri) }
                .getOrElse {
                    Log.w(TAG, "Falha ao adicionar música, mantendo áudio original", it)
                    current
                }
        }
        return current
    }

    /**
     * Boomerang igual aos apps de cabine 360: usa a gravação INTEIRA, reproduzindo
     * de ida e depois de volta (vai-e-volta) — a duração acompanha o que foi gravado.
     * Faz streaming (1 quadro por vez) para não estourar a memória em vídeos longos.
     * Recodifica num MP4 novo (sem áudio).
     */
    private fun makeBoomerang(
        inputPath: String,
        fps: Int,
        maxWidth: Int,
        onStatus: (String) -> Unit
    ): String {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(inputPath)
        try {
            val durMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: return inputPath
            val durUs = durMs * 1000
            // Rotação do vídeo (corrige boomerang saindo "deitado" 16:9).
            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0

            // Usa a gravação inteira (limite de 12s para o processamento não demorar demais).
            val clipUs = min(durUs, 12_000_000L)
            // Quantos quadros na ida; a volta reaproveita os mesmos tempos.
            val forwardFrames = ((clipUs / 1_000_000.0) * fps).toInt().coerceIn(2, 160)
            val stepUs = clipUs / forwardFrames

            val first = upright(frameAt(retriever, 0) ?: return inputPath, rotation)
            val targetW = min(maxWidth, first.width).let { if (it % 2 == 0) it else it - 1 }
            val targetH = (first.height * targetW.toFloat() / first.width)
                .roundToInt().let { if (it % 2 == 0) it else it - 1 }
            first.recycle()

            val outFile = File(File(inputPath).parentFile, "boom_${File(inputPath).name}")
            val encoder = Mp4FrameEncoder(targetW, targetH, fps, outFile)
            val totalEnc = forwardFrames * 2 - 2
            var enc = 0
            fun encodeAt(timeUs: Long) {
                val raw = frameAt(retriever, timeUs) ?: return
                val up = upright(raw, rotation)
                val scaled = if (up.width != targetW || up.height != targetH)
                    Bitmap.createScaledBitmap(up, targetW, targetH, true) else up
                if (scaled !== up) up.recycle()
                encoder.encodeFrame(scaled)
                scaled.recycle()
                enc++
                onStatus("Boomerang… ${(enc * 100 / totalEnc).coerceAtMost(99)}%")
            }
            // ida
            for (i in 0 until forwardFrames) encodeAt(i * stepUs)
            // volta (sem repetir as pontas, pra não "travar" no loop)
            for (i in forwardFrames - 2 downTo 1) encodeAt(i * stepUs)
            encoder.finishAndMux()

            File(inputPath).delete()
            return outFile.absolutePath
        } finally {
            retriever.release()
        }
    }

    /**
     * Câmera lenta REAL: re-muxa a faixa de vídeo multiplicando os tempos (PTS) por
     * [factor], deixando o vídeo mais lento sem reencodar (rápido e sem perder qualidade).
     * O áudio é descartado (câmera lenta normalmente é sem som).
     */
    private fun makeSlowMotion(inputPath: String, factor: Float): String {
        val extractor = MediaExtractor().apply { setDataSource(inputPath) }
        try {
            val videoTrack = firstTrackOfType(extractor, "video/") ?: return inputPath
            val format = extractor.getTrackFormat(videoTrack)
            val outFile = File(File(inputPath).parentFile, "slow_${File(inputPath).name}")
            val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outIndex = muxer.addTrack(format)
            muxer.start()

            val bufSize = maxOf(format.getIntOrZero(MediaFormat.KEY_MAX_INPUT_SIZE), 4 * 1024 * 1024)
            val buffer = ByteBuffer.allocate(bufSize)
            val info = MediaCodec.BufferInfo()
            extractor.selectTrack(videoTrack)
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = (extractor.sampleTime.toDouble() * factor).toLong()
                info.flags = extractor.sampleFlagsCompat()
                muxer.writeSampleData(outIndex, buffer, info)
                extractor.advance()
            }
            muxer.stop()
            muxer.release()
            File(inputPath).delete()
            return outFile.absolutePath
        } finally {
            extractor.release()
        }
    }

    /** Efeito reverso: reproduz o vídeo de trás pra frente (sem áudio). */
    private fun makeReverse(
        inputPath: String,
        fps: Int,
        maxWidth: Int,
        onStatus: (String) -> Unit
    ): String {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(inputPath)
        try {
            val durMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: return inputPath
            val durUs = durMs * 1000
            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0

            val clipUs = min(durUs, 12_000_000L)
            val frameCount = ((clipUs / 1_000_000.0) * fps).toInt().coerceIn(2, 160)
            val stepUs = clipUs / frameCount

            val first = upright(frameAt(retriever, 0) ?: return inputPath, rotation)
            val targetW = min(maxWidth, first.width).let { if (it % 2 == 0) it else it - 1 }
            val targetH = (first.height * targetW.toFloat() / first.width)
                .roundToInt().let { if (it % 2 == 0) it else it - 1 }
            first.recycle()

            val outFile = File(File(inputPath).parentFile, "rev_${File(inputPath).name}")
            val encoder = Mp4FrameEncoder(targetW, targetH, fps, outFile)
            var enc = 0
            for (i in frameCount - 1 downTo 0) {
                val raw = frameAt(retriever, i * stepUs) ?: continue
                val up = upright(raw, rotation)
                val scaled = if (up.width != targetW || up.height != targetH)
                    Bitmap.createScaledBitmap(up, targetW, targetH, true) else up
                if (scaled !== up) up.recycle()
                encoder.encodeFrame(scaled)
                scaled.recycle()
                enc++
                onStatus("Reverso… ${(enc * 100 / frameCount).coerceAtMost(99)}%")
            }
            encoder.finishAndMux()
            File(inputPath).delete()
            return outFile.absolutePath
        } finally {
            retriever.release()
        }
    }

    /** Endireita o quadro conforme a rotação do vídeo, se necessário. */
    private fun upright(bmp: Bitmap, rotation: Int): Bitmap {
        if ((rotation == 90 || rotation == 270) && bmp.width > bmp.height) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated !== bmp) bmp.recycle()
            return rotated
        }
        return bmp
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

            // Buffer grande o suficiente para o maior sample (keyframes de vídeo
            // podem passar de 1MB). Usa o KEY_MAX_INPUT_SIZE quando disponível.
            val bufSize = maxOf(
                videoFormat.getIntOrZero(MediaFormat.KEY_MAX_INPUT_SIZE),
                musicFormat.getIntOrZero(MediaFormat.KEY_MAX_INPUT_SIZE),
                4 * 1024 * 1024
            )
            val buffer = ByteBuffer.allocate(bufSize)
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

    private fun MediaFormat.getIntOrZero(key: String): Int =
        if (containsKey(key)) getInteger(key) else 0

    private fun MediaExtractor.sampleFlagsCompat(): Int {
        var flags = 0
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        return flags
    }
}

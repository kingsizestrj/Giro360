package com.voltec.giro360

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Envia o vídeo (e, opcionalmente, a moldura e a música) para o servidor Prime360,
 * que aplica o efeito + moldura + música com FFmpeg. Devolve a URL pública (QR).
 *
 * Deve ser chamado fora da thread principal (ex.: Dispatchers.IO).
 */
object CloudUploader {

    sealed class Result {
        data class Success(val url: String) : Result()
        data class Error(val message: String) : Result()
    }

    /** Envio simples (sem moldura/música) — usado ao recompartilhar da galeria. */
    fun upload(
        context: Context,
        filePath: String,
        effect: String = "normal",
        fps: Int = 20,
        eventId: String = "",
        onProgress: (Int) -> Unit = {}
    ): Result = uploadJob(context, filePath, effect, "normal", fps, null, null, eventId, onProgress)

    /** Consulta o status de processamento no servidor a partir da URL /v/<id>. */
    fun fetchStatus(shareUrl: String): String? {
        val statusUrl = shareUrl.replace("/v/", "/status/")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(statusUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
            }
            if (conn.responseCode in 200..299) {
                JSONObject(conn.inputStream.bufferedReader().readText()).optString("status").ifEmpty { null }
            } else null
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Envio completo: vídeo + (opcional) moldura PNG + (opcional) música.
     * A moldura e a música vão em endpoints de "asset" com o mesmo id do vídeo.
     */
    fun uploadJob(
        context: Context,
        videoPath: String,
        effect: String,
        speed: String,
        fps: Int,
        frameBytes: ByteArray?,
        musicUri: Uri?,
        eventId: String = "",
        onProgress: (Int) -> Unit = {}
    ): Result {
        val baseUrl = AppConfig.getServerUrl(context)
        if (baseUrl.isEmpty()) return Result.Error("Servidor não configurado")
        val apiKey = AppConfig.getApiKey(context)

        val file = File(videoPath)
        if (!file.exists()) return Result.Error("Arquivo não encontrado")

        val id = UUID.randomUUID().toString()

        // 1) moldura (best-effort — se falhar, segue sem ela)
        if (frameBytes != null) {
            runCatching { postBytes("$baseUrl/asset?id=$id&kind=frame", apiKey, "image/png", frameBytes) }
        }
        // 2) música (best-effort)
        if (musicUri != null) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(musicUri)?.use { it.readBytes() }
                if (bytes != null) postBytes("$baseUrl/asset?id=$id&kind=music", apiKey, "audio/*", bytes)
            }
        }

        // 3) vídeo (com progresso) -> dispara o processamento
        val name = Uri.encode(file.name)
        val endpoint = "$baseUrl/upload?id=$id&name=$name&effect=${Uri.encode(effect)}" +
            "&speed=${Uri.encode(speed)}&fps=$fps&event=${Uri.encode(eventId)}"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                useCaches = false
                connectTimeout = 15000
                readTimeout = 60000
                setRequestProperty("Content-Type", "video/mp4")
                if (apiKey.isNotEmpty()) setRequestProperty("X-Api-Key", apiKey)
                setFixedLengthStreamingMode(file.length())
            }
            val total = file.length().coerceAtLeast(1)
            conn.outputStream.use { out ->
                file.inputStream().use { input ->
                    val buf = ByteArray(256 * 1024)
                    var sent = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sent += n
                        onProgress((sent * 100 / total).toInt())
                    }
                    out.flush()
                }
            }
            val code = conn.responseCode
            if (code in 200..299) {
                val body = conn.inputStream.bufferedReader().readText()
                val url = JSONObject(body).optString("url")
                if (url.isNotEmpty()) Result.Success(url) else Result.Error("Resposta sem URL")
            } else {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
                Result.Error("Servidor respondeu $code. $err")
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Falha no envio")
        } finally {
            conn?.disconnect()
        }
    }

    /** POST de bytes brutos (moldura/música). Lança em caso de erro HTTP. */
    private fun postBytes(endpoint: String, apiKey: String, contentType: String, bytes: ByteArray) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                useCaches = false
                connectTimeout = 15000
                readTimeout = 60000
                setRequestProperty("Content-Type", contentType)
                if (apiKey.isNotEmpty()) setRequestProperty("X-Api-Key", apiKey)
                setFixedLengthStreamingMode(bytes.size)
            }
            conn.outputStream.use { it.write(bytes) }
            conn.responseCode // consome a resposta
        } finally {
            conn?.disconnect()
        }
    }
}

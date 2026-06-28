package com.voltec.giro360

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Envia um vídeo para o servidor Giro360 (Docker) e devolve a URL pública
 * (usada no QR Code). Envio por corpo bruto (POST /upload?name=...).
 *
 * Deve ser chamado fora da thread principal (ex.: Dispatchers.IO).
 */
object CloudUploader {

    /** Resultado do envio. */
    sealed class Result {
        data class Success(val url: String) : Result()
        data class Error(val message: String) : Result()
    }

    fun upload(context: Context, filePath: String): Result {
        val baseUrl = AppConfig.getServerUrl(context)
        if (baseUrl.isEmpty()) return Result.Error("Servidor não configurado")
        val apiKey = AppConfig.getApiKey(context)

        val file = File(filePath)
        if (!file.exists()) return Result.Error("Arquivo não encontrado")

        val name = Uri.encode(file.name)
        val endpoint = "$baseUrl/upload?name=$name"

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15000
                readTimeout = 60000
                setRequestProperty("Content-Type", "video/mp4")
                if (apiKey.isNotEmpty()) setRequestProperty("X-Api-Key", apiKey)
                setFixedLengthStreamingMode(file.length())
            }
            conn.outputStream.use { out ->
                file.inputStream().use { input -> input.copyTo(out, 64 * 1024) }
            }
            val code = conn.responseCode
            if (code in 200..299) {
                val body = conn.inputStream.bufferedReader().readText()
                val url = JSONObject(body).optString("url")
                if (url.isNotEmpty()) Result.Success(url)
                else Result.Error("Resposta sem URL")
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
}

package com.voltec.giro360

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Fila de reenvio: se um upload para o servidor falhar (ex.: Wi-Fi caiu),
 * o trabalho fica salvo e é reenviado automaticamente depois.
 *
 * Guarda os dados necessários para refazer o envio (vídeo bruto local + efeito
 * + moldura/música escolhidas). process() deve rodar fora da thread principal.
 */
object UploadQueue {

    data class Job(
        val recId: String,
        val eventId: String,
        val videoPath: String,
        val effect: String,
        val speed: String,
        val fps: Int,
        val frameId: String?,
        val customFrameUri: String?,
        val musicUri: String?
    )

    private fun file(context: Context) = File(context.filesDir, "upload_queue.json")

    private fun read(context: Context): JSONArray {
        val f = file(context)
        if (!f.exists()) return JSONArray()
        return try { JSONArray(f.readText()) } catch (e: Exception) { JSONArray() }
    }

    private fun write(context: Context, arr: JSONArray) = file(context).writeText(arr.toString())

    @Synchronized
    fun add(context: Context, job: Job) {
        val arr = read(context)
        // evita duplicar o mesmo recId
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("recId") != job.recId) out.put(arr.getJSONObject(i))
        }
        out.put(toJson(job))
        write(context, out)
    }

    @Synchronized
    fun remove(context: Context, recId: String) {
        val arr = read(context)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("recId") != recId) out.put(arr.getJSONObject(i))
        }
        write(context, out)
    }

    fun pendingCount(context: Context): Int = read(context).length()

    private fun jobsList(context: Context): List<Job> {
        val arr = read(context)
        return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }

    /** Tenta reenviar todos os pendentes. Devolve quantos foram concluídos. */
    fun process(context: Context): Int {
        if (!AppConfig.isConfigured(context)) return 0
        var ok = 0
        for (j in jobsList(context)) {
            val vf = File(j.videoPath)
            if (!vf.exists()) { remove(context, j.recId); continue } // vídeo sumiu

            val frameBytes: ByteArray? = when {
                j.frameId != null -> frameById(j.frameId)?.let {
                    runCatching { renderFrameToPng(it) }.getOrNull()
                }
                j.customFrameUri != null -> runCatching {
                    context.contentResolver.openInputStream(Uri.parse(j.customFrameUri))?.use { it.readBytes() }
                }.getOrNull()
                else -> null
            }
            val music = j.musicUri?.let { Uri.parse(it) }

            val r = CloudUploader.uploadJob(
                context, j.videoPath, j.effect, j.speed, j.fps, frameBytes, music, j.eventId
            )
            if (r is CloudUploader.Result.Success) {
                EventStore.findRecording(context, j.recId)?.let {
                    EventStore.updateRecording(context, it.copy(shareUrl = r.url))
                }
                remove(context, j.recId)
                ok++
            }
        }
        return ok
    }

    private fun toJson(j: Job) = JSONObject().apply {
        put("recId", j.recId)
        put("eventId", j.eventId)
        put("videoPath", j.videoPath)
        put("effect", j.effect)
        put("speed", j.speed)
        put("fps", j.fps)
        put("frameId", j.frameId ?: JSONObject.NULL)
        put("customFrameUri", j.customFrameUri ?: JSONObject.NULL)
        put("musicUri", j.musicUri ?: JSONObject.NULL)
    }

    private fun fromJson(o: JSONObject) = Job(
        recId = o.getString("recId"),
        eventId = o.optString("eventId"),
        videoPath = o.getString("videoPath"),
        effect = o.optString("effect", "normal"),
        speed = o.optString("speed", "normal"),
        fps = o.optInt("fps", 20),
        frameId = o.optStringOrNull("frameId"),
        customFrameUri = o.optStringOrNull("customFrameUri"),
        musicUri = o.optStringOrNull("musicUri")
    )

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).ifEmpty { null }
}

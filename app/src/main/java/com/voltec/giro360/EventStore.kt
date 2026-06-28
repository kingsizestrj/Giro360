package com.voltec.giro360

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Armazena eventos e gravações num arquivo JSON local (sem banco de dados).
 * Simples e suficiente para uso em um único aparelho.
 */
object EventStore {

    private fun file(context: Context) = File(context.filesDir, "giro360_data.json")

    private fun readRoot(context: Context): JSONObject {
        val f = file(context)
        if (!f.exists()) return JSONObject().put("events", JSONArray()).put("recordings", JSONArray())
        return try {
            JSONObject(f.readText())
        } catch (e: Exception) {
            JSONObject().put("events", JSONArray()).put("recordings", JSONArray())
        }
    }

    private fun writeRoot(context: Context, root: JSONObject) {
        file(context).writeText(root.toString())
    }

    // ---------- Eventos ----------

    fun loadEvents(context: Context): List<Event> {
        val arr = readRoot(context).optJSONArray("events") ?: JSONArray()
        return (0 until arr.length()).map { eventFromJson(arr.getJSONObject(it)) }
            .sortedByDescending { it.createdAt }
    }

    fun saveEvent(context: Context, event: Event) {
        val root = readRoot(context)
        val arr = root.optJSONArray("events") ?: JSONArray()
        var replaced = false
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optString("id") == event.id) {
                out.put(eventToJson(event)); replaced = true
            } else out.put(obj)
        }
        if (!replaced) out.put(eventToJson(event))
        root.put("events", out)
        writeRoot(context, root)
    }

    fun deleteEvent(context: Context, eventId: String) {
        val root = readRoot(context)
        val arr = root.optJSONArray("events") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optString("id") != eventId) out.put(obj)
        }
        root.put("events", out)
        // remove também as gravações do evento
        val recArr = root.optJSONArray("recordings") ?: JSONArray()
        val recOut = JSONArray()
        for (i in 0 until recArr.length()) {
            val obj = recArr.getJSONObject(i)
            if (obj.optString("eventId") != eventId) recOut.put(obj)
            else File(obj.optString("filePath")).delete()
        }
        root.put("recordings", recOut)
        writeRoot(context, root)
    }

    fun getEvent(context: Context, eventId: String): Event? =
        loadEvents(context).firstOrNull { it.id == eventId }

    // ---------- Gravações ----------

    fun loadRecordings(context: Context, eventId: String): List<Recording> {
        val arr = readRoot(context).optJSONArray("recordings") ?: JSONArray()
        return (0 until arr.length()).map { recordingFromJson(arr.getJSONObject(it)) }
            .filter { it.eventId == eventId }
            .sortedByDescending { it.createdAt }
    }

    fun addRecording(context: Context, rec: Recording) {
        val root = readRoot(context)
        val arr = root.optJSONArray("recordings") ?: JSONArray()
        arr.put(recordingToJson(rec))
        root.put("recordings", arr)
        writeRoot(context, root)
    }

    fun updateRecording(context: Context, rec: Recording) {
        val root = readRoot(context)
        val arr = root.optJSONArray("recordings") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optString("id") == rec.id) out.put(recordingToJson(rec))
            else out.put(obj)
        }
        root.put("recordings", out)
        writeRoot(context, root)
    }

    fun deleteRecording(context: Context, recId: String) {
        val root = readRoot(context)
        val arr = root.optJSONArray("recordings") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optString("id") != recId) out.put(obj)
            else File(obj.optString("filePath")).delete()
        }
        root.put("recordings", out)
        writeRoot(context, root)
    }

    fun recordingCount(context: Context, eventId: String): Int =
        loadRecordings(context, eventId).size

    // ---------- JSON helpers ----------

    private fun eventToJson(e: Event) = JSONObject().apply {
        put("id", e.id)
        put("name", e.name)
        put("createdAt", e.createdAt)
        put("effect", e.effect.name)
        put("durationSeconds", e.durationSeconds)
        put("autoStart", e.autoStart)
        put("frameId", e.frameId ?: JSONObject.NULL)
        put("customFrameUri", e.customFrameUri ?: JSONObject.NULL)
        put("musicUri", e.musicUri ?: JSONObject.NULL)
        put("musicName", e.musicName ?: JSONObject.NULL)
    }

    private fun eventFromJson(o: JSONObject) = Event(
        id = o.getString("id"),
        name = o.getString("name"),
        createdAt = o.optLong("createdAt"),
        effect = runCatching { Effect.valueOf(o.optString("effect", "SLOW")) }.getOrDefault(Effect.SLOW),
        durationSeconds = o.optInt("durationSeconds", 8),
        autoStart = o.optBoolean("autoStart", true),
        frameId = o.optStringOrNull("frameId"),
        customFrameUri = o.optStringOrNull("customFrameUri"),
        musicUri = o.optStringOrNull("musicUri"),
        musicName = o.optStringOrNull("musicName")
    )

    private fun recordingToJson(r: Recording) = JSONObject().apply {
        put("id", r.id)
        put("eventId", r.eventId)
        put("filePath", r.filePath)
        put("createdAt", r.createdAt)
        put("effect", r.effect.name)
        put("shareUrl", r.shareUrl ?: JSONObject.NULL)
    }

    private fun recordingFromJson(o: JSONObject) = Recording(
        id = o.getString("id"),
        eventId = o.getString("eventId"),
        filePath = o.getString("filePath"),
        createdAt = o.optLong("createdAt"),
        effect = runCatching { Effect.valueOf(o.optString("effect", "NORMAL")) }.getOrDefault(Effect.NORMAL),
        shareUrl = o.optStringOrNull("shareUrl")
    )

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key) || !has(key)) null else optString(key).ifEmpty { null }
}

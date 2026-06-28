package com.voltec.giro360

import android.content.Context

/** Configurações do app (URL do servidor de vídeos e chave de API). */
object AppConfig {
    private const val PREFS = "giro360_config"
    private const val KEY_URL = "server_url"
    private const val KEY_API = "api_key"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getServerUrl(context: Context): String =
        prefs(context).getString(KEY_URL, "")?.trim()?.trimEnd('/') ?: ""

    fun getApiKey(context: Context): String =
        prefs(context).getString(KEY_API, "") ?: ""

    fun isConfigured(context: Context): Boolean = getServerUrl(context).isNotEmpty()

    fun save(context: Context, url: String, apiKey: String) {
        prefs(context).edit()
            .putString(KEY_URL, url.trim().trimEnd('/'))
            .putString(KEY_API, apiKey.trim())
            .apply()
    }
}

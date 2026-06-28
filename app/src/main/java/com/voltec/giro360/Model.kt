package com.voltec.giro360

/** Efeito aplicado ao vídeo gravado. */
enum class Effect(val label: String) {
    NORMAL("Normal"),
    SLOW("Lento"),
    BOOMERANG("Boomerang"),
    REVERSE("Reverso")
}

/** Um evento (festa, casamento, etc.) que agrupa as gravações. */
data class Event(
    val id: String,
    val name: String,
    val createdAt: Long,
    // Configurações padrão do evento
    val effect: Effect = Effect.SLOW,
    val durationSeconds: Int = 8,
    val countdownSeconds: Int = 5,      // contagem regressiva antes de gravar
    // Parâmetros do boomerang (ajustáveis na hora)
    val boomerangFps: Int = 20,         // velocidade do loop
    val boomerangClipMs: Int = 1200,    // trecho usado (ida); total ~= 2x
    val boomerangWidth: Int = 480,      // qualidade/largura do boomerang
    val frameId: String? = null,        // moldura embutida selecionada
    val customFrameUri: String? = null, // moldura escolhida da galeria
    val musicUri: String? = null,       // música de fundo escolhida
    val musicName: String? = null
)

/** Uma gravação pertencente a um evento. */
data class Recording(
    val id: String,
    val eventId: String,
    val filePath: String,
    val createdAt: Long,
    val effect: Effect,
    // Link público (preenchido após upload na nuvem) usado para o QR Code.
    val shareUrl: String? = null
)

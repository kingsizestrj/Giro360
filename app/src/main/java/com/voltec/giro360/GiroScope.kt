package com.voltec.giro360

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Escopo de vida da aplicação para tarefas que não podem ser canceladas ao
 * sair da tela (processar o vídeo, salvar e enviar ao servidor). Assim a
 * gravação nunca se perde se o usuário voltar/trocar de tela durante o envio.
 */
object GiroScope {
    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

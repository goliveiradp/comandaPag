package br.com.comandapag.rede

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Protocolo entre a maquininha servidor e os terminais: HTTP + JSON na rede local.
 * Toda chamada (menos /ping e /parear) leva o token recebido no pareamento.
 */
object Protocolo {
    const val PORTA_PADRAO = 8765
    const val CABECALHO_TOKEN = "X-ComandaPag-Token"

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}

@Serializable
data class PedidoPareamento(val codigo: String, val aparelho: String)

@Serializable
data class RespostaPareamento(val token: String, val servidor: String)

@Serializable
data class RespostaId(val id: Long)

@Serializable
data class RespostaErro(val mensagem: String)

/** Número que aumenta a cada gravação no servidor; os terminais usam para saber quando atualizar. */
@Serializable
data class Mudancas(val versao: Long)

@Serializable
data class Ping(val app: String = "ComandaPag", val servidor: String)

package br.com.comandapag.rede

import br.com.comandapag.core.fiscal.EmissorNfce
import br.com.comandapag.core.fiscal.NotaJaEmitidaException
import br.com.comandapag.core.fiscal.NotaNfce
import br.com.comandapag.core.fiscal.RespostaCancelamento
import br.com.comandapag.core.fiscal.RespostaNfce
import br.com.comandapag.core.fiscal.StatusEmissor
import kotlinx.serialization.Serializable

@Serializable
data class PedidoEmissao(val ref: String, val nota: NotaNfce)

@Serializable
data class PedidoCancelamento(val ref: String, val justificativa: String)

/** Resposta do servidor à emissão; [jaEmitida] quando a referência já foi usada. */
@Serializable
data class RespostaEmissaoServidor(val resposta: RespostaNfce? = null, val jaEmitida: Boolean = false)

/**
 * Nos terminais, a NFC-e é emitida pela maquininha servidor (só ela tem o token da Focus).
 */
class EmissorNfceRemoto(private val c: ClienteComandaPag) : EmissorNfce {
    override suspend fun status(): StatusEmissor = c.get<StatusEmissor>("/fiscal/status")!!

    override suspend fun emitir(ref: String, nota: NotaNfce): RespostaNfce {
        val r = c.post<PedidoEmissao, RespostaEmissaoServidor>("/fiscal/emitir", PedidoEmissao(ref, nota))
        if (r.jaEmitida) throw NotaJaEmitidaException()
        return r.resposta!!
    }

    override suspend fun consultar(ref: String): RespostaNfce? = c.get("/fiscal/consultar", mapOf("ref" to ref))

    override suspend fun cancelar(ref: String, justificativa: String): RespostaCancelamento =
        c.post("/fiscal/cancelar", PedidoCancelamento(ref, justificativa))
}

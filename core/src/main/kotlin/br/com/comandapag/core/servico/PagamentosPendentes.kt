package br.com.comandapag.core.servico

import br.com.comandapag.core.modelo.Pagamento
import kotlinx.serialization.Serializable

/**
 * Pagamento aprovado na maquininha que não pôde ser gravado no servidor (ex.: Wi-Fi caiu
 * logo depois da aprovação). Fica guardado no aparelho até ser reenviado.
 */
@Serializable
data class PagamentoPendente(val comandaId: Long, val pagamento: Pagamento, val operador: String)

interface PagamentosPendentes {
    suspend fun adicionar(pendente: PagamentoPendente)
    suspend fun listar(): List<PagamentoPendente>
    suspend fun remover(pendente: PagamentoPendente)
}

class PagamentosPendentesEmMemoria : PagamentosPendentes {
    private val lista = mutableListOf<PagamentoPendente>()
    override suspend fun adicionar(pendente: PagamentoPendente) { synchronized(lista) { lista += pendente } }
    override suspend fun listar(): List<PagamentoPendente> = synchronized(lista) { lista.toList() }
    override suspend fun remover(pendente: PagamentoPendente) { synchronized(lista) { lista -= pendente } }
}

package br.com.comandapag.core

import br.com.comandapag.core.modelo.Comanda
import br.com.comandapag.core.modelo.StatusComanda
import br.com.comandapag.core.pagamento.ResultadoOperacao
import br.com.comandapag.core.pagamento.ResultadoPagamento
import br.com.comandapag.core.pagamento.SolicitacaoPagamento
import br.com.comandapag.core.pagamento.TerminalPagamento
import br.com.comandapag.core.repositorio.ComandaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.yield

class ComandaRepositoryEmMemoria : ComandaRepository {
    private val dados = MutableStateFlow<Map<Long, Comanda>>(emptyMap())
    private var proximoId = 1L

    override fun observarAbertas(): Flow<List<Comanda>> =
        dados.map { m -> m.values.filter { it.status == StatusComanda.ABERTA } }

    override fun observarFechadas(): Flow<List<Comanda>> =
        dados.map { m -> m.values.filter { it.status != StatusComanda.ABERTA } }

    override fun observar(id: Long): Flow<Comanda?> = dados.map { it[id] }
    override suspend fun buscar(id: Long): Comanda? {
        val lido = dados.value[id]
        yield() // simula a demora de um banco de dados real entre ler e devolver
        return lido
    }

    override suspend fun buscarAbertaPorIdentificacao(identificacao: String): Comanda? =
        dados.value.values.firstOrNull { it.estaAberta && it.identificacao.equals(identificacao, ignoreCase = true) }

    override suspend fun salvar(comanda: Comanda): Long {
        val id = if (comanda.id == 0L) proximoId++ else comanda.id
        dados.value = dados.value + (id to comanda.copy(id = id))
        return id
    }
}

class TerminalFalso(var resposta: ResultadoPagamento = ResultadoPagamento.Aprovado("COD1", "ID1", "VISA", "OK")) :
    TerminalPagamento {
    val solicitacoes = mutableListOf<SolicitacaoPagamento>()

    override suspend fun estaAtivado() = true
    override suspend fun ativar(codigoAtivacao: String) = ResultadoOperacao.Sucesso
    override suspend fun cobrar(solicitacao: SolicitacaoPagamento, aoMudarStatus: (String) -> Unit): ResultadoPagamento {
        solicitacoes += solicitacao
        aoMudarStatus("PROCESSANDO")
        return resposta
    }
    override suspend fun cancelarOperacao() {}
}

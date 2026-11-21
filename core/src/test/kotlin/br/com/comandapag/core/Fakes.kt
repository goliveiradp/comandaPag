package br.com.comandapag.core

import br.com.comandapag.core.modelo.Caixa
import br.com.comandapag.core.modelo.Comanda
import br.com.comandapag.core.modelo.Pagamento
import br.com.comandapag.core.modelo.StatusComanda
import br.com.comandapag.core.modelo.Usuario
import br.com.comandapag.core.pagamento.Impressora
import br.com.comandapag.core.pagamento.ResultadoOperacao
import br.com.comandapag.core.pagamento.ResultadoPagamento
import br.com.comandapag.core.pagamento.SolicitacaoEstorno
import br.com.comandapag.core.pagamento.SolicitacaoPagamento
import br.com.comandapag.core.pagamento.TerminalPagamento
import br.com.comandapag.core.repositorio.CaixaRepository
import br.com.comandapag.core.repositorio.ComandaRepository
import br.com.comandapag.core.repositorio.UsuarioRepository
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

    override suspend fun pagamentosDoCaixa(caixaId: Long): List<Pagamento> =
        dados.value.values.flatMap { it.pagamentos }.filter { it.caixaId == caixaId }
}

class CaixaRepositoryEmMemoria : CaixaRepository {
    private val dados = MutableStateFlow<Map<Long, Caixa>>(emptyMap())
    private var proximoId = 1L

    override fun observarAberto(): Flow<Caixa?> = dados.map { m -> m.values.firstOrNull { it.aberto } }
    override fun observarFechados(): Flow<List<Caixa>> = dados.map { m -> m.values.filter { !it.aberto } }
    override suspend fun buscarAberto(): Caixa? = dados.value.values.firstOrNull { it.aberto }
    override suspend fun buscar(id: Long): Caixa? = dados.value[id]
    override suspend fun salvar(caixa: Caixa): Long {
        val id = if (caixa.id == 0L) proximoId++ else caixa.id
        dados.value = dados.value + (id to caixa.copy(id = id))
        return id
    }
}

class TerminalFalso(var resposta: ResultadoPagamento = ResultadoPagamento.Aprovado("COD1", "ID1", "VISA", "OK")) :
    TerminalPagamento {
    val solicitacoes = mutableListOf<SolicitacaoPagamento>()
    val estornos = mutableListOf<SolicitacaoEstorno>()
    var respostaEstorno: ResultadoPagamento = ResultadoPagamento.Aprovado("COD1", "ID1", null, "ESTORNADO")

    override suspend fun estornar(solicitacao: SolicitacaoEstorno, aoMudarStatus: (String) -> Unit): ResultadoPagamento {
        estornos += solicitacao
        return respostaEstorno
    }

    override suspend fun estaAtivado() = true
    override suspend fun ativar(codigoAtivacao: String) = ResultadoOperacao.Sucesso
    override suspend fun cobrar(solicitacao: SolicitacaoPagamento, aoMudarStatus: (String) -> Unit): ResultadoPagamento {
        solicitacoes += solicitacao
        aoMudarStatus("PROCESSANDO")
        return resposta
    }
    override suspend fun cancelarOperacao() {}
}

class UsuarioRepositoryEmMemoria : UsuarioRepository {
    private val dados = MutableStateFlow<Map<Long, Usuario>>(emptyMap())
    private var proximoId = 1L

    override fun observarTodos(): Flow<List<Usuario>> = dados.map { it.values.toList() }
    override suspend fun listarTodos(): List<Usuario> = dados.value.values.toList()
    override suspend fun buscar(id: Long): Usuario? = dados.value[id]
    override suspend fun salvar(usuario: Usuario): Long {
        val id = if (usuario.id == 0L) proximoId++ else usuario.id
        dados.value = dados.value + (id to usuario.copy(id = id))
        return id
    }
}

/** Impressora que guarda o que foi impresso; pode falhar para setores escolhidos. */
class ImpressoraFalsa : Impressora {
    val impressoes = mutableListOf<List<String>>()
    /** Falha quando o texto contém alguma destas palavras (ex.: "BAR"). */
    val falharSeContiver = mutableSetOf<String>()

    override suspend fun imprimir(linhas: List<String>, colunas: Int): ResultadoOperacao {
        if (falharSeContiver.any { palavra -> linhas.any { it.contains(palavra) } }) {
            return ResultadoOperacao.Falha("SEM PAPEL")
        }
        impressoes += linhas
        return ResultadoOperacao.Sucesso
    }
}

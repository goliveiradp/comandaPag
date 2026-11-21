package br.com.comandapag.core

import br.com.comandapag.core.modelo.Caixa
import br.com.comandapag.core.modelo.Comanda
import br.com.comandapag.core.modelo.Pagamento
import br.com.comandapag.core.modelo.Produto
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
import br.com.comandapag.core.repositorio.ProdutoRepository
import br.com.comandapag.core.repositorio.UsuarioRepository
import br.com.comandapag.core.servico.ConflitoDeVersaoException
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

    /** Chamado antes de cada gravação; os testes usam para simular outro aparelho gravando no meio. */
    var antesDeSalvar: suspend (Comanda) -> Unit = {}

    override suspend fun salvar(comanda: Comanda): Long {
        antesDeSalvar(comanda)
        return synchronized(this) {
            val id = if (comanda.id == 0L) proximoId++ else comanda.id
            val atual = dados.value[id]
            if (comanda.id != 0L && atual != null && atual.versao != comanda.versao) throw ConflitoDeVersaoException()
            dados.value = dados.value + (id to comanda.copy(id = id, versao = if (comanda.id == 0L) 0 else comanda.versao + 1))
            id
        }
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
    override suspend fun salvar(caixa: Caixa): Long = synchronized(this) {
        val id = if (caixa.id == 0L) proximoId++ else caixa.id
        val atual = dados.value[id]
        if (caixa.id != 0L && atual != null && atual.versao != caixa.versao) throw ConflitoDeVersaoException()
        dados.value = dados.value + (id to caixa.copy(id = id, versao = if (caixa.id == 0L) 0 else caixa.versao + 1))
        id
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
    override suspend fun salvar(usuario: Usuario): Long = synchronized(this) {
        val id = if (usuario.id == 0L) proximoId++ else usuario.id
        dados.value = dados.value + (id to usuario.copy(id = id))
        id
    }
}

/** Impressora que guarda o que foi impresso; pode falhar para setores escolhidos. */
class ImpressoraFalsa : Impressora {
    val impressoes: MutableList<List<String>> = java.util.Collections.synchronizedList(mutableListOf())
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

class ProdutoRepositoryEmMemoria : ProdutoRepository {
    private val dados = MutableStateFlow<Map<Long, Produto>>(emptyMap())
    private var proximoId = 1L

    override fun observarAtivos(): Flow<List<Produto>> = dados.map { m -> m.values.filter { it.ativo } }
    override suspend fun buscar(id: Long): Produto? = dados.value[id]
    override suspend fun salvar(produto: Produto): Long = synchronized(this) {
        val id = if (produto.id == 0L) proximoId++ else produto.id
        dados.value = dados.value + (id to produto.copy(id = id))
        id
    }
    override suspend fun desativar(id: Long) {
        synchronized(this) { dados.value[id]?.let { dados.value = dados.value + (id to it.copy(ativo = false)) } }
    }
}

/** Emissor de NFC-e de mentira: guarda o que recebeu e responde o que o teste mandar. */
class EmissorNfceFalso(
    var ativo: Boolean = true,
    val ambiente: br.com.comandapag.core.fiscal.AmbienteFiscal = br.com.comandapag.core.fiscal.AmbienteFiscal.HOMOLOGACAO,
) : br.com.comandapag.core.fiscal.EmissorNfce {
    val emitidas = mutableListOf<Pair<String, br.com.comandapag.core.fiscal.NotaNfce>>()
    val canceladas = mutableListOf<Pair<String, String>>()
    /** Respostas guardadas por referência (para consultar). */
    val notas = mutableMapOf<String, br.com.comandapag.core.fiscal.RespostaNfce>()
    var rejeitar: String? = null
    /** Simula "a nota já existe" (a resposta anterior se perdeu). */
    var jaEmitida = false
    var cancelamentoAceito = true
    val emitente = br.com.comandapag.core.fiscal.Emitente("12345678000195", "Bar do Zé Ltda", "123456789", "Rua A, 1 - São Luís/MA")

    override suspend fun status() = br.com.comandapag.core.fiscal.StatusEmissor(ativo, ambiente, emitente)

    override suspend fun emitir(ref: String, nota: br.com.comandapag.core.fiscal.NotaNfce): br.com.comandapag.core.fiscal.RespostaNfce {
        if (jaEmitida) throw br.com.comandapag.core.fiscal.NotaJaEmitidaException()
        emitidas += ref to nota
        val r = rejeitar?.let { br.com.comandapag.core.fiscal.RespostaNfce(false, mensagem = it, ambiente = ambiente) }
            ?: br.com.comandapag.core.fiscal.RespostaNfce(
                autorizada = true,
                mensagem = "Autorizado o uso da NF-e",
                numero = (emitidas.size).toString(),
                serie = "1",
                chave = "21261112345678000195650010000000011000000019",
                qrCodeUrl = "http://www.nfce.sefaz.ma.gov.br/portal/consultarNFCe.jsp?p=2126111234",
                urlConsulta = "http://www.nfce.sefaz.ma.gov.br/portal/consultaNFe.do",
                protocolo = "221260000000001",
                ambiente = ambiente,
                emitente = emitente,
            )
        notas[ref] = r
        return r
    }

    override suspend fun consultar(ref: String) = notas[ref]

    override suspend fun cancelar(ref: String, justificativa: String): br.com.comandapag.core.fiscal.RespostaCancelamento {
        canceladas += ref to justificativa
        return br.com.comandapag.core.fiscal.RespostaCancelamento(cancelamentoAceito, if (cancelamentoAceito) "Cancelamento homologado" else "Prazo excedido")
    }
}

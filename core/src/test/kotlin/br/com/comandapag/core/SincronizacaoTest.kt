package br.com.comandapag.core

import br.com.comandapag.core.modelo.FormaPagamento
import br.com.comandapag.core.modelo.ItemComanda
import br.com.comandapag.core.modelo.Perfil
import br.com.comandapag.core.modelo.Produto
import br.com.comandapag.core.modelo.StatusComanda
import br.com.comandapag.core.modelo.Usuario
import br.com.comandapag.core.pagamento.ResultadoPagamento
import br.com.comandapag.core.servico.CaixaService
import br.com.comandapag.core.servico.ComandaService
import br.com.comandapag.core.servico.ConflitoDeVersaoException
import br.com.comandapag.core.servico.SemConexaoException
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Comportamento quando há várias maquininhas: conflitos de gravação e queda de conexão. */
class SincronizacaoTest {
    private val repo = ComandaRepositoryEmMemoria()
    private val caixas = CaixaRepositoryEmMemoria()
    private val terminal = TerminalFalso()
    private val service = ComandaService(repo, terminal, caixas) { 1_000L }
    private val garcom = Usuario(id = 1, nome = "Ana", permissoes = Perfil.ADMINISTRADOR.permissoes)
    private val cerveja = Produto(id = 1, nome = "Cerveja", precoCentavos = 1200)

    @BeforeTest
    fun abrirCaixa() = runTest { CaixaService(caixas, repo).abrir(0, garcom) }

    @Test
    fun repositorioRecusaGravacaoComVersaoVelha() = runTest {
        val c = service.abrir("1", garcom)
        val lida = repo.buscar(c.id)!!
        repo.salvar(lida.copy(taxaServico = true)) // versão 0 -> 1
        assertFailsWith<ConflitoDeVersaoException> { repo.salvar(lida.copy(identificacao = "x")) } // ainda com versão 0
        assertEquals(1, repo.buscar(c.id)!!.versao)
    }

    @Test
    fun outroAparelhoGravandoNoMeioNaoPerdeItens() = runTest {
        val c = service.abrir("Mesa 1", garcom)
        var outroAparelhoVaiGravar = true
        repo.antesDeSalvar = { comanda ->
            if (outroAparelhoVaiGravar && comanda.itens.any { it.produtoId == 1L }) {
                outroAparelhoVaiGravar = false
                // Enquanto este aparelho adicionava a cerveja, outro lançou um chopp
                val atual = repo.buscar(c.id)!!
                repo.salvar(atual.copy(itens = atual.itens + ItemComanda(2, "Chopp", 990, 1)))
            }
        }
        service.adicionarItem(c.id, cerveja)
        val final = repo.buscar(c.id)!!
        assertEquals(setOf(1L, 2L), final.itens.map { it.produtoId }.toSet(), "os dois itens devem estar lá")
    }

    @Test
    fun cobrancaNaoCobraDuasVezesQuandoHaConflito() = runTest {
        val c = service.abrir("Mesa 1", garcom)
        service.adicionarItem(c.id, cerveja, 2)
        var outroAparelhoVaiGravar = true
        repo.antesDeSalvar = { comanda ->
            if (outroAparelhoVaiGravar && comanda.pagamentos.isNotEmpty()) {
                outroAparelhoVaiGravar = false
                val atual = repo.buscar(c.id)!!
                repo.salvar(atual.copy(itens = atual.itens + ItemComanda(2, "Chopp", 990, 1)))
            }
        }
        service.cobrar(c.id, FormaPagamento.PIX, operador = garcom)
        assertEquals(1, terminal.solicitacoes.size, "a maquininha cobra uma vez só")
        val final = repo.buscar(c.id)!!
        assertEquals(1, final.pagamentos.size)
        assertEquals(2400, final.totalPagoCentavos)
        // O chopp lançado no meio continua devendo
        assertEquals(990, final.restanteCentavos)
        assertEquals(StatusComanda.ABERTA, final.status)
    }

    @Test
    fun pagamentoAprovadoSemConexaoFicaPendenteEDepoisEGravado() = runTest {
        val c = service.abrir("Mesa 1", garcom)
        service.adicionarItem(c.id, cerveja)
        var semRede = true
        repo.antesDeSalvar = { if (semRede) throw SemConexaoException() }

        val r = service.cobrar(c.id, FormaPagamento.CREDITO, operador = garcom)
        assertIs<ResultadoPagamento.Aprovado>(r)
        assertTrue(r.registroPendente)
        assertEquals(1, service.pagamentosPendentes().size)
        assertTrue(repo.buscar(c.id)!!.pagamentos.isEmpty())

        // Ainda sem rede: continua pendente
        assertEquals(0, service.reenviarPendentes())
        assertEquals(1, service.pagamentosPendentes().size)

        semRede = false
        assertEquals(1, service.reenviarPendentes())
        assertTrue(service.pagamentosPendentes().isEmpty())
        val paga = repo.buscar(c.id)!!
        assertEquals(StatusComanda.PAGA, paga.status)
        assertEquals(1, paga.pagamentos.size)
        assertEquals(1, terminal.solicitacoes.size)
    }

    @Test
    fun pendenteQueOServidorJaRecebeuNaoDuplica() = runTest {
        val c = service.abrir("Mesa 1", garcom)
        service.adicionarItem(c.id, cerveja)
        // A gravação chega ao servidor, mas a resposta se perde (o aparelho acha que falhou)
        var perderResposta = true
        val original = repo.antesDeSalvar
        repo.antesDeSalvar = { comanda ->
            if (perderResposta && comanda.pagamentos.isNotEmpty()) {
                perderResposta = false
                repo.antesDeSalvar = original
                repo.salvar(comanda)
                throw SemConexaoException()
            }
        }
        service.cobrar(c.id, FormaPagamento.DEBITO, operador = garcom)
        assertEquals(1, service.pagamentosPendentes().size)
        assertEquals(1, service.reenviarPendentes())
        assertEquals(1, repo.buscar(c.id)!!.pagamentos.size, "não pode registrar o mesmo pagamento duas vezes")
    }
}

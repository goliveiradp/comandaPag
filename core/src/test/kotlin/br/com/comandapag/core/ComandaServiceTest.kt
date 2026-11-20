package br.com.comandapag.core

import br.com.comandapag.core.modelo.FormaPagamento
import br.com.comandapag.core.modelo.Produto
import br.com.comandapag.core.modelo.StatusComanda
import br.com.comandapag.core.pagamento.ResultadoPagamento
import br.com.comandapag.core.servico.ComandaService
import br.com.comandapag.core.servico.RegraDeNegocioException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComandaServiceTest {
    private val repo = ComandaRepositoryEmMemoria()
    private val terminal = TerminalFalso()
    private val service = ComandaService(repo, terminal, agora = { 1_000L })

    private val cerveja = Produto(id = 1, nome = "Cerveja", precoCentavos = 1200)
    private val porcao = Produto(id = 2, nome = "Porção de fritas", precoCentavos = 3550)

    @Test
    fun abreComanda() = runTest {
        val c = service.abrir("  Mesa 5 ")
        assertEquals("Mesa 5", c.identificacao)
        assertEquals(StatusComanda.ABERTA, repo.buscar(c.id)!!.status)
    }

    @Test
    fun naoAbreDuasComandasComMesmaIdentificacao() = runTest {
        service.abrir("12")
        assertFailsWith<RegraDeNegocioException> { service.abrir("12") }
        assertFailsWith<RegraDeNegocioException> { service.abrir("   ") }
    }

    @Test
    fun podeReabrirIdentificacaoDepoisDeFechar() = runTest {
        val c = service.abrir("12")
        service.cancelar(c.id)
        service.abrir("12")
    }

    @Test
    fun adicionaItensSomandoQuantidadeDoMesmoProduto() = runTest {
        val c = service.abrir("1")
        service.adicionarItem(c.id, cerveja)
        service.adicionarItem(c.id, cerveja, 2)
        service.adicionarItem(c.id, porcao)
        service.adicionarItem(c.id, cerveja, observacao = "sem gelo")
        val salva = repo.buscar(c.id)!!
        assertEquals(3, salva.itens.size)
        assertEquals(3, salva.itens[0].quantidade)
        assertEquals(5, salva.quantidadeItens)
        assertEquals(1200 * 4 + 3550L, salva.totalCentavos)
    }

    @Test
    fun toquesSimultaneosNaoPerdemItens() = runTest {
        val c = service.abrir("1")
        // O repositório falso suspende a cada leitura, como o banco real
        coroutineScope {
            repeat(20) { launch { service.adicionarItem(c.id, cerveja) } }
        }
        assertEquals(20, repo.buscar(c.id)!!.itens.single().quantidade)
    }

    @Test
    fun alteraERemoveItens() = runTest {
        val c = service.abrir("1")
        service.adicionarItem(c.id, cerveja)
        service.adicionarItem(c.id, porcao)
        service.alterarQuantidade(c.id, 0, 5)
        assertEquals(5, repo.buscar(c.id)!!.itens[0].quantidade)
        service.removerItem(c.id, 0)
        assertEquals(listOf("Porção de fritas"), repo.buscar(c.id)!!.itens.map { it.nome })
        assertFailsWith<RegraDeNegocioException> { service.removerItem(c.id, 7) }
    }

    @Test
    fun calculaTaxaDeServicoArredondada() = runTest {
        val c = service.abrir("1")
        service.adicionarItem(c.id, porcao) // 35,50 -> 10% = 3,55
        val com = service.definirTaxaServico(c.id, true)
        assertEquals(355, com.taxaServicoCentavos)
        assertEquals(3905, com.totalCentavos)
        val outra = service.abrir("2")
        service.adicionarItem(outra.id, Produto(id = 3, nome = "Água", precoCentavos = 455))
        assertEquals(46, service.definirTaxaServico(outra.id, true).taxaServicoCentavos) // 45,5 -> 46
    }

    @Test
    fun cobrancaAprovadaFechaComanda() = runTest {
        val c = service.abrir("7", taxaServico = true)
        service.adicionarItem(c.id, cerveja, 2)
        val status = mutableListOf<String>()
        val r = service.cobrar(c.id, FormaPagamento.CREDITO, parcelas = 2) { status += it }

        assertIs<ResultadoPagamento.Aprovado>(r)
        assertEquals(listOf("PROCESSANDO"), status)
        val s = terminal.solicitacoes.single()
        assertEquals(2640, s.valorCentavos)
        assertEquals(2, s.parcelas)
        assertEquals("CMD${c.id}", s.referencia)

        val paga = repo.buscar(c.id)!!
        assertEquals(StatusComanda.PAGA, paga.status)
        assertEquals(2640, paga.pagamento!!.valorCentavos)
        assertEquals("COD1", paga.pagamento!!.codigoTransacao)
        assertFailsWith<RegraDeNegocioException> { service.adicionarItem(c.id, cerveja) }
    }

    @Test
    fun cobrancaRecusadaMantemComandaAberta() = runTest {
        terminal.resposta = ResultadoPagamento.Recusado("SALDO INSUFICIENTE", "51")
        val c = service.abrir("7")
        service.adicionarItem(c.id, cerveja)
        val r = service.cobrar(c.id, FormaPagamento.DEBITO)
        assertIs<ResultadoPagamento.Recusado>(r)
        val aberta = repo.buscar(c.id)!!
        assertTrue(aberta.estaAberta)
        assertNull(aberta.pagamento)
    }

    @Test
    fun validaCobranca() = runTest {
        val c = service.abrir("7")
        assertFailsWith<RegraDeNegocioException> { service.cobrar(c.id, FormaPagamento.PIX) }
        service.adicionarItem(c.id, cerveja)
        assertFailsWith<RegraDeNegocioException> { service.cobrar(c.id, FormaPagamento.DEBITO, parcelas = 3) }
        assertFailsWith<RegraDeNegocioException> { service.cobrar(c.id, FormaPagamento.CREDITO, parcelas = 13) }
        assertTrue(terminal.solicitacoes.isEmpty())
    }

    @Test
    fun referenciaTemNoMaximoDezCaracteres() {
        assertEquals("CMD1234567", ComandaService.referencia(123456789))
    }
}

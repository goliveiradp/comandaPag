package br.com.comandapag.core

import br.com.comandapag.core.impressao.ExtratoComanda
import br.com.comandapag.core.impressao.RelatorioCaixa
import br.com.comandapag.core.modelo.FormaPagamento
import br.com.comandapag.core.modelo.Perfil
import br.com.comandapag.core.modelo.Permissao
import br.com.comandapag.core.modelo.Produto
import br.com.comandapag.core.modelo.StatusComanda
import br.com.comandapag.core.modelo.Usuario
import br.com.comandapag.core.pagamento.ResultadoPagamento
import br.com.comandapag.core.servico.CaixaService
import br.com.comandapag.core.servico.ComandaService
import br.com.comandapag.core.servico.RegraDeNegocioException
import br.com.comandapag.core.servico.SemPermissaoException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaixaEstornoTest {
    private val comandas = ComandaRepositoryEmMemoria()
    private val caixas = CaixaRepositoryEmMemoria()
    private val terminal = TerminalFalso()
    private var relogio = 1_000L
    private val trava = Mutex()
    private val service = ComandaService(comandas, terminal, caixas, trava) { relogio }
    private val caixaService = CaixaService(caixas, comandas, trava) { relogio }

    private val garcom = Usuario(id = 1, nome = "Ana", permissoes = Perfil.GARCOM.permissoes)
    private val operador = Usuario(id = 2, nome = "Dani", permissoes = Perfil.CAIXA.permissoes)
    private val gerente = Usuario(id = 3, nome = "Bruno", permissoes = Perfil.GERENTE.permissoes)
    private val cerveja = Produto(id = 1, nome = "Cerveja", precoCentavos = 1200)

    @Test
    fun naoRecebeComCaixaFechado() = runTest {
        val c = service.abrir("1", garcom)
        service.adicionarItem(c.id, cerveja)
        val e = assertFailsWith<RegraDeNegocioException> { service.receberDinheiro(c.id, 2000, garcom) }
        assertTrue(e.message!!.contains("caixa", ignoreCase = true))
        assertFailsWith<RegraDeNegocioException> { service.cobrar(c.id, FormaPagamento.PIX, operador = garcom) }
        assertTrue(terminal.solicitacoes.isEmpty())
    }

    @Test
    fun operarCaixaExigePermissao() = runTest {
        assertFailsWith<SemPermissaoException> { caixaService.abrir(0, garcom) }
        caixaService.abrir(0, operador)
        assertFailsWith<RegraDeNegocioException> { caixaService.abrir(0, operador) } // já aberto
        assertFailsWith<SemPermissaoException> { caixaService.sangria(100, "", garcom) }
    }

    @Test
    fun fechamentoConfereDinheiroDaGaveta() = runTest {
        caixaService.abrir(fundoDeTrocoCentavos = 10_000, operador = operador) // R$ 100 de troco

        // Vendas: 2 cervejas no dinheiro (R$ 24, entregou 50) e 1 no PIX (R$ 12)
        val c1 = service.abrir("1", garcom)
        service.adicionarItem(c1.id, cerveja, 2)
        service.receberDinheiro(c1.id, 5_000, garcom)
        val c2 = service.abrir("2", garcom)
        service.adicionarItem(c2.id, cerveja)
        service.cobrar(c2.id, FormaPagamento.PIX, operador = garcom)

        caixaService.suprimento(2_000, "Troco extra", operador) // +20
        caixaService.sangria(5_000, "Cofre", operador) // -50
        assertFailsWith<RegraDeNegocioException> { caixaService.sangria(1_000_000, "", operador) }

        val parcial = caixaService.resumo(caixaService.caixaAberto()!!)
        assertEquals(2_400, parcial.vendasPorForma[FormaPagamento.DINHEIRO])
        assertEquals(1_200, parcial.vendasPorForma[FormaPagamento.PIX])
        assertEquals(3_600, parcial.totalVendasCentavos)
        // 100 + 24 + 20 - 50 = 94
        assertEquals(9_400, parcial.dinheiroEsperadoCentavos)
        assertNull(parcial.diferencaCentavos)

        relogio = 9_000
        val fechado = caixaService.fechar(valorContadoCentavos = 9_000, operador = operador) // contou 90
        assertEquals(-400, fechado.diferencaCentavos) // faltam R$ 4
        assertEquals(9_000L, fechado.caixa.fechadoEm)
        assertNull(caixaService.caixaAberto())

        val linhas = RelatorioCaixa.gerar(fechado, "Bar", formatarData = { "x" })
        println(linhas.joinToString("\n"))
        assertTrue(linhas.all { it.length <= ExtratoComanda.COLUNAS })
        assertTrue(linhas.any { it.contains("FALTA") && it.endsWith("-R$ 4,00") })
        assertTrue(linhas.any { it.contains("Esperado") && it.endsWith("R$ 94,00") })

        // Novo caixa não enxerga as vendas do anterior
        caixaService.abrir(0, operador)
        assertEquals(0, caixaService.resumo(caixaService.caixaAberto()!!).totalVendasCentavos)
    }

    @Test
    fun estornoNoCartaoReabreAComanda() = runTest {
        caixaService.abrir(0, operador)
        val c = service.abrir("Mesa 1", garcom)
        service.adicionarItem(c.id, cerveja, 2)
        service.cobrar(c.id, FormaPagamento.CREDITO, parcelas = 2, operador = garcom)
        assertEquals(StatusComanda.PAGA, comandas.buscar(c.id)!!.status)

        // Garçom não pode estornar
        assertFailsWith<SemPermissaoException> { service.estornar(c.id, 0, garcom) }

        val r = service.estornar(c.id, 0, gerente)
        assertIs<ResultadoPagamento.Aprovado>(r)
        val e = terminal.estornos.single()
        assertEquals("COD1", e.codigoTransacao)
        assertEquals(FormaPagamento.CREDITO, e.forma)
        assertEquals(2_400, e.valorCentavos)

        val reaberta = comandas.buscar(c.id)!!
        assertEquals(StatusComanda.ABERTA, reaberta.status)
        assertNull(reaberta.fechadaEm)
        assertTrue(reaberta.pagamentos.single().estornado)
        assertEquals("Bruno", reaberta.pagamentos.single().estornadoPor)
        assertEquals(2_400, reaberta.restanteCentavos)
        assertFailsWith<RegraDeNegocioException> { service.estornar(c.id, 0, gerente) } // de novo, não

        val resumo = caixaService.resumo(caixaService.caixaAberto()!!)
        assertEquals(2_400, resumo.estornosPorForma[FormaPagamento.CREDITO])

        // Tudo estornado: agora pode cancelar
        service.cancelar(c.id, gerente)
    }

    @Test
    fun estornoRecusadoNaMaquininhaNaoMudaNada() = runTest {
        caixaService.abrir(0, operador)
        val c = service.abrir("1", garcom)
        service.adicionarItem(c.id, cerveja)
        service.cobrar(c.id, FormaPagamento.DEBITO, operador = garcom)
        terminal.respostaEstorno = ResultadoPagamento.Recusado("PRAZO EXPIRADO")
        assertIs<ResultadoPagamento.Recusado>(service.estornar(c.id, 0, gerente))
        val c2 = comandas.buscar(c.id)!!
        assertEquals(StatusComanda.PAGA, c2.status)
        assertTrue(!c2.pagamentos.single().estornado)
        assertTrue(caixaService.caixaAberto()!!.movimentos.isEmpty())
    }

    @Test
    fun estornoEmDinheiroSaiDaGaveta() = runTest {
        caixaService.abrir(5_000, operador)
        val c = service.abrir("1", garcom)
        service.adicionarItem(c.id, cerveja)
        service.receberDinheiro(c.id, 2_000, garcom) // abate 12, troco 8
        service.estornar(c.id, 0, gerente)
        assertTrue(terminal.estornos.isEmpty(), "dinheiro não passa pela maquininha")
        val resumo = caixaService.resumo(caixaService.caixaAberto()!!)
        // 50 + 12 - 12 = 50
        assertEquals(5_000, resumo.dinheiroEsperadoCentavos)
    }

    @Test
    fun estornoParcialNaDivisao() = runTest {
        caixaService.abrir(0, operador)
        val c = service.abrir("1", garcom)
        service.adicionarItem(c.id, cerveja, 2) // 24
        service.cobrar(c.id, FormaPagamento.PIX, operador = garcom, valorCentavos = 1_000)
        service.receberDinheiro(c.id, 1_400, garcom) // quitou
        assertEquals(StatusComanda.PAGA, comandas.buscar(c.id)!!.status)
        service.estornar(c.id, 0, gerente) // estorna o PIX de 10
        val comanda = comandas.buscar(c.id)!!
        assertEquals(1_000, comanda.restanteCentavos)
        assertTrue(comanda.estaAberta)
        // Com um pagamento válido ainda, não pode cancelar
        assertFailsWith<RegraDeNegocioException> { service.cancelar(c.id, gerente) }
        // Recebe de novo no crédito e fecha
        service.cobrar(c.id, FormaPagamento.CREDITO, operador = garcom)
        assertEquals(StatusComanda.PAGA, comandas.buscar(c.id)!!.status)
        assertEquals(3, comandas.buscar(c.id)!!.pagamentos.size)
    }

    @Test
    fun perfisIncluemAsNovasPermissoes() {
        assertTrue(Perfil.CAIXA.permissoes.contains(Permissao.OPERAR_CAIXA))
        assertTrue(Perfil.GERENTE.permissoes.containsAll(listOf(Permissao.ESTORNAR, Permissao.OPERAR_CAIXA)))
        assertTrue(!Perfil.GARCOM.permissoes.contains(Permissao.ESTORNAR))
    }
}

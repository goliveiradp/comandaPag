package br.com.comandapag.core

import br.com.comandapag.core.modelo.FormaPagamento
import br.com.comandapag.core.modelo.Perfil
import br.com.comandapag.core.modelo.Permissao
import br.com.comandapag.core.modelo.Produto
import br.com.comandapag.core.modelo.StatusComanda
import br.com.comandapag.core.modelo.Usuario
import br.com.comandapag.core.pagamento.ResultadoPagamento
import br.com.comandapag.core.servico.ComandaService
import br.com.comandapag.core.servico.RegraDeNegocioException
import br.com.comandapag.core.servico.SemPermissaoException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ComandaServiceTest {
    private val repo = ComandaRepositoryEmMemoria()
    private val terminal = TerminalFalso()
    private val service = ComandaService(repo, terminal, agora = { 1_000L })

    private val garcom = Usuario(id = 10, nome = "Ana", permissoes = Perfil.GARCOM.permissoes)
    private val gerente = Usuario(id = 20, nome = "Bruno", permissoes = Perfil.GERENTE.permissoes)
    private val semCobrar = Usuario(id = 30, nome = "Caio", permissoes = emptySet())

    private val cerveja = Produto(id = 1, nome = "Cerveja", precoCentavos = 1200)
    private val porcao = Produto(id = 2, nome = "Porção de fritas", precoCentavos = 3550)

    @Test
    fun abreComanda() = runTest {
        val c = service.abrir("  Mesa 5 ", garcom)
        assertEquals("Mesa 5", c.identificacao)
        assertEquals("Ana", repo.buscar(c.id)!!.garcomNome)
        assertEquals(10, repo.buscar(c.id)!!.garcomId)
        assertEquals(StatusComanda.ABERTA, repo.buscar(c.id)!!.status)
    }

    @Test
    fun naoAbreDuasComandasComMesmaIdentificacao() = runTest {
        service.abrir("12", garcom)
        assertFailsWith<RegraDeNegocioException> { service.abrir("12", garcom) }
        assertFailsWith<RegraDeNegocioException> { service.abrir("   ", garcom) }
    }

    @Test
    fun podeReabrirIdentificacaoDepoisDeFechar() = runTest {
        val c = service.abrir("12", garcom)
        service.cancelar(c.id, gerente)
        service.abrir("12", garcom)
    }

    @Test
    fun adicionaItensSomandoQuantidadeDoMesmoProduto() = runTest {
        val c = service.abrir("1", garcom)
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
        val c = service.abrir("1", garcom)
        // O repositório falso suspende a cada leitura, como o banco real
        coroutineScope {
            repeat(20) { launch { service.adicionarItem(c.id, cerveja) } }
        }
        assertEquals(20, repo.buscar(c.id)!!.itens.single().quantidade)
    }

    @Test
    fun alteraERemoveItens() = runTest {
        val c = service.abrir("1", garcom)
        service.adicionarItem(c.id, cerveja)
        service.adicionarItem(c.id, porcao)
        service.alterarQuantidade(c.id, 0, 5, garcom)
        assertEquals(5, repo.buscar(c.id)!!.itens[0].quantidade)
        service.removerItem(c.id, 0, gerente)
        assertEquals(listOf("Porção de fritas"), repo.buscar(c.id)!!.itens.map { it.nome })
        assertFailsWith<RegraDeNegocioException> { service.removerItem(c.id, 7, gerente) }
    }

    @Test
    fun calculaTaxaDeServicoArredondada() = runTest {
        val c = service.abrir("1", garcom)
        service.adicionarItem(c.id, porcao) // 35,50 -> 10% = 3,55
        val com = service.definirTaxaServico(c.id, true, gerente)
        assertEquals(355, com.taxaServicoCentavos)
        assertEquals(3905, com.totalCentavos)
        val outra = service.abrir("2", garcom)
        service.adicionarItem(outra.id, Produto(id = 3, nome = "Água", precoCentavos = 455))
        assertEquals(46, service.definirTaxaServico(outra.id, true, gerente).taxaServicoCentavos) // 45,5 -> 46
    }

    @Test
    fun cobrancaAprovadaFechaComanda() = runTest {
        val c = service.abrir("7", garcom, taxaServico = true)
        service.adicionarItem(c.id, cerveja, 2)
        val status = mutableListOf<String>()
        val r = service.cobrar(c.id, FormaPagamento.CREDITO, parcelas = 2, operador = garcom) { status += it }

        assertIs<ResultadoPagamento.Aprovado>(r)
        assertEquals(listOf("PROCESSANDO"), status)
        val s = terminal.solicitacoes.single()
        assertEquals(2640, s.valorCentavos)
        assertEquals(2, s.parcelas)
        assertEquals("C${c.id}P1", s.referencia)

        val paga = repo.buscar(c.id)!!
        assertEquals(StatusComanda.PAGA, paga.status)
        assertEquals(2640, paga.pagamentos.single().valorCentavos)
        assertEquals("COD1", paga.pagamentos.single().codigoTransacao)
        assertEquals("Ana", paga.pagamentos.single().recebidoPor)
        assertEquals("Ana", paga.fechadaPor)
        assertFailsWith<RegraDeNegocioException> { service.adicionarItem(c.id, cerveja) }
    }

    @Test
    fun cobrancaRecusadaMantemComandaAberta() = runTest {
        terminal.resposta = ResultadoPagamento.Recusado("SALDO INSUFICIENTE", "51")
        val c = service.abrir("7", garcom)
        service.adicionarItem(c.id, cerveja)
        val r = service.cobrar(c.id, FormaPagamento.DEBITO, operador = garcom)
        assertIs<ResultadoPagamento.Recusado>(r)
        val aberta = repo.buscar(c.id)!!
        assertTrue(aberta.estaAberta)
        assertTrue(aberta.pagamentos.isEmpty())
    }

    @Test
    fun validaCobranca() = runTest {
        val c = service.abrir("7", garcom)
        assertFailsWith<RegraDeNegocioException> { service.cobrar(c.id, FormaPagamento.PIX, operador = garcom) }
        service.adicionarItem(c.id, cerveja)
        assertFailsWith<RegraDeNegocioException> { service.cobrar(c.id, FormaPagamento.DEBITO, parcelas = 3, operador = garcom) }
        assertFailsWith<RegraDeNegocioException> { service.cobrar(c.id, FormaPagamento.CREDITO, parcelas = 13, operador = garcom) }
        assertTrue(terminal.solicitacoes.isEmpty())
    }

    @Test
    fun garcomSoAumentaQuantidadeSemPermissao() = runTest {
        val c = service.abrir("1", garcom)
        service.adicionarItem(c.id, cerveja, 3)
        service.alterarQuantidade(c.id, 0, 4, garcom) // aumentar: liberado
        val e = assertFailsWith<SemPermissaoException> { service.alterarQuantidade(c.id, 0, 2, garcom) }
        assertEquals(Permissao.REMOVER_ITENS, e.permissao)
        assertFailsWith<SemPermissaoException> { service.removerItem(c.id, 0, garcom) }
        assertEquals(4, repo.buscar(c.id)!!.itens[0].quantidade)
        // Com autorização do gerente
        service.alterarQuantidade(c.id, 0, 2, gerente)
        assertEquals(2, repo.buscar(c.id)!!.itens[0].quantidade)
    }

    @Test
    fun cancelarETaxaExigemPermissao() = runTest {
        val c = service.abrir("1", garcom, taxaServico = true)
        assertFailsWith<SemPermissaoException> { service.cancelar(c.id, garcom) }
        assertFailsWith<SemPermissaoException> { service.definirTaxaServico(c.id, false, garcom) }
        service.definirTaxaServico(c.id, true, garcom) // sem mudança: não exige nada
        service.definirTaxaServico(c.id, false, gerente)
        val cancelada = service.cancelar(c.id, gerente)
        assertEquals(StatusComanda.CANCELADA, cancelada.status)
        assertEquals("Bruno", cancelada.fechadaPor)
    }

    @Test
    fun cobrarExigePermissao() = runTest {
        val c = service.abrir("1", semCobrar)
        service.adicionarItem(c.id, cerveja)
        assertFailsWith<SemPermissaoException> { service.cobrar(c.id, FormaPagamento.PIX, operador = semCobrar) }
        assertTrue(terminal.solicitacoes.isEmpty())
    }

    @Test
    fun referenciaTemNoMaximoDezCaracteres() {
        assertEquals("C7P2", ComandaService.referencia(7, 2))
        assertEquals("C123456789", ComandaService.referencia(123456789, 3))
    }

    @Test
    fun divideAContaEmTresComFormasDiferentes() = runTest {
        val c = service.abrir("Mesa 1", garcom)
        service.adicionarItem(c.id, Produto(id = 9, nome = "Rodízio", precoCentavos = 10000)) // R$ 100,00

        // Pessoa 1: PIX de 1/3 (arredondado para cima)
        val parte1 = ComandaService.valorPorPessoa(10000, 3)
        assertEquals(3334, parte1)
        assertIs<ResultadoPagamento.Aprovado>(
            service.cobrar(c.id, FormaPagamento.PIX, operador = garcom, valorCentavos = parte1),
        )
        assertEquals(6666, repo.buscar(c.id)!!.restanteCentavos)
        assertTrue(repo.buscar(c.id)!!.estaAberta)

        // Pessoa 2: dinheiro, entrega R$ 50,00 para pagar 33,33 -> troco 16,67
        val parte2 = ComandaService.valorPorPessoa(6666, 2)
        assertEquals(3333, parte2)
        val r = service.receberDinheiro(c.id, recebidoCentavos = 5000, operador = garcom, valorCentavos = parte2)
        assertEquals(1667, r.trocoCentavos)
        assertFalse(r.quitada)

        // Pessoa 3: crédito do resto (sem informar valor = tudo o que falta)
        service.cobrar(c.id, FormaPagamento.CREDITO, operador = garcom)
        assertEquals(3333, terminal.solicitacoes.last().valorCentavos)
        assertEquals("C${c.id}P3", terminal.solicitacoes.last().referencia)

        val paga = repo.buscar(c.id)!!
        assertEquals(StatusComanda.PAGA, paga.status)
        assertEquals(listOf(3334L, 3333L, 3333L), paga.pagamentos.map { it.valorCentavos })
        assertEquals(10000, paga.totalPagoCentavos)
        assertEquals(0, paga.restanteCentavos)
    }

    @Test
    fun dinheiroComTrocoQuitaAComanda() = runTest {
        val c = service.abrir("1", garcom)
        service.adicionarItem(c.id, cerveja, 2) // 24,00
        val r = service.receberDinheiro(c.id, recebidoCentavos = 5000, operador = garcom)
        assertEquals(2600, r.trocoCentavos)
        assertTrue(r.quitada)
        val p = repo.buscar(c.id)!!.pagamentos.single()
        assertEquals(2400, p.valorCentavos)
        assertEquals(5000, p.valorRecebidoCentavos)
        assertEquals("Ana", repo.buscar(c.id)!!.fechadaPor)
        assertTrue(terminal.solicitacoes.isEmpty(), "dinheiro não passa pela maquininha")
    }

    @Test
    fun dinheiroAbaixoDoValorAbateSoOQueFoiEntregue() = runTest {
        val c = service.abrir("1", garcom)
        service.adicionarItem(c.id, cerveja, 2) // 24,00
        val r = service.receberDinheiro(c.id, recebidoCentavos = 1000, operador = garcom)
        assertEquals(0, r.trocoCentavos)
        assertFalse(r.quitada)
        assertEquals(1400, r.comanda.restanteCentavos)
    }

    @Test
    fun recusadoNaoRegistraPagamentoParcial() = runTest {
        val c = service.abrir("1", garcom)
        service.adicionarItem(c.id, cerveja, 2)
        terminal.resposta = ResultadoPagamento.Recusado("NEGADA")
        service.cobrar(c.id, FormaPagamento.DEBITO, operador = garcom, valorCentavos = 1000)
        assertTrue(repo.buscar(c.id)!!.pagamentos.isEmpty())
    }

    @Test
    fun valoresInvalidosNaDivisao() = runTest {
        val c = service.abrir("1", garcom)
        service.adicionarItem(c.id, cerveja) // 12,00
        assertFailsWith<RegraDeNegocioException> {
            service.cobrar(c.id, FormaPagamento.PIX, operador = garcom, valorCentavos = 1300)
        }
        assertFailsWith<RegraDeNegocioException> {
            service.cobrar(c.id, FormaPagamento.PIX, operador = garcom, valorCentavos = 0)
        }
        assertFailsWith<RegraDeNegocioException> {
            service.cobrar(c.id, FormaPagamento.DINHEIRO, operador = garcom)
        }
        assertFailsWith<RegraDeNegocioException> {
            service.receberDinheiro(c.id, recebidoCentavos = 0, operador = garcom)
        }
        assertFailsWith<SemPermissaoException> {
            service.receberDinheiro(c.id, recebidoCentavos = 2000, operador = semCobrar)
        }
        assertTrue(terminal.solicitacoes.isEmpty())
    }

    @Test
    fun depoisDePagarParteNaoPodeReduzirAbaixoDoPago() = runTest {
        val c = service.abrir("1", garcom)
        service.adicionarItem(c.id, cerveja, 3) // 36,00
        service.cobrar(c.id, FormaPagamento.PIX, operador = garcom, valorCentavos = 3000)

        // 36 -> 24: abaixo dos 30 já pagos
        assertFailsWith<RegraDeNegocioException> { service.alterarQuantidade(c.id, 0, 2, gerente) }
        assertFailsWith<RegraDeNegocioException> { service.cancelar(c.id, gerente) }
        // Adicionar continua liberado
        service.adicionarItem(c.id, cerveja)
        assertEquals(1800, repo.buscar(c.id)!!.restanteCentavos)
        // 48 -> 36 ainda fica acima dos 30 pagos
        service.alterarQuantidade(c.id, 0, 3, gerente)
        assertEquals(600, repo.buscar(c.id)!!.restanteCentavos)
    }

    @Test
    fun valorPorPessoaFechaASoma() {
        for (total in listOf(1L, 99L, 10000L, 12345L)) {
            for (pessoas in 1..7) {
                var restante = total
                for (p in pessoas downTo 1) restante -= ComandaService.valorPorPessoa(restante, p)
                assertEquals(0, restante, "total=$total pessoas=$pessoas")
            }
        }
    }
}

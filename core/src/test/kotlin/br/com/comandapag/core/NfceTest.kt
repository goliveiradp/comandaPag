package br.com.comandapag.core

import br.com.comandapag.core.fiscal.AmbienteFiscal
import br.com.comandapag.core.fiscal.DanfeNfce
import br.com.comandapag.core.fiscal.MontagemNfce
import br.com.comandapag.core.fiscal.NfceService
import br.com.comandapag.core.fiscal.RespostaNfce
import br.com.comandapag.core.impressao.ExtratoComanda
import br.com.comandapag.core.modelo.Comanda
import br.com.comandapag.core.modelo.DadosFiscais
import br.com.comandapag.core.modelo.FormaPagamento
import br.com.comandapag.core.modelo.ItemComanda
import br.com.comandapag.core.modelo.Pagamento
import br.com.comandapag.core.modelo.Perfil
import br.com.comandapag.core.modelo.Produto
import br.com.comandapag.core.modelo.StatusComanda
import br.com.comandapag.core.modelo.StatusNfce
import br.com.comandapag.core.modelo.Usuario
import br.com.comandapag.core.servico.CaixaService
import br.com.comandapag.core.servico.ComandaService
import br.com.comandapag.core.servico.RegraDeNegocioException
import br.com.comandapag.core.servico.SemPermissaoException
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NfceTest {
    private val comandas = ComandaRepositoryEmMemoria()
    private val produtos = ProdutoRepositoryEmMemoria()
    private val caixas = CaixaRepositoryEmMemoria()
    private val emissor = EmissorNfceFalso()
    private var relogio = 1_763_766_000_000L // 21/11/2025 20:00 (UTC-3)
    private val service = ComandaService(comandas, TerminalFalso(), caixas) { relogio }
    private val nfce = NfceService(comandas, produtos, emissor, { relogio }, ZoneId.of("America/Fortaleza"))

    private val garcom = Usuario(id = 1, nome = "Ana", permissoes = Perfil.GARCOM.permissoes)
    private val gerente = Usuario(id = 2, nome = "Bruno", permissoes = Perfil.GERENTE.permissoes)
    private val refeicao = DadosFiscais(ncm = "21069090")
    private val bebidaSt = DadosFiscais(ncm = "22030000", cfop = "5405", icmsSituacao = "500")

    private lateinit var prato: Produto
    private lateinit var cerveja: Produto

    @BeforeTest
    fun preparar() = runTest {
        prato = Produto(nome = "Prato feito", precoCentavos = 2500, fiscal = refeicao).let { it.copy(id = produtos.salvar(it)) }
        cerveja = Produto(nome = "Cerveja", precoCentavos = 1200, fiscal = bebidaSt).let { it.copy(id = produtos.salvar(it)) }
        CaixaService(caixas, comandas).abrir(0, gerente)
    }

    private suspend fun comandaPaga(taxa: Boolean = true): Comanda {
        val c = service.abrir("Mesa 3", garcom, taxaServico = taxa)
        service.adicionarItem(c.id, prato)
        service.adicionarItem(c.id, cerveja, 2)
        // 25 + 24 = 49,00 + 4,90 = 53,90: 20,00 no PIX e o resto em dinheiro (entregou 50)
        service.cobrar(c.id, FormaPagamento.PIX, operador = garcom, valorCentavos = 2000)
        service.receberDinheiro(c.id, 5000, garcom)
        return comandas.buscar(c.id)!!.also { assertEquals(StatusComanda.PAGA, it.status) }
    }

    @Test
    fun montaANotaComRateioDoServicoEPagamentos() = runTest {
        val c = comandaPaga()
        val nota = MontagemNfce.montar(c, mapOf(prato.id to prato, cerveja.id to cerveja), "2025-11-21T20:00:00-03:00")
        assertEquals(2, nota.itens.size)
        assertEquals(listOf(2500L, 2400L), nota.itens.map { it.valorBrutoCentavos })
        assertEquals(490, nota.itens.sumOf { it.outrasDespesasCentavos }, "a taxa inteira foi rateada")
        assertEquals(listOf(250L, 240L), nota.itens.map { it.outrasDespesasCentavos })
        assertEquals("5405", nota.itens[1].fiscal.cfop)
        assertEquals(listOf("17", "01"), nota.pagamentos.map { it.codigo })
        assertEquals(listOf(2000L, 5000L), nota.pagamentos.map { it.valorCentavos }, "no dinheiro vai o valor entregue")
        assertEquals(1610, nota.trocoCentavos) // 50,00 - 33,90
        assertEquals(5390, nota.totalCentavos)
        assertTrue(nota.informacoesAdicionais.contains("Mesa 3"))
    }

    @Test
    fun rateioDeCentavosFechaExato() {
        val itens = listOf(ItemComanda(1, "A", 333, 1), ItemComanda(2, "B", 333, 1), ItemComanda(3, "C", 334, 1))
        val c = Comanda(id = 1, identificacao = "1", abertaEm = 0, itens = itens, taxaServico = true, status = StatusComanda.PAGA,
            pagamentos = listOf(Pagamento(FormaPagamento.DEBITO, 1100, pagoEm = 0, bandeira = "MASTERCARD")))
        val p = (1L..3L).associateWith { Produto(id = it, nome = "x", precoCentavos = 1, fiscal = refeicao) }
        val nota = MontagemNfce.montar(c, p, "x")
        assertEquals(100, nota.itens.sumOf { it.outrasDespesasCentavos })
        assertEquals("02", nota.pagamentos.single().bandeira)
        assertEquals("04", nota.pagamentos.single().codigo)
    }

    @Test
    fun pagamentoEstornadoNaoEntraNaNota() = runTest {
        val c = service.abrir("1", garcom)
        service.adicionarItem(c.id, prato)
        service.cobrar(c.id, FormaPagamento.CREDITO, operador = garcom)
        service.estornar(c.id, 0, gerente)
        service.cobrar(c.id, FormaPagamento.DEBITO, operador = garcom)
        val nota = MontagemNfce.montar(comandas.buscar(c.id)!!, mapOf(prato.id to prato), "x")
        assertEquals(listOf("04"), nota.pagamentos.map { it.codigo })
    }

    @Test
    fun exigeNcmCadastrado() = runTest {
        val semNcm = Produto(nome = "Suco", precoCentavos = 800).let { it.copy(id = produtos.salvar(it)) }
        val c = service.abrir("1", garcom)
        service.adicionarItem(c.id, semNcm)
        service.receberDinheiro(c.id, 800, garcom)
        val e = assertFailsWith<RegraDeNegocioException> { nfce.emitir(c.id, garcom) }
        assertTrue(e.message!!.contains("Suco"))
        assertTrue(emissor.emitidas.isEmpty())
    }

    @Test
    fun emiteEGuardaANotaNaComanda() = runTest {
        val c = comandaPaga()
        val r = nfce.emitir(c.id, garcom, cpf = "529.982.247-25")
        assertEquals(StatusNfce.AUTORIZADA, r.nota.status)
        assertEquals("cmd${c.id}", emissor.emitidas.single().first)
        assertEquals("52998224725", emissor.emitidas.single().second.cpf)
        assertEquals("2025-11-21T20:00:00-03:00", emissor.emitidas.single().second.dataEmissao)
        val salva = comandas.buscar(c.id)!!.nfce!!
        assertTrue(salva.autorizada)
        assertEquals("221260000000001", salva.protocolo)
        assertEquals(AmbienteFiscal.HOMOLOGACAO.name, salva.ambiente)
        // Não emite de novo
        assertFailsWith<RegraDeNegocioException> { nfce.emitir(c.id, garcom) }
    }

    @Test
    fun validacoesAntesDeEmitir() = runTest {
        val aberta = service.abrir("Aberta", garcom)
        service.adicionarItem(aberta.id, prato)
        assertFailsWith<RegraDeNegocioException> { nfce.emitir(aberta.id, garcom) } // não paga
        val c = comandaPaga()
        assertFailsWith<RegraDeNegocioException> { nfce.emitir(c.id, garcom, cpf = "111.111.111-11") }
        assertFailsWith<SemPermissaoException> { nfce.emitir(c.id, Usuario(9, "X", emptySet())) }
        emissor.ativo = false
        assertFailsWith<RegraDeNegocioException> { nfce.emitir(c.id, garcom) }
        assertTrue(emissor.emitidas.isEmpty())
    }

    @Test
    fun rejeicaoMudaAReferenciaDaProximaTentativa() = runTest {
        val c = comandaPaga()
        emissor.rejeitar = "Rejeição: NCM inexistente"
        val e = assertFailsWith<RegraDeNegocioException> { nfce.emitir(c.id, garcom) }
        assertTrue(e.message!!.contains("NCM inexistente"))
        assertEquals(StatusNfce.REJEITADA, comandas.buscar(c.id)!!.nfce!!.status)
        emissor.rejeitar = null
        nfce.emitir(c.id, garcom)
        assertEquals(listOf("cmd${c.id}", "cmd${c.id}-1"), emissor.emitidas.map { it.first })
    }

    @Test
    fun respostaPerdidaConsultaANotaEmVezDeDuplicar() = runTest {
        val c = comandaPaga()
        emissor.notas["cmd${c.id}"] = RespostaNfce(true, numero = "7", serie = "1", chave = "1".repeat(44), ambiente = AmbienteFiscal.HOMOLOGACAO)
        emissor.jaEmitida = true
        val r = nfce.emitir(c.id, garcom)
        assertEquals("7", r.nota.numero)
        assertTrue(emissor.emitidas.isEmpty(), "não enviou outra nota")
    }

    @Test
    fun cancelamentoEm30MinutosEEstornoDepois() = runTest {
        val c = comandaPaga()
        nfce.emitir(c.id, garcom)
        // Com NFC-e autorizada, não estorna
        val e = assertFailsWith<RegraDeNegocioException> { service.estornar(c.id, 0, gerente) }
        assertTrue(e.message!!.contains("Cancele a nota"))
        assertFailsWith<SemPermissaoException> { nfce.cancelar(c.id, garcom) }

        relogio += 10 * 60 * 1000
        val cancelada = nfce.cancelar(c.id, gerente)
        assertEquals(StatusNfce.CANCELADA, cancelada.nfce!!.status)
        assertEquals("cmd${c.id}", emissor.canceladas.single().first)
        assertTrue(emissor.canceladas.single().second.length >= 15)
        // Agora pode estornar
        service.estornar(c.id, 0, gerente)
    }

    @Test
    fun naoCancelaDepoisDe30Minutos() = runTest {
        val c = comandaPaga()
        nfce.emitir(c.id, garcom)
        relogio += 31 * 60 * 1000
        val e = assertFailsWith<RegraDeNegocioException> { nfce.cancelar(c.id, gerente) }
        assertTrue(e.message!!.contains("30 minutos"))
        assertTrue(emissor.canceladas.isEmpty())
    }

    @Test
    fun danfeCabeNaBobinaETemOsDadosObrigatorios() = runTest {
        val c = comandaPaga()
        val r = nfce.emitir(c.id, garcom, cpf = "52998224725")
        val danfe = DanfeNfce.gerar(r.comanda, r.nota, r.emitente, formatarData = { "21/11/2025 20:00:00" })
        val texto = (danfe.antes + danfe.depois).joinToString("\n")
        println(texto)
        assertTrue((danfe.antes + danfe.depois).all { it.length <= ExtratoComanda.COLUNAS })
        assertTrue(texto.contains("CNPJ 12.345.678/0001-95"))
        assertTrue(texto.contains("Documento Auxiliar"))
        assertTrue(texto.contains("2126 1112 3456"))
        assertTrue(texto.contains("CPF 529.982.247-25"))
        assertTrue(texto.contains("SEM VALOR FISCAL"), "homologação avisa no cupom")
        assertTrue(texto.contains("Troco R$"))
        assertEquals(r.nota.qrCodeUrl, danfe.qrCode)
    }

    @Test
    fun cpfValido() {
        assertTrue(MontagemNfce.cpfValido("529.982.247-25"))
        assertFalse(MontagemNfce.cpfValido("529.982.247-24"))
        assertFalse(MontagemNfce.cpfValido("000.000.000-00"))
        assertNull(MontagemNfce.bandeira(null))
        assertEquals("06", MontagemNfce.bandeira("Elo"))
    }
}

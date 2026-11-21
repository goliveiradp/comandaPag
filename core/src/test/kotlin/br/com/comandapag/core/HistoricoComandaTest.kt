package br.com.comandapag.core

import br.com.comandapag.core.modelo.Perfil
import br.com.comandapag.core.modelo.Produto
import br.com.comandapag.core.modelo.TipoEvento
import br.com.comandapag.core.modelo.Usuario
import br.com.comandapag.core.servico.ComandaService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Vários garçons na mesma mesa: o histórico diz quem lançou e quem tirou cada coisa. */
class HistoricoComandaTest {
    private val repo = ComandaRepositoryEmMemoria()
    private var relogio = 1_000L
    private val service = ComandaService(repo, TerminalFalso(), CaixaRepositoryEmMemoria()) { relogio }

    private val ana = Usuario(id = 1, nome = "Ana", permissoes = Perfil.GARCOM.permissoes)
    private val carlos = Usuario(id = 2, nome = "Carlos", permissoes = Perfil.GARCOM.permissoes)
    private val bruno = Usuario(id = 3, nome = "Bruno", permissoes = Perfil.GERENTE.permissoes)
    private val chopp = Produto(id = 1, nome = "Chopp", precoCentavos = 990)
    private val fritas = Produto(id = 2, nome = "Fritas", precoCentavos = 3000)

    @Test
    fun registraQuemFezCadaAcao() = runTest {
        val c = service.abrir("Mesa 4", ana)
        relogio = 2_000
        service.adicionarItem(c.id, chopp, 2, operador = ana)
        relogio = 3_000
        service.adicionarItem(c.id, chopp, 1, operador = carlos)
        service.adicionarItem(c.id, fritas, 1, "sem sal", operador = carlos)
        // Carlos pede para tirar 1 chopp; Bruno (gerente) autoriza
        relogio = 4_000
        service.alterarQuantidade(c.id, 0, 2, operador = bruno, solicitante = carlos)
        // Ana aumenta pela tela (+)
        service.alterarQuantidade(c.id, 0, 3, operador = ana)
        service.definirTaxaServico(c.id, true, operador = bruno, solicitante = ana)

        val comanda = repo.buscar(c.id)!!
        val textos = comanda.historico.map { it.texto() }
        assertEquals(
            listOf(
                "Ana abriu a comanda",
                "Ana adicionou 2x Chopp",
                "Carlos adicionou 1x Chopp",
                "Carlos adicionou 1x Fritas (sem sal)",
                "Carlos removeu 1x Chopp, autorizado por Bruno",
                "Ana adicionou 1x Chopp",
                "Ana ligou os 10% de serviço, autorizado por Bruno",
            ),
            textos,
        )
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 3_000L, 4_000L, 4_000L, 4_000L), comanda.historico.map { it.em })
        assertEquals(listOf("Ana", "Carlos"), comanda.lancadoPor(comanda.itens[0]))
        assertEquals(listOf("Carlos"), comanda.lancadoPor(comanda.itens[1]))
    }

    @Test
    fun gerenteFazendoEleMesmoNaoApareceComoAutorizador() = runTest {
        val c = service.abrir("1", bruno)
        service.adicionarItem(c.id, chopp, 2, operador = bruno)
        service.removerItem(c.id, 0, operador = bruno, solicitante = bruno)
        val ultimo = repo.buscar(c.id)!!.historico.last()
        assertEquals(TipoEvento.REMOVEU, ultimo.tipo)
        assertEquals(2, ultimo.quantidade)
        assertNull(ultimo.autorizadoPor)
        service.cancelar(c.id, bruno, solicitante = ana)
        assertEquals("Ana cancelou a comanda, autorizado por Bruno", repo.buscar(c.id)!!.historico.last().texto())
    }
}

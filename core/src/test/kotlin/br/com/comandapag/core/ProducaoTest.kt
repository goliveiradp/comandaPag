package br.com.comandapag.core

import br.com.comandapag.core.modelo.Perfil
import br.com.comandapag.core.modelo.Produto
import br.com.comandapag.core.modelo.SetorProducao
import br.com.comandapag.core.modelo.Usuario
import br.com.comandapag.core.producao.ItemPedido
import br.com.comandapag.core.producao.PedidoProducao
import br.com.comandapag.core.producao.TicketProducao
import br.com.comandapag.core.servico.ComandaService
import br.com.comandapag.core.servico.RegraDeNegocioException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProducaoTest {
    private val repo = ComandaRepositoryEmMemoria()
    private val impressora = ImpressoraFalsa()
    private val service = ComandaService(repo, TerminalFalso(), CaixaRepositoryEmMemoria(), { impressora }) { 1_000L }

    private val garcom = Usuario(id = 1, nome = "Ana", permissoes = Perfil.GARCOM.permissoes)
    private val gerente = Usuario(id = 2, nome = "Bruno", permissoes = Perfil.GERENTE.permissoes)
    private val fritas = Produto(id = 1, nome = "Porção de fritas", precoCentavos = 3590, setor = SetorProducao.COZINHA)
    private val chopp = Produto(id = 2, nome = "Chopp", precoCentavos = 990, setor = SetorProducao.BAR)
    private val agua = Produto(id = 3, nome = "Água", precoCentavos = 500, setor = SetorProducao.NENHUM)

    private fun textos() = impressora.impressoes.map { it.joinToString("\n") }

    @Test
    fun enviaUmTicketPorSetorSoComOsItensNovos() = runTest {
        val c = service.abrir("Mesa 4", garcom)
        service.adicionarItem(c.id, fritas, 1, "sem sal")
        service.adicionarItem(c.id, chopp, 2)
        service.adicionarItem(c.id, agua)
        assertEquals(3, repo.buscar(c.id)!!.quantidadePendente) // água não conta

        val r = service.enviarPedido(c.id, garcom)
        assertEquals(listOf(SetorProducao.COZINHA, SetorProducao.BAR), r.enviados)
        assertEquals(2, impressora.impressoes.size)
        val (cozinha, bar) = textos()
        assertTrue(cozinha.contains("COZINHA") && cozinha.contains("1x Porção de fritas") && cozinha.contains(">> sem sal"))
        assertTrue(!cozinha.contains("Chopp") && !cozinha.contains("Água"))
        assertTrue(bar.contains("BAR") && bar.contains("2x Chopp"))
        assertTrue(cozinha.contains("Mesa 4") && cozinha.contains("Pedido 1") && cozinha.contains("Garçom: Ana"))

        val enviada = repo.buscar(c.id)!!
        assertEquals(0, enviada.quantidadePendente)
        assertEquals(1, enviada.pedidosEnviados)
        assertTrue(enviada.itens.all { it.quantidadeEnviada == it.quantidade })

        // Nada novo: não imprime de novo
        assertFailsWith<RegraDeNegocioException> { service.enviarPedido(c.id, garcom) }

        // Mais 1 chopp: o 2º pedido só tem o chopp novo
        service.adicionarItem(c.id, chopp)
        service.enviarPedido(c.id, garcom)
        val segundo = textos().last()
        assertTrue(segundo.contains("Pedido 2") && segundo.contains("1x Chopp") && !segundo.contains("fritas"))
    }

    @Test
    fun falhaNumSetorDeixaSoEleParaReenviar() = runTest {
        val c = service.abrir("1", garcom)
        service.adicionarItem(c.id, fritas)
        service.adicionarItem(c.id, chopp)
        impressora.falharSeContiver += "BAR"
        val r = service.enviarPedido(c.id, garcom)
        assertEquals(listOf(SetorProducao.COZINHA), r.enviados)
        assertEquals("SEM PAPEL", r.falhas[SetorProducao.BAR])
        assertEquals(1, repo.buscar(c.id)!!.quantidadePendente)

        impressora.falharSeContiver.clear()
        service.enviarPedido(c.id, garcom)
        val ultimo = textos().last()
        assertTrue(ultimo.contains("BAR") && ultimo.contains("1x Chopp"))
        assertEquals(0, repo.buscar(c.id)!!.quantidadePendente)
    }

    @Test
    fun reduzirItemJaEnviadoImprimeCancelamento() = runTest {
        val c = service.abrir("Mesa 2", garcom)
        service.adicionarItem(c.id, chopp, 3)
        service.enviarPedido(c.id, garcom)
        service.adicionarItem(c.id, chopp, 1) // 4 lançados, 3 enviados, 1 pendente

        // Tirar só o que ainda não foi enviado: não imprime cancelamento
        service.alterarQuantidade(c.id, 0, 3, gerente)
        assertEquals(1, impressora.impressoes.size)
        assertEquals(3, repo.buscar(c.id)!!.itens[0].quantidadeEnviada)

        // Agora tira 2 que já estavam no bar
        service.alterarQuantidade(c.id, 0, 1, gerente)
        val cancelamento = textos().last()
        assertTrue(cancelamento.contains("CANCELAMENTO") && cancelamento.contains("BAR") && cancelamento.contains("-2x Chopp"))
        assertEquals(1, repo.buscar(c.id)!!.itens[0].quantidadeEnviada)

        // Remover o item inteiro cancela o restante
        service.removerItem(c.id, 0, gerente)
        assertTrue(textos().last().contains("-1x Chopp"))
    }

    @Test
    fun itemSemProducaoNaoGeraTicket() = runTest {
        val c = service.abrir("1", garcom)
        service.adicionarItem(c.id, agua, 2)
        assertEquals(0, repo.buscar(c.id)!!.quantidadePendente)
        assertFailsWith<RegraDeNegocioException> { service.enviarPedido(c.id, garcom) }
        service.alterarQuantidade(c.id, 0, 1, gerente)
        assertTrue(impressora.impressoes.isEmpty())
    }

    @Test
    fun ticketCabeNaBobinaComLetraGrande() {
        val linhas = TicketProducao.gerar(
            PedidoProducao(
                SetorProducao.COZINHA, "Mesa 15 - varanda dos fundos", "Ana Paula", 3, 0,
                listOf(ItemPedido(2, "Picanha na chapa com fritas e farofa", "mal passada, sem cebola")),
            ),
            formatarHora = { "21/11 20:00" },
        )
        println(linhas.joinToString("\n"))
        assertTrue(linhas.all { it.length <= TicketProducao.COLUNAS })
        assertTrue(linhas.any { it.endsWith("Pedido 3") })
        assertTrue(linhas.joinToString(" ").contains("Mesa 15 - varanda dos fundos"), "nome da comanda não pode ser cortado")
    }
}

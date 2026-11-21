package br.com.comandapag

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import br.com.comandapag.terminal.ImpressoraSimulada
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Lança itens de cozinha, bar e sem preparo, envia o pedido, cancela um item e testa o envio automático. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PedidoProducaoTest : TesteDeTela() {

    @Before
    fun limparImpressora() = ImpressoraSimulada.historico.clear()

    private fun tickets() = ImpressoraSimulada.historico.toList()

    private fun esperarTickets(quantidade: Int) = regra.waitUntil(30_000) {
        org.robolectric.shadows.ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        tickets().size >= quantidade
    }

    @Test
    fun enviaPedidoParaCozinhaEBar() {
        criarAdministrador()
        regra.onNodeWithText("Produtos").performClick()
        cadastrarProduto("Porção de fritas", "35,90", "Petiscos", setor = "Cozinha")
        cadastrarProduto("Chopp", "9,90", "Bebidas", setor = "Bar")
        cadastrarProduto("Água", "5,00", "Bebidas", setor = "Não imprime")
        capturar("40-produtos-setor")

        regra.onNodeWithText("Comandas").performClick()
        abrirComanda("Mesa 4")
        regra.onNodeWithText("Adicionar", useUnmergedTree = true).performClick()
        esperarTexto("Chopp")
        regra.onNodeWithText("Porção de fritas").performClick()
        esperarTexto("1")
        regra.onNodeWithText("Chopp").performClick()
        regra.onNodeWithText("Chopp").performClick()
        regra.onNodeWithText("Água").performClick()
        regra.waitUntil(15_000) { regra.onAllNodes(androidx.compose.ui.test.hasText("2")).fetchSemanticsNodes().isNotEmpty() }
        regra.onNodeWithContentDescription("Fechar").performClick()

        // Água não conta: 1 fritas + 2 chopps
        esperarTexto("Enviar pedido (3)")
        esperarTexto("Novo · não enviado")
        capturar("41-itens-novos")
        clicar("Enviar pedido (3)")
        esperarTexto("Pedido enviado: Cozinha, Bar")
        esperarTickets(2)
        val (cozinha, bar) = tickets()
        assertTrue(cozinha, cozinha.contains("COZINHA") && cozinha.contains("1x Porção de fritas") && cozinha.contains("Pedido 1"))
        assertTrue(cozinha, !cozinha.contains("Chopp") && !cozinha.contains("Água"))
        assertTrue(bar, bar.contains("BAR") && bar.contains("2x Chopp") && bar.contains("Mesa 4"))
        esperarTexto("Enviado (bar)")
        regra.onNodeWithText("Enviar pedido", substring = true).assertDoesNotExist()
        capturar("42-itens-enviados")

        // Tira 1 chopp que já estava no bar: imprime cancelamento
        regra.onAllNodes(hasContentDescription("Diminuir") and hasClickAction())[1].performClick()
        esperarTickets(3)
        val cancelamento = tickets()[2]
        assertTrue(cancelamento, cancelamento.contains("CANCELAMENTO") && cancelamento.contains("-1x Chopp"))

        // Envio automático: liga em Ajustes, lança mais uma porção e fecha a lista
        regra.onNodeWithContentDescription("Voltar").performClick()
        esperarSumir("Pedido enviado")
        regra.onNodeWithText("Ajustes").performClick()
        clicar("Imprimir pedido ao fechar a lista de produtos")
        regra.onNodeWithText("Comandas").performClick()
        regra.onNodeWithText("Mesa 4").performClick()
        regra.onNodeWithText("Adicionar", useUnmergedTree = true).performClick()
        esperarTexto("Porção de fritas")
        regra.onNodeWithText("Porção de fritas").performClick()
        esperarTexto("2")
        regra.onNodeWithContentDescription("Fechar").performClick()
        esperarTickets(4)
        val automatico = tickets()[3]
        assertTrue(automatico, automatico.contains("Pedido 2") && automatico.contains("1x Porção de fritas"))
        assertEquals(4, tickets().size)
        esperarTexto("Enviado (cozinha)")
        capturar("43-envio-automatico")
    }
}

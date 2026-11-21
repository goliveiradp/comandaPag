package br.com.comandapag

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Percorre o fluxo principal usando a maquininha simulada:
 * primeiro acesso -> cadastrar produto -> abrir comanda -> lançar itens -> cobrar -> histórico.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FluxoComandaTest : TesteDeTela() {

    @Test
    fun fluxoCompleto() {
        esperarTexto("Primeiro acesso")
        capturar("00-primeiro-acesso")
        criarAdministrador("Dono", "1234")
        abrirCaixa()
        capturar("01-sem-comandas")

        // Produtos
        regra.onNodeWithText("Produtos").performClick()
        cadastrarProduto("Cerveja 600ml", "12,00", "Bebidas")
        cadastrarProduto("Refrigerante", "6,50", "Bebidas")
        cadastrarProduto("Porção de fritas", "35,90", "Petiscos")
        capturar("02-produtos")

        // Nova comanda
        regra.onNodeWithText("Comandas").performClick()
        abrirComanda("Mesa 4")
        esperarTexto("Garçom: Dono")

        // Lançar itens
        regra.onNodeWithText("Adicionar", useUnmergedTree = true).performClick()
        esperarTexto("Cerveja 600ml")
        regra.onNodeWithText("Cerveja 600ml").performClick()
        esperarTexto("1")
        regra.onNodeWithText("Cerveja 600ml").performClick()
        regra.onNodeWithText("Porção de fritas").performClick()
        regra.waitUntil(15_000) {
            regra.onAllNodesWithText("1").fetchSemanticsNodes().isNotEmpty() &&
                regra.onAllNodesWithText("2").fetchSemanticsNodes().isNotEmpty()
        }
        capturar("03-seletor-produtos")
        regra.onNodeWithContentDescription("Fechar").performClick()

        esperarTexto("R$ 59,90") // 2 x 12,00 + 35,90
        capturar("04-comanda")

        // Receber tudo no crédito
        regra.onNodeWithText("Receber").performClick()
        esperarTexto("Valor deste pagamento")
        capturar("05-forma-pagamento")
        clicar("Cobrar")

        esperarTexto("Pagamento aprovado")
        esperarTexto("Comanda paga")
        capturar("06-pagamento-aprovado")
        regra.onNodeWithText("Concluir").performClick()

        // Comanda saiu das abertas e foi para o histórico
        esperarTexto("Nenhuma comanda aberta")
        regra.onNodeWithText("Histórico").performClick()
        esperarTexto("Vendas de hoje")
        esperarTexto("Por garçom")
        regra.onAllNodes(hasText("Dono: R$ 59,90", substring = true)).onFirst().assertExists()
        capturar("07-historico")
    }
}

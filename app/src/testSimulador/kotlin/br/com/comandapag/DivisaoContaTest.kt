package br.com.comandapag

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Conta de R$ 71,80 dividida em 2: um paga em dinheiro (com troco) e o outro no crédito. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DivisaoContaTest : TesteDeTela() {

    @Test
    fun divideEmDuasPessoasComDinheiroECartao() {
        criarAdministrador()
        abrirCaixa()
        regra.onNodeWithText("Produtos").performClick()
        cadastrarProduto("Porção de fritas", "35,90", "Petiscos")
        regra.onNodeWithText("Comandas").performClick()
        abrirComanda("Mesa 2")
        regra.onNodeWithText("Adicionar", useUnmergedTree = true).performClick()
        esperarTexto("Porção de fritas")
        regra.onNodeWithText("Porção de fritas").performClick()
        esperarTexto("1")
        regra.onNodeWithText("Porção de fritas").performClick()
        regra.onNodeWithContentDescription("Fechar").performClick()
        esperarTexto("R$ 71,80")

        // Pessoa 1 de 2: dinheiro, entrega R$ 50
        regra.onNodeWithText("Receber").performClick()
        esperarTexto("Valor deste pagamento")
        botao("Mais pessoas").performClick()
        esperarTexto("Faltam 2 pessoas: R$ 35,90 cada")
        clicar("Dinheiro")
        clicar("R$ 50")
        esperarTexto("R$ 14,10") // troco
        capturar("20-dividir-dinheiro")
        clicar("Receber")
        esperarTexto("Troco: R$ 14,10")
        esperarTexto("Ainda falta R$ 35,90")
        capturar("21-troco")

        // Pessoa 2: crédito do restante
        clicar("Próximo pagamento")
        esperarTexto("Valor deste pagamento")
        regra.onNode(hasSetTextAction() and hasText("35,90")).assertExists() // sugere o que falta
        capturar("22-segunda-pessoa")
        clicar("Cobrar")
        esperarTexto("Pagamento aprovado")
        esperarTexto("Comanda paga")
        clicar("Concluir")
        esperarTexto("Nenhuma comanda aberta")

        // Histórico soma as duas formas
        regra.onNodeWithText("Histórico").performClick()
        esperarTexto("Dinheiro: R$ 35,90")
        esperarTexto("Crédito: R$ 35,90")
        esperarTexto("Dinheiro + Crédito") // na ordem em que foram pagos
        capturar("23-historico-misto")
    }

    @Test
    fun pagamentoParcialDeixaComandaAberta() {
        criarAdministrador()
        abrirCaixa()
        regra.onNodeWithText("Produtos").performClick()
        cadastrarProduto("Cerveja 600ml", "12,00", "Bebidas")
        regra.onNodeWithText("Comandas").performClick()
        abrirComanda("Mesa 3")
        regra.onNodeWithText("Adicionar", useUnmergedTree = true).performClick()
        esperarTexto("Cerveja 600ml")
        regra.onNodeWithText("Cerveja 600ml").performClick()
        regra.onNodeWithContentDescription("Fechar").performClick()
        esperarTexto("R$ 12,00")

        // Paga só R$ 5,00 no PIX e deixa para depois
        regra.onNodeWithText("Receber").performClick()
        esperarTexto("Valor deste pagamento")
        val campoValor = regra.onNode(hasSetTextAction() and hasText("Valor deste pagamento (R$)"))
        campoValor.performTextClearance()
        campoValor.performTextInput("5")
        clicar("PIX")
        clicar("Cobrar")
        esperarTexto("Ainda falta R$ 7,00")
        clicar("Depois")

        // A comanda continua aberta mostrando quanto falta
        esperarTexto("Falta")
        esperarTexto("Receber resto")
        capturar("24-parcial")
    }
}

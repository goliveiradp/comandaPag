package br.com.comandapag

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Dia de caixa: abre com R$ 100, vende R$ 12 em dinheiro, estorna, faz sangria de R$ 30
 * e fecha contando R$ 65 (faltam R$ 5).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CaixaEstornoTest : TesteDeTela() {

    @Test
    fun receberEstornarEFecharCaixa() {
        criarAdministrador()

        // Sem caixa aberto não dá para receber
        regra.onNodeWithText("Produtos").performClick()
        cadastrarProduto("Cerveja 600ml", "12,00", "Bebidas")
        regra.onNodeWithText("Comandas").performClick()
        abrirComanda("Mesa 1")
        regra.onNodeWithText("Adicionar", useUnmergedTree = true).performClick()
        esperarTexto("Cerveja 600ml")
        regra.onNodeWithText("Cerveja 600ml").performClick()
        regra.onNodeWithContentDescription("Fechar").performClick()
        esperarTexto("R$ 12,00")
        regra.onNodeWithText("Receber").performClick()
        esperarTexto("O caixa está fechado")
        capturar("30-caixa-fechado")
        clicar("Voltar")
        regra.onNodeWithContentDescription("Voltar").performClick()

        // Abre o caixa com R$ 100
        regra.onNodeWithText("Caixa").performClick()
        esperarTexto("Caixa fechado")
        capturar("31-caixa-fechado")
        clicar("Abrir caixa")
        campo("Fundo de troco (R$)").performTextInput("100")
        clicar("Confirmar")
        esperarTexto("Dinheiro esperado na gaveta")

        // Recebe a Mesa 1 em dinheiro
        regra.onNodeWithText("Comandas").performClick()
        regra.onNodeWithText("Mesa 1").performClick()
        esperarTexto("R$ 12,00")
        regra.onNodeWithText("Receber").performClick()
        esperarTexto("Valor deste pagamento")
        clicar("Dinheiro")
        clicar("R$ 20")
        clicar("Receber")
        esperarTexto("Troco: R$ 8,00")
        clicar("Concluir")
        esperarTexto("Nenhuma comanda aberta")

        // Estorna pelo histórico: a comanda volta a ficar aberta
        regra.onNodeWithText("Histórico").performClick()
        esperarTexto("Mesa 1")
        regra.onNodeWithText("Mesa 1").performClick()
        esperarTexto("Toque num pagamento para estornar")
        regra.onNode(hasText("Dinheiro ·", substring = true)).performClick()
        esperarTexto("Estornar pagamento?")
        capturar("32-confirmar-estorno")
        clicar("Estornar")
        esperarTexto("Estorno de R$ 12,00 realizado")
        esperarTexto("ESTORNADO")
        esperarTexto("Receber") // reaberta
        capturar("33-estornado")
        regra.onNodeWithContentDescription("Voltar").performClick()

        // Caixa: 100 + 12 - 12 = 100; sangria de 30 -> 70
        regra.onNodeWithText("Caixa").performClick()
        esperarTexto("R$ 100,00")
        clicar("Sangria")
        campo("Valor retirado do caixa (R$)").performTextInput("30")
        campo("Motivo").performTextInput("Cofre")
        clicar("Confirmar")
        esperarTexto("R$ 70,00")
        esperarTexto("Sangria registrada")
        esperarSumir("Sangria registrada")
        capturar("34-caixa-aberto")

        // Fecha contando 65: falta R$ 5
        clicar("Fechar caixa")
        campo("Dinheiro contado (R$)").performTextInput("65")
        esperarTexto("Falta")
        esperarTexto("-R$ 5,00")
        capturar("35-fechar-caixa")
        clicar("Fechar caixa")
        esperarTexto("FECHAMENTO DE CAIXA")
        esperarTexto("FALTA")
        capturar("36-relatorio")
        clicar("Fechar")
        esperarTexto("Fechamentos anteriores")
        esperarTexto("Caixa fechado")
    }
}

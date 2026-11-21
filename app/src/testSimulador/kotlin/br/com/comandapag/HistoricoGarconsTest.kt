package br.com.comandapag

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Dono e Ana atendem a mesma mesa; o histórico mostra quem lançou e quem tirou cada item. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HistoricoGarconsTest : TesteDeTela() {

    @Test
    fun doisGarconsNaMesmaMesa() {
        criarAdministrador("Dono", "1234")
        regra.onNodeWithText("Produtos").performClick()
        cadastrarProduto("Chopp", "9,90", "Bebidas")
        regra.onNodeWithText("Ajustes").performClick()
        regra.onNodeWithText("Usuários e permissões").performClick()
        regra.onNodeWithContentDescription("Novo usuário").performClick()
        campo("Nome").performTextInput("Ana")
        campo("PIN (4 a 6 números)").performTextInput("1111")
        regra.onNodeWithText("Salvar").performClick()
        esperarTexto("Ana")
        regra.onNodeWithContentDescription("Voltar").performClick()

        // Dono abre a mesa e lança 1 chopp
        regra.onNodeWithText("Comandas").performClick()
        abrirComanda("Mesa 4")
        regra.onNodeWithText("Adicionar", useUnmergedTree = true).performClick()
        esperarTexto("Chopp")
        regra.onNodeWithText("Chopp").performClick()
        esperarTexto("1")
        regra.onNodeWithContentDescription("Fechar").performClick()
        regra.onNodeWithContentDescription("Voltar").performClick()
        sair()

        // Ana entra, lança mais 1 chopp na mesma mesa e depois tira 1 (com o PIN do Dono)
        entrarComo("Ana", "1111")
        esperarTexto("Mesa 4")
        regra.onNodeWithText("Mesa 4").performClick()
        esperarTexto("Lançado por Dono")
        botao("Aumentar").performClick()
        esperarTexto("Lançado por Dono, Ana")
        botao("Diminuir").performClick()
        esperarTexto("Autorização necessária")
        digitarPin("1234")
        esperarTexto("R$ 9,90 · R$ 9,90")
        capturar("70-lancado-por")

        // Histórico completo
        regra.onNodeWithContentDescription("Mais opções").performClick()
        clicar("Histórico da comanda")
        esperarTexto("Histórico da Mesa 4")
        esperarTexto("Dono abriu a comanda")
        esperarTexto("Dono adicionou 1x Chopp")
        esperarTexto("Ana adicionou 1x Chopp")
        esperarTexto("Ana removeu 1x Chopp, autorizado por Dono")
        capturar("71-historico-comanda")
    }
}

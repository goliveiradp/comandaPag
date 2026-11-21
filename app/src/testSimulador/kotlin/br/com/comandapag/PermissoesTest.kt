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

/**
 * O administrador cadastra uma garçonete; ela não vê as abas restritas e,
 * para remover um item, precisa do PIN de quem tem permissão.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PermissoesTest : TesteDeTela() {

    @Test
    fun garcomPrecisaDeAutorizacaoParaRemoverItem() {
        criarAdministrador("Dono", "1234")

        // Administrador cadastra um produto e a garçonete Ana
        regra.onNodeWithText("Produtos").performClick()
        cadastrarProduto("Cerveja 600ml", "12,00", "Bebidas")
        regra.onNodeWithText("Ajustes").performClick()
        regra.onNodeWithText("Usuários e permissões").performClick()
        regra.onNodeWithContentDescription("Novo usuário").performClick()
        campo("Nome").performTextInput("Ana")
        campo("PIN (4 a 6 números)").performTextInput("1111")
        capturar("10-novo-usuario")
        regra.onNodeWithText("Salvar").performClick()
        esperarTexto("Ana")
        capturar("11-usuarios")
        regra.onNodeWithContentDescription("Voltar").performClick()
        sair()

        // PIN errado não entra
        esperarTexto("Quem está usando?")
        capturar("12-login")
        regra.onNodeWithText("Ana").performClick()
        digitarPin("9999")
        esperarTexto("PIN incorreto")
        capturar("13-pin-incorreto")

        // PIN certo
        digitarPin("1111")
        esperarTexto("Nenhuma comanda aberta")
        regra.onNodeWithText("Garçom").assertExists() // perfil na barra de cima
        regra.onNodeWithText("Produtos").assertDoesNotExist()
        regra.onNodeWithText("Histórico").assertDoesNotExist()

        // Ana abre a comanda e lança 2 cervejas
        abrirComanda("Mesa 9")
        esperarTexto("Garçom: Ana")
        regra.onNodeWithText("Adicionar", useUnmergedTree = true).performClick()
        esperarTexto("Cerveja 600ml")
        regra.onNodeWithText("Cerveja 600ml").performClick()
        esperarTexto("1")
        regra.onNodeWithText("Cerveja 600ml").performClick()
        regra.onNodeWithContentDescription("Fechar").performClick()
        esperarTexto("R$ 24,00")

        // Diminuir exige autorização
        botao("Diminuir").performClick()
        esperarTexto("Autorização necessária")
        capturar("14-autorizacao")
        regra.onNodeWithText("R$ 24,00", substring = true, useUnmergedTree = true) // continua 2 itens
        digitarPin("1234") // PIN do Dono
        esperarTexto("R$ 12,00 · R$ 12,00")
        regra.onNodeWithText("Autorização necessária").assertDoesNotExist()
        capturar("15-item-removido-com-autorizacao")
    }
}

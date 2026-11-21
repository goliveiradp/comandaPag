package br.com.comandapag

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import br.com.comandapag.terminal.ImpressoraSimulada
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** NFC-e no ambiente simulado: configura, cadastra NCM, recebe, emite com CPF, imprime e cancela. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotaFiscalTest : TesteDeTela() {

    @Before
    fun limpar() = ImpressoraSimulada.historico.clear()

    @Test
    fun emiteImprimeECancelaNfce() {
        criarAdministrador()
        abrirCaixa()

        // Ajustes > Nota fiscal: ambiente simulado
        regra.onNodeWithText("Ajustes").performClick()
        regra.onNodeWithText("Nota fiscal (NFC-e)").performScrollTo()
        clicar("Simulado")
        campo("CNPJ").performTextInput("12345678000195")
        campo("Razão social").performTextInput("Bar do Zé Ltda")
        campo("Endereço (sai no cupom)").performTextInput("Rua A, 1 - São Luís/MA")
        capturar("60-config-nfce")
        clicar("Salvar nota fiscal")
        esperarTexto("Configuração da NFC-e salva.")

        // Produto com NCM
        regra.onNodeWithText("Produtos").performClick()
        regra.onNodeWithContentDescription("Novo produto").performClick()
        campo("Nome").performTextInput("Prato feito")
        campo("Preço (R$)").performTextInput("25,00")
        clicar("Dados da nota fiscal (NFC-e) · falta NCM")
        campo("NCM (8 dígitos)").performTextInput("21069090")
        capturar("61-produto-fiscal")
        regra.onNodeWithText("Salvar").performClick()
        esperarTexto("Prato feito")

        // Comanda paga no PIX
        regra.onNodeWithText("Comandas").performClick()
        abrirComanda("Mesa 5")
        regra.onNodeWithText("Adicionar", useUnmergedTree = true).performClick()
        esperarTexto("Prato feito")
        regra.onNodeWithText("Prato feito").performClick()
        regra.onNodeWithContentDescription("Fechar").performClick()
        esperarTexto("R$ 25,00")
        regra.onNodeWithText("Receber").performClick()
        esperarTexto("Valor deste pagamento")
        clicar("PIX")
        clicar("Cobrar")
        esperarTexto("Comanda paga")

        // Emitir NFC-e com CPF
        clicar("Emitir NFC-e")
        esperarTexto("CPF na nota?")
        campo("CPF do consumidor").performTextInput("52998224725")
        capturar("62-cpf")
        clicar("Emitir")
        esperarTexto("NFC-e nº 1 autorizada")
        capturar("63-nfce-autorizada")

        val danfe = ImpressoraSimulada.historico.last()
        assertTrue(danfe, danfe.contains("Documento Auxiliar"))
        assertTrue(danfe, danfe.contains("CNPJ 12.345.678/0001-95"))
        assertTrue(danfe, danfe.contains("CPF 529.982.247-25"))
        assertTrue(danfe, danfe.contains("SEM VALOR FISCAL"))
        assertTrue(danfe, danfe.contains("[QR CODE: http"))

        // Com a nota autorizada, o estorno é bloqueado; cancela a nota e aí estorna
        regra.onNode(hasText("PIX ·", substring = true)).performClick()
        clicar("Estornar")
        esperarTexto("Cancele a nota antes de estornar")
        clicar("OK")
        clicar("Cancelar NFC-e")
        esperarTexto("Cancelar a NFC-e?")
        clicar("Cancelar NFC-e")
        esperarTexto("NFC-e cancelada")
        capturar("64-nfce-cancelada")
        regra.onNode(hasText("PIX ·", substring = true)).performClick()
        clicar("Estornar")
        esperarTexto("ESTORNADO")
    }
}

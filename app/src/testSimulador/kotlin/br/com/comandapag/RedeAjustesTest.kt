package br.com.comandapag

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Telas de rede e impressoras nos Ajustes (com endereços que não respondem). */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RedeAjustesTest : TesteDeTela() {

    @Test
    fun configuraImpressoraDeRedeEConexaoComServidor() {
        criarAdministrador()
        regra.onNodeWithText("Ajustes").performClick()
        regra.onNodeWithText("Rede (várias maquininhas)").performScrollTo()
        esperarTexto("Esta maquininha funciona sozinha.")

        // Impressora de rede para o bar que não responde: o teste avisa
        regra.onNodeWithText("Impressoras de produção").performScrollTo()
        capturar("50-impressoras")
        clicar("Impressora de rede")
        campo("IP da impressora (bar)").performTextInput("127.0.0.1")
        clicar("Testar bar")
        esperarTexto("Impressora 127.0.0.1 não respondeu")
        capturar("51-teste-impressora")

        // Conectar a um servidor que não existe
        regra.onNodeWithText("Rede (várias maquininhas)").performScrollTo()
        clicar("Terminal")
        esperarTexto("Conectar a um servidor")
        campo("Endereço do servidor (ex.: 192.168.0.10)").performTextInput("127.0.0.1:1")
        campo("Código de pareamento").performTextInput("123456")
        capturar("52-conectar-servidor")
        clicar("Conectar")
        esperarTexto("Não encontrei o servidor")
        capturar("53-servidor-nao-encontrado")
        clicar("Cancelar")

        // Pedir para virar servidor mostra o aviso antes de mudar
        clicar("Servidor")
        esperarTexto("Mudar para servidor?")
        capturar("54-confirmar-servidor")
        clicar("Cancelar")
    }

    @Test
    fun primeiroAcessoOfereceConectarAUmServidor() {
        esperarTexto("Primeiro acesso")
        clicar("Conectar a um servidor")
        esperarTexto("Veja o endereço e o código na maquininha servidor")
        capturar("55-primeiro-acesso-conectar")
    }
}

package br.com.comandapag

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import br.com.comandapag.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import java.io.File

/**
 * Percorre o fluxo principal usando a maquininha simulada:
 * cadastrar produto -> abrir comanda -> lançar itens -> cobrar -> histórico.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FluxoComandaTest {

    @get:Rule
    val regra = createAndroidComposeRule<MainActivity>()

    /** Salva uma imagem da tela (incluindo diálogos abertos) em build/capturas. */
    private fun capturar(nome: String) {
        regra.waitForIdle()
        if (System.getProperty("sem.captura") != null) return
        val dir = File(System.getProperty("capturas.dir") ?: "build/capturas").apply { mkdirs() }
        val principal = regra.activity.window.decorView
        val bmp = Bitmap.createBitmap(principal.width, principal.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        for (raiz in janelasAbertas()) {
            if (raiz !== principal) canvas.drawColor(0x99000000.toInt()) // escurece o fundo do diálogo
            val local = IntArray(2).also { raiz.getLocationOnScreen(it) }
            canvas.save()
            canvas.translate(local[0].toFloat(), local[1].toFloat())
            raiz.draw(canvas)
            canvas.restore()
        }
        File(dir, "$nome.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Suppress("UNCHECKED_CAST", "PrivateApi")
    private fun janelasAbertas(): List<View> {
        val classe = Class.forName("android.view.WindowManagerGlobal")
        val instancia = classe.getMethod("getInstance").invoke(null)
        val campo = classe.getDeclaredField("mViews").apply { isAccessible = true }
        return (campo.get(instancia) as List<View>).filter { it.isShown && it.width > 0 }
    }

    /** Espera o texto aparecer, avançando o relógio do Robolectric (a maquininha simulada usa delay). */
    private fun esperarTexto(texto: String) = regra.waitUntil(timeoutMillis = 15_000) {
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        regra.onAllNodes(hasText(texto, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun cadastrarProduto(nome: String, preco: String, categoria: String) {
        regra.onNodeWithContentDescription("Novo produto").performClick()
        regra.onNode(hasSetTextAction() and hasText("Nome")).performTextInput(nome)
        regra.onNode(hasSetTextAction() and hasText("Preço (R$)")).performTextInput(preco)
        regra.onNode(hasSetTextAction() and hasText("Categoria (opcional)")).performTextInput(categoria)
        regra.onNodeWithText("Salvar").performClick()
        esperarTexto(nome)
    }

    @Test
    fun fluxoCompleto() {
        capturar("01-sem-comandas")

        // Produtos
        regra.onNodeWithText("Produtos").performClick()
        cadastrarProduto("Cerveja 600ml", "12,00", "Bebidas")
        cadastrarProduto("Refrigerante", "6,50", "Bebidas")
        cadastrarProduto("Porção de fritas", "35,90", "Petiscos")
        capturar("02-produtos")

        // Nova comanda
        regra.onNodeWithText("Comandas").performClick()
        regra.onNodeWithText("Nova comanda", useUnmergedTree = true).performClick()
        regra.onNode(hasSetTextAction()).performTextInput("Mesa 4")
        regra.onNodeWithText("Abrir").performClick()
        esperarTexto("Comanda vazia")

        // Lançar itens
        regra.onNodeWithText("Adicionar", useUnmergedTree = true).performClick()
        esperarTexto("Cerveja 600ml")
        regra.onNodeWithText("Cerveja 600ml").performClick()
        esperarTexto("1")
        regra.onNodeWithText("Cerveja 600ml").performClick()
        esperarTexto("2")
        regra.onNodeWithText("Porção de fritas").performClick()
        regra.waitUntil(15_000) { regra.onAllNodesWithText("1").fetchSemanticsNodes().isNotEmpty() &&
            regra.onAllNodesWithText("2").fetchSemanticsNodes().isNotEmpty() }
        capturar("03-seletor-produtos")
        regra.onNodeWithContentDescription("Fechar").performClick()

        esperarTexto("R$ 59,90")
        regra.onNodeWithText("Total").assertExists() // subtotal e total: 2 x 12,00 + 35,90 = 59,90
        capturar("04-comanda")

        // Cobrar no crédito
        regra.onNodeWithText("Cobrar").performClick()
        esperarTexto("Cobrar R$ 59,90")
        capturar("05-forma-pagamento")
        // O botão "Cobrar" do diálogo é o último nó com esse texto
        val botoes = regra.onAllNodesWithText("Cobrar")
        botoes[botoes.fetchSemanticsNodes().size - 1].performClick()

        esperarTexto("Pagamento aprovado")
        capturar("06-pagamento-aprovado")
        regra.onNodeWithText("Concluir").performClick()

        // Comanda saiu das abertas e foi para o histórico
        esperarTexto("Nenhuma comanda aberta")
        regra.onNodeWithText("Histórico").performClick()
        esperarTexto("Vendas de hoje")
        regra.onAllNodesWithText("R$ 59,90").onFirst().assertExists()
        capturar("07-historico")
    }
}

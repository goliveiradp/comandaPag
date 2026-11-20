package br.com.comandapag

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import br.com.comandapag.ui.MainActivity
import org.junit.Rule
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/** Base dos testes de interface: abre o app e oferece atalhos para as ações comuns. */
abstract class TesteDeTela {

    @get:Rule
    val regra = createAndroidComposeRule<MainActivity>()

    /** Espera o texto aparecer, avançando o relógio do Robolectric (a maquininha simulada usa delay). */
    fun esperarTexto(texto: String) = regra.waitUntil(timeoutMillis = 30_000) {
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
        regra.onAllNodes(hasText(texto, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    fun campo(rotulo: String): SemanticsNodeInteraction = regra.onNode(hasSetTextAction() and hasText(rotulo))

    /**
     * Clica no elemento com o [texto]. Se houver mais de um (ex.: um botão na tela e outro
     * no diálogo aberto por cima), usa o último, que é o do diálogo.
     */
    fun clicar(texto: String) {
        val nos = regra.onAllNodes(hasText(texto) and hasClickAction())
        val no = nos[nos.fetchSemanticsNodes().size - 1]
        runCatching { no.performScrollTo() }
        no.performClick()
    }

    fun digitarPin(pin: String) {
        (pin.map { it.toString() } + "OK").forEach { tecla ->
            val no = regra.onNode(hasText(tecla) and hasClickAction())
            // Dentro do diálogo o teclado pode estar numa área com rolagem
            runCatching { no.performScrollTo() }
            no.performClick()
        }
    }

    fun criarAdministrador(nome: String = "Dono", pin: String = "1234") {
        esperarTexto("Primeiro acesso")
        campo("Seu nome").performTextInput(nome)
        campo("PIN (4 a 6 números)").performTextInput(pin)
        campo("Repita o PIN").performTextInput(pin)
        clicar("Criar administrador e entrar")
        esperarTexto("Nenhuma comanda aberta")
    }

    /** Abre o caixa pela aba Caixa e volta para Comandas. */
    fun abrirCaixa(fundo: String = "100,00") {
        regra.onNodeWithText("Caixa").performClick()
        esperarTexto("Caixa fechado")
        clicar("Abrir caixa")
        campo("Fundo de troco (R$)").performTextInput(fundo)
        clicar("Confirmar")
        esperarTexto("Dinheiro esperado na gaveta")
        regra.onNodeWithText("Comandas").performClick()
        esperarTexto("Nova comanda")
    }

    fun entrarComo(nome: String, pin: String) {
        esperarTexto("Quem está usando?")
        regra.onNodeWithText(nome).performClick()
        digitarPin(pin)
    }

    fun sair() = regra.onNodeWithText("Sair").performClick()

    fun cadastrarProduto(nome: String, preco: String, categoria: String) {
        regra.onNodeWithContentDescription("Novo produto").performClick()
        campo("Nome").performTextInput(nome)
        campo("Preço (R$)").performTextInput(preco)
        campo("Categoria (opcional)").performTextInput(categoria)
        regra.onNodeWithText("Salvar").performClick()
        esperarTexto(nome)
    }

    fun abrirComanda(identificacao: String) {
        regra.onNodeWithText("Nova comanda", useUnmergedTree = true).performClick()
        regra.onNode(hasSetTextAction()).performTextInput(identificacao)
        regra.onNodeWithText("Abrir").performClick()
        esperarTexto("Comanda vazia")
    }

    fun botao(descricao: String) = regra.onNode(hasContentDescription(descricao) and hasClickAction())

    /** Salva uma imagem da tela (incluindo diálogos abertos) em build/capturas. */
    fun capturar(nome: String) {
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
}

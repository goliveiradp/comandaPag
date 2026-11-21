package br.com.comandapag.rede

import br.com.comandapag.core.pagamento.ResultadoOperacao
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ImpressoraRedeTest {

    @Test
    fun enviaComandosEscPosParaAImpressora() = runBlocking {
        ServerSocket(0).use { impressoraFalsa ->
            var recebido = ByteArray(0)
            val leitor = thread { impressoraFalsa.accept().use { recebido = it.getInputStream().readBytes() } }

            val r = ImpressoraRede("127.0.0.1", impressoraFalsa.localPort)
                .imprimir(listOf("COZINHA", "1x Porção de fritas"), colunas = 24)
            leitor.join(5_000)

            assertEquals(ResultadoOperacao.Sucesso, r)
            assertContentEquals(byteArrayOf(0x1B, 0x40), recebido.copyOfRange(0, 2), "começa inicializando (ESC @)")
            assertContentEquals(byteArrayOf(0x1D, 0x21, 0x01), recebido.copyOfRange(2, 5), "letra alta no ticket")
            val texto = String(recebido, Charsets.US_ASCII)
            assertTrue(texto.contains("1x Porcao de fritas\n"), "acentos removidos: $texto")
            assertContentEquals(byteArrayOf(0x1D, 0x56, 0x42, 0x00), recebido.copyOfRange(recebido.size - 4, recebido.size), "termina cortando o papel")
        }
    }

    @Test
    fun impressoraDesligadaRetornaFalha() = runBlocking {
        val portaLivre = ServerSocket(0).use { it.localPort }
        val r = ImpressoraRede("127.0.0.1", portaLivre, tempoLimiteMs = 500).imprimir(listOf("teste"))
        assertIs<ResultadoOperacao.Falha>(r)
        assertTrue(r.mensagem.contains("127.0.0.1"))
    }

    @Test
    fun removeAcentos() {
        assertEquals("Acai com granola - Pao de queijo - Cafe", ImpressoraRede.semAcentos("Açaí com granola - Pão de queijo - Café"))
    }
}

package br.com.comandapag.rede

import br.com.comandapag.core.pagamento.Impressora
import br.com.comandapag.core.pagamento.ResultadoOperacao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.Normalizer

/**
 * Impressora térmica de rede no padrão ESC/POS (Epson, Elgin, Bematech, Daruma...),
 * que recebe os dados na porta TCP 9100.
 */
class ImpressoraRede(
    val host: String,
    val porta: Int = PORTA_PADRAO,
    private val tempoLimiteMs: Int = 4_000,
) : Impressora {

    override suspend fun imprimir(linhas: List<String>, colunas: Int): ResultadoOperacao =
        enviar(escPos(linhas, letraGrande = colunas <= 24))

    override suspend fun imprimirComQrCode(
        antes: List<String>,
        qrCode: String,
        depois: List<String>,
        colunas: Int,
    ): ResultadoOperacao = enviar(escPosComQrCode(antes, qrCode, depois))

    private suspend fun enviar(bytes: ByteArray): ResultadoOperacao = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, porta), tempoLimiteMs)
                socket.soTimeout = tempoLimiteMs
                socket.getOutputStream().apply {
                    write(bytes)
                    flush()
                }
            }
            ResultadoOperacao.Sucesso
        } catch (e: Exception) {
            ResultadoOperacao.Falha("Impressora $host não respondeu (${e.message ?: e.javaClass.simpleName})")
        }
    }

    companion object {
        const val PORTA_PADRAO = 9100

        private val INICIAR = byteArrayOf(0x1B, 0x40) // ESC @
        private val LETRA_NORMAL = byteArrayOf(0x1D, 0x21, 0x00) // GS ! 0
        private val ALTURA_DUPLA = byteArrayOf(0x1D, 0x21, 0x01) // GS ! 1 (mesma largura, dobro da altura)
        private val AVANCAR_E_CORTAR = byteArrayOf(0x1B, 0x64, 0x04, 0x1D, 0x56, 0x42, 0x00) // ESC d 4, GS V B 0

        /**
         * Monta os bytes ESC/POS. Os acentos são removidos, porque cada modelo usa uma
         * tabela de caracteres diferente e o ticket precisa sair legível em todas.
         */
        fun escPos(linhas: List<String>, letraGrande: Boolean): ByteArray {
            val saida = ByteArrayOutputStream()
            saida.write(INICIAR)
            saida.write(if (letraGrande) ALTURA_DUPLA else LETRA_NORMAL)
            linhas.forEach { saida.write((semAcentos(it) + "\n").toByteArray(Charsets.US_ASCII)) }
            saida.write(LETRA_NORMAL)
            saida.write(AVANCAR_E_CORTAR)
            return saida.toByteArray()
        }

        /** Texto, QR Code nativo da impressora (GS ( k, modelo 2) centralizado e texto depois. */
        fun escPosComQrCode(antes: List<String>, qrCode: String, depois: List<String>): ByteArray {
            val saida = ByteArrayOutputStream()
            saida.write(INICIAR)
            saida.write(LETRA_NORMAL)
            antes.forEach { saida.write((semAcentos(it) + "\n").toByteArray(Charsets.US_ASCII)) }
            saida.write(byteArrayOf(0x1B, 0x61, 0x01)) // centralizar
            saida.write(qrCodeEscPos(qrCode))
            saida.write("\n".toByteArray())
            saida.write(byteArrayOf(0x1B, 0x61, 0x00)) // alinhar à esquerda
            depois.forEach { saida.write((semAcentos(it) + "\n").toByteArray(Charsets.US_ASCII)) }
            saida.write(AVANCAR_E_CORTAR)
            return saida.toByteArray()
        }

        /** Comandos ESC/POS para imprimir um QR Code (modelo 2, módulo 5, correção M). */
        fun qrCodeEscPos(conteudo: String): ByteArray {
            val dados = conteudo.toByteArray(Charsets.US_ASCII)
            val tamanho = dados.size + 3
            val pL = (tamanho % 256).toByte()
            val pH = (tamanho / 256).toByte()
            val saida = ByteArrayOutputStream()
            saida.write(byteArrayOf(0x1D, 0x28, 0x6B, 0x04, 0x00, 0x31, 0x41, 0x32, 0x00)) // modelo 2
            saida.write(byteArrayOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x43, 0x05)) // tamanho do módulo
            saida.write(byteArrayOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x45, 0x31)) // correção de erro M
            saida.write(byteArrayOf(0x1D, 0x28, 0x6B, pL, pH, 0x31, 0x50, 0x30)) // guardar os dados
            saida.write(dados)
            saida.write(byteArrayOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x51, 0x30)) // imprimir
            return saida.toByteArray()
        }

        fun semAcentos(texto: String): String =
            Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .map { if (it.code in 32..126) it else '?' }
                .joinToString("")
    }
}

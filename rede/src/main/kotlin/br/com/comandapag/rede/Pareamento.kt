package br.com.comandapag.rede

import java.security.SecureRandom

/**
 * Controle de quais aparelhos podem falar com o servidor. O servidor mostra um código de
 * 6 dígitos; o terminal informa o código uma vez e recebe um token que guarda para sempre.
 */
interface Pareamento {
    /** Código mostrado na tela do servidor. */
    val codigo: String

    fun tokenValido(token: String?): Boolean

    /** Troca o código por um token. Null se o código estiver errado. */
    fun parear(codigo: String, aparelho: String): String?

    companion object {
        private val aleatorio = SecureRandom()

        fun novoCodigo(): String = (0 until 6).joinToString("") { aleatorio.nextInt(10).toString() }

        fun novoToken(): String = ByteArray(24).also { aleatorio.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}

/** Implementação simples (os testes usam; o app guarda os tokens nas preferências). */
class PareamentoEmMemoria(override val codigo: String = Pareamento.novoCodigo()) : Pareamento {
    private val tokens = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun tokenValido(token: String?): Boolean = token != null && tokens.containsKey(token)

    override fun parear(codigo: String, aparelho: String): String? {
        if (codigo != this.codigo) return null
        return Pareamento.novoToken().also { tokens[it] = aparelho }
    }
}

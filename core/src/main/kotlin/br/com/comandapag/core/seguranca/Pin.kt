package br.com.comandapag.core.seguranca

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PIN numérico de 4 a 6 dígitos. É guardado como hash SHA-256 com salt
 * aleatório por usuário e várias iterações, para não ficar legível no banco.
 */
object Pin {
    const val TAMANHO_MINIMO = 4
    const val TAMANHO_MAXIMO = 6
    private const val ITERACOES = 10_000

    fun valido(pin: String): Boolean = pin.length in TAMANHO_MINIMO..TAMANHO_MAXIMO && pin.all { it in '0'..'9' }

    fun gerarSalt(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()

    fun hash(pin: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        var bytes = (salt + pin).toByteArray()
        repeat(ITERACOES) { bytes = md.digest(bytes + salt.toByteArray()) }
        return bytes.toHex()
    }

    /** Compara em tempo constante. */
    fun confere(pin: String, salt: String, hashEsperado: String): Boolean =
        MessageDigest.isEqual(hash(pin, salt).toByteArray(), hashEsperado.toByteArray())

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

package br.com.comandapag.core.modelo

/**
 * Valores monetários são sempre guardados em centavos (Long) para evitar
 * erros de arredondamento com ponto flutuante. A PagBank também trabalha
 * com centavos (ex.: R$ 10,00 = 1000).
 */
object Dinheiro {

    /** Formata centavos no padrão brasileiro: 123450 -> "R$ 1.234,50". */
    fun formatar(centavos: Long): String {
        val negativo = centavos < 0
        val abs = kotlin.math.abs(centavos)
        val reais = (abs / 100).toString().reversed().chunked(3).joinToString(".").reversed()
        val cents = (abs % 100).toString().padStart(2, '0')
        return (if (negativo) "-R$ " else "R$ ") + reais + "," + cents
    }

    /**
     * Converte o texto digitado pelo usuário em centavos.
     * Aceita "12", "12,5", "12,50", "1.234,56", "R$ 3,00" e "12.50".
     * Retorna null se o texto não for um valor válido.
     */
    fun parse(texto: String): Long? {
        var t = texto.trim().removePrefix("R$").trim()
        if (t.isEmpty()) return null
        t = when {
            t.contains(',') -> t.replace(".", "").replace(',', '.')
            // "12.50" (ponto decimal) vs "1.234" (milhar): só é decimal se tiver 1-2 casas
            t.count { it == '.' } == 1 && t.substringAfter('.').length <= 2 -> t
            else -> t.replace(".", "")
        }
        if (!Regex("""\d+(\.\d{1,2})?""").matches(t)) return null
        val partes = t.split('.')
        val reais = partes[0].toLongOrNull() ?: return null
        val cents = partes.getOrNull(1)?.padEnd(2, '0')?.toLong() ?: 0L
        return reais * 100 + cents
    }
}

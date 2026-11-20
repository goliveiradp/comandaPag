package br.com.comandapag.core.impressao

import br.com.comandapag.core.modelo.Comanda
import br.com.comandapag.core.modelo.Dinheiro
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Gera as linhas de texto do extrato da comanda para a impressora térmica. */
object ExtratoComanda {

    /** Colunas que cabem na bobina de 58 mm da Smart 2 com fonte monoespaçada. */
    const val COLUNAS = 32

    fun gerar(
        comanda: Comanda,
        nomeEstabelecimento: String,
        colunas: Int = COLUNAS,
        formatarData: (Long) -> String = ::dataPadrao,
    ): List<String> {
        val separador = "-".repeat(colunas)
        val linhas = mutableListOf<String>()

        if (nomeEstabelecimento.isNotBlank()) linhas += centralizar(nomeEstabelecimento.uppercase(), colunas)
        linhas += centralizar("EXTRATO DE CONSUMO", colunas)
        linhas += separador
        linhas += "Comanda: ${comanda.identificacao}"
        if (comanda.garcomNome.isNotBlank()) linhas += quebrar("Garçom: ${comanda.garcomNome}", colunas)
        linhas += "Aberta: ${formatarData(comanda.abertaEm)}"
        comanda.fechadaEm?.let { linhas += "Fechada: ${formatarData(it)}" }
        linhas += separador

        for (item in comanda.itens) {
            linhas += quebrar("${item.quantidade}x ${item.nome}", colunas)
            linhas += colunasEsqDir(
                "   ${item.quantidade} x ${Dinheiro.formatar(item.precoUnitarioCentavos)}",
                Dinheiro.formatar(item.subtotalCentavos),
                colunas,
            )
            if (item.observacao.isNotBlank()) linhas += quebrar("   Obs: ${item.observacao}", colunas)
        }

        linhas += separador
        linhas += colunasEsqDir("Subtotal", Dinheiro.formatar(comanda.subtotalCentavos), colunas)
        if (comanda.taxaServico) {
            linhas += colunasEsqDir(
                "Serviço (${Comanda.PERCENTUAL_SERVICO}%)",
                Dinheiro.formatar(comanda.taxaServicoCentavos),
                colunas,
            )
        }
        linhas += colunasEsqDir("TOTAL", Dinheiro.formatar(comanda.totalCentavos), colunas)

        if (comanda.pagamentos.isNotEmpty()) {
            linhas += separador
            for (p in comanda.pagamentos) {
                val parcelas = if (p.parcelas > 1) " ${p.parcelas}x" else ""
                val estorno = if (p.estornado) " ESTORNADO" else ""
                linhas += colunasEsqDir("${p.forma.descricao}$parcelas$estorno", Dinheiro.formatar(p.valorCentavos), colunas)
                p.valorRecebidoCentavos?.takeIf { p.trocoCentavos > 0 }?.let {
                    linhas += colunasEsqDir("   Recebido", Dinheiro.formatar(it), colunas)
                    linhas += colunasEsqDir("   Troco", Dinheiro.formatar(p.trocoCentavos), colunas)
                }
                p.bandeira?.takeIf { it.isNotBlank() }?.let { linhas += "   Bandeira: $it" }
                p.codigoTransacao?.takeIf { it.isNotBlank() }?.let { linhas += quebrar("   Transação: $it", colunas) }
            }
            linhas += colunasEsqDir("Total pago", Dinheiro.formatar(comanda.totalPagoCentavos), colunas)
            if (comanda.restanteCentavos > 0) {
                linhas += colunasEsqDir("FALTA PAGAR", Dinheiro.formatar(comanda.restanteCentavos), colunas)
            }
        }

        linhas += separador
        linhas += centralizar("Não é documento fiscal", colunas)
        return linhas
    }

    fun dataPadrao(millis: Long): String =
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.forLanguageTag("pt-BR")).format(Date(millis))

    internal fun colunasEsqDir(esquerda: String, direita: String, colunas: Int): String {
        val espaco = colunas - direita.length
        val esq = if (esquerda.length >= espaco) esquerda.take((espaco - 1).coerceAtLeast(0)) else esquerda
        return esq.padEnd(espaco) + direita
    }

    internal fun centralizar(texto: String, colunas: Int): String {
        val t = texto.take(colunas)
        return " ".repeat((colunas - t.length) / 2) + t
    }

    /** Quebra o texto em linhas de até [colunas] caracteres, preferindo quebrar entre palavras. */
    internal fun quebrar(texto: String, colunas: Int): List<String> {
        // Mantém o recuo inicial em todas as linhas
        val recuo = texto.takeWhile { it == ' ' }
        if (recuo.isNotEmpty() && recuo.length < colunas) {
            return quebrar(texto.drop(recuo.length), colunas - recuo.length).map { recuo + it }
        }
        val linhas = mutableListOf<String>()
        var atual = StringBuilder()
        for (palavra in texto.split(' ')) {
            val candidata = if (atual.isEmpty()) palavra else "$atual $palavra"
            when {
                candidata.length <= colunas -> atual = StringBuilder(candidata)
                palavra.length > colunas -> {
                    // Palavra maior que a linha: corta em pedaços
                    var resto = candidata
                    while (resto.length > colunas) {
                        linhas += resto.take(colunas)
                        resto = resto.drop(colunas)
                    }
                    atual = StringBuilder(resto)
                }
                else -> {
                    linhas += atual.toString()
                    atual = StringBuilder(palavra)
                }
            }
        }
        if (atual.isNotEmpty() || linhas.isEmpty()) linhas += atual.toString()
        return linhas
    }
}

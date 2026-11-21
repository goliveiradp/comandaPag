package br.com.comandapag.core.fiscal

import br.com.comandapag.core.impressao.ExtratoComanda
import br.com.comandapag.core.modelo.Comanda
import br.com.comandapag.core.modelo.Dinheiro
import br.com.comandapag.core.modelo.NotaFiscalConsumidor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** DANFE NFC-e: linhas antes do QR Code, o conteúdo do QR Code e linhas depois. */
data class Danfe(val antes: List<String>, val qrCode: String?, val depois: List<String>)

/** Documento auxiliar da NFC-e impresso para o consumidor. */
object DanfeNfce {

    fun gerar(
        comanda: Comanda,
        nota: NotaFiscalConsumidor,
        emitente: Emitente?,
        colunas: Int = ExtratoComanda.COLUNAS,
        formatarData: (Long) -> String = ::dataHora,
    ): Danfe {
        val sep = "-".repeat(colunas)
        val l = mutableListOf<String>()
        fun centro(t: String) = ExtratoComanda.quebrar(t, colunas).forEach { l += ExtratoComanda.centralizar(it, colunas) }
        fun valor(r: String, c: Long) { l += ExtratoComanda.colunasEsqDir(r, Dinheiro.formatar(c).removePrefix("R$ "), colunas) }

        emitente?.let { e ->
            centro(e.razaoSocial.uppercase())
            l += ExtratoComanda.quebrar("CNPJ ${formatarCnpj(e.cnpj)}" + (if (e.inscricaoEstadual.isNotBlank()) " IE ${e.inscricaoEstadual}" else ""), colunas)
            if (e.endereco.isNotBlank()) l += ExtratoComanda.quebrar(e.endereco, colunas)
        }
        l += sep
        centro("Documento Auxiliar da Nota Fiscal de Consumidor Eletrônica")
        l += sep

        comanda.itens.forEach { item ->
            l += ExtratoComanda.quebrar(item.nome, colunas)
            l += ExtratoComanda.colunasEsqDir(
                "  ${item.quantidade} UN x ${Dinheiro.formatar(item.precoUnitarioCentavos).removePrefix("R$ ")}",
                Dinheiro.formatar(item.subtotalCentavos).removePrefix("R$ "),
                colunas,
            )
        }
        l += sep
        l += ExtratoComanda.colunasEsqDir("Qtd. total de itens", comanda.quantidadeItens.toString(), colunas)
        valor("Valor total R$", comanda.subtotalCentavos)
        if (comanda.taxaServicoCentavos > 0) valor("Outras despesas (serviço)", comanda.taxaServicoCentavos)
        valor("Valor a pagar R$", comanda.totalCentavos)
        l += ExtratoComanda.colunasEsqDir("FORMA PAGAMENTO", "VALOR PAGO", colunas)
        val validos = comanda.pagamentos.filter { !it.estornado }
        validos.forEach { p -> valor(p.forma.descricao, p.valorRecebidoCentavos ?: p.valorCentavos) }
        val troco = validos.sumOf { it.trocoCentavos }
        if (troco > 0) valor("Troco R$", troco)
        l += sep

        nota.urlConsulta?.let {
            centro("Consulte pela Chave de Acesso em")
            l += ExtratoComanda.quebrar(it, colunas)
        }
        nota.chave?.let { centro(formatarChave(it)) }
        l += sep
        centro(nota.cpf?.let { "CONSUMIDOR - CPF ${formatarCpf(it)}" } ?: "CONSUMIDOR NÃO IDENTIFICADO")
        l += sep
        centro("NFC-e nº ${nota.numero ?: "-"} Série ${nota.serie ?: "-"} ${formatarData(nota.emitidaEm)}")
        nota.protocolo?.let { centro("Protocolo de autorização: $it") }
        if (nota.contingencia) {
            centro("EMITIDA EM CONTINGÊNCIA")
            centro("Pendente de autorização")
        }
        if (nota.ambiente != AmbienteFiscal.PRODUCAO.name) {
            centro("EMITIDA EM AMBIENTE DE HOMOLOGAÇÃO - SEM VALOR FISCAL")
        }

        val depois = mutableListOf<String>()
        depois += ExtratoComanda.centralizar("Comanda ${comanda.identificacao}", colunas)
        if (comanda.garcomNome.isNotBlank()) depois += ExtratoComanda.centralizar("Garçom: ${comanda.garcomNome}", colunas)
        return Danfe(l, nota.qrCodeUrl, depois)
    }

    fun formatarChave(chave: String): String = chave.filter(Char::isDigit).chunked(4).joinToString(" ")

    fun formatarCnpj(c: String): String {
        val d = c.filter(Char::isDigit)
        return if (d.length == 14) "${d.substring(0, 2)}.${d.substring(2, 5)}.${d.substring(5, 8)}/${d.substring(8, 12)}-${d.substring(12)}" else c
    }

    fun formatarCpf(c: String): String {
        val d = c.filter(Char::isDigit)
        return if (d.length == 11) "${d.substring(0, 3)}.${d.substring(3, 6)}.${d.substring(6, 9)}-${d.substring(9)}" else c
    }

    private fun dataHora(millis: Long): String =
        SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.forLanguageTag("pt-BR")).format(Date(millis))
}

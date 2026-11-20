package br.com.comandapag.core.modelo

enum class TipoMovimento(val descricao: String) {
    /** Dinheiro colocado na gaveta (ex.: reforço de troco). */
    SUPRIMENTO("Suprimento"),
    /** Dinheiro retirado da gaveta (ex.: levar para o cofre, pagar fornecedor). */
    SANGRIA("Sangria"),
    /** Devolução de um pagamento estornado. */
    ESTORNO("Estorno"),
}

data class MovimentoCaixa(
    val tipo: TipoMovimento,
    val valorCentavos: Long,
    val motivo: String,
    val em: Long,
    val por: String,
    /** Forma do pagamento estornado (só para [TipoMovimento.ESTORNO]). */
    val forma: FormaPagamento? = null,
)

data class Caixa(
    val id: Long = 0,
    val abertoEm: Long,
    val abertoPor: String,
    /** Fundo de troco colocado na abertura. */
    val valorInicialCentavos: Long,
    val movimentos: List<MovimentoCaixa> = emptyList(),
    val fechadoEm: Long? = null,
    val fechadoPor: String? = null,
    /** Dinheiro contado na gaveta no fechamento. */
    val valorContadoCentavos: Long? = null,
) {
    val aberto: Boolean get() = fechadoEm == null
}

/** Totais do caixa, para a tela e para o relatório de fechamento. */
data class ResumoCaixa(
    val caixa: Caixa,
    /** Vendas recebidas neste caixa, por forma (antes de estornos). */
    val vendasPorForma: Map<FormaPagamento, Long>,
    val estornosPorForma: Map<FormaPagamento, Long>,
    val suprimentosCentavos: Long,
    val sangriasCentavos: Long,
    val quantidadePagamentos: Int,
) {
    val totalVendasCentavos: Long get() = vendasPorForma.values.sum()
    val totalEstornosCentavos: Long get() = estornosPorForma.values.sum()

    /** Quanto deveria haver de dinheiro na gaveta. */
    val dinheiroEsperadoCentavos: Long
        get() = caixa.valorInicialCentavos +
            (vendasPorForma[FormaPagamento.DINHEIRO] ?: 0) +
            suprimentosCentavos - sangriasCentavos -
            (estornosPorForma[FormaPagamento.DINHEIRO] ?: 0)

    /** Contado − esperado: positivo = sobra, negativo = falta. Null enquanto não fechou. */
    val diferencaCentavos: Long? get() = caixa.valorContadoCentavos?.let { it - dinheiroEsperadoCentavos }

    companion object {
        fun calcular(caixa: Caixa, pagamentosDoCaixa: List<Pagamento>): ResumoCaixa {
            val estornos = caixa.movimentos.filter { it.tipo == TipoMovimento.ESTORNO && it.forma != null }
            return ResumoCaixa(
                caixa = caixa,
                vendasPorForma = pagamentosDoCaixa.groupBy { it.forma }.mapValues { (_, l) -> l.sumOf { it.valorCentavos } },
                estornosPorForma = estornos.groupBy { it.forma!! }.mapValues { (_, l) -> l.sumOf { it.valorCentavos } },
                suprimentosCentavos = caixa.movimentos.filter { it.tipo == TipoMovimento.SUPRIMENTO }.sumOf { it.valorCentavos },
                sangriasCentavos = caixa.movimentos.filter { it.tipo == TipoMovimento.SANGRIA }.sumOf { it.valorCentavos },
                quantidadePagamentos = pagamentosDoCaixa.size,
            )
        }
    }
}

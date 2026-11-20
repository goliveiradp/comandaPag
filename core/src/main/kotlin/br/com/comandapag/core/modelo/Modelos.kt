package br.com.comandapag.core.modelo

data class Produto(
    val id: Long = 0,
    val nome: String,
    val precoCentavos: Long,
    val categoria: String = "",
    val ativo: Boolean = true,
)

data class ItemComanda(
    val produtoId: Long,
    val nome: String,
    val precoUnitarioCentavos: Long,
    val quantidade: Int,
    val observacao: String = "",
) {
    val subtotalCentavos: Long get() = precoUnitarioCentavos * quantidade
}

enum class StatusComanda { ABERTA, PAGA, CANCELADA }

enum class FormaPagamento(val descricao: String) {
    CREDITO("Crédito"),
    DEBITO("Débito"),
    PIX("PIX"),
}

data class Pagamento(
    val forma: FormaPagamento,
    val valorCentavos: Long,
    val parcelas: Int,
    val codigoTransacao: String?,
    val idTransacao: String?,
    val bandeira: String?,
    val pagoEm: Long,
)

data class Comanda(
    val id: Long = 0,
    /** Número da comanda, mesa ou nome do cliente. */
    val identificacao: String,
    val abertaEm: Long,
    val itens: List<ItemComanda> = emptyList(),
    val status: StatusComanda = StatusComanda.ABERTA,
    /** Cobra os 10% de serviço sobre o subtotal. */
    val taxaServico: Boolean = false,
    val fechadaEm: Long? = null,
    val pagamento: Pagamento? = null,
) {
    val subtotalCentavos: Long get() = itens.sumOf { it.subtotalCentavos }

    /** 10% do subtotal, arredondado para o centavo mais próximo. */
    val taxaServicoCentavos: Long
        get() = if (taxaServico) (subtotalCentavos * PERCENTUAL_SERVICO + 50) / 100 else 0

    val totalCentavos: Long get() = subtotalCentavos + taxaServicoCentavos

    val quantidadeItens: Int get() = itens.sumOf { it.quantidade }

    val estaAberta: Boolean get() = status == StatusComanda.ABERTA

    companion object {
        const val PERCENTUAL_SERVICO = 10L
    }
}

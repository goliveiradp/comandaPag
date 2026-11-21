package br.com.comandapag.core.modelo

import kotlinx.serialization.Serializable

/** Onde o item é preparado; define em qual ticket de produção ele sai. */
enum class SetorProducao(val descricao: String) {
    COZINHA("Cozinha"),
    BAR("Bar"),
    /** Item pronto (ex.: água em garrafa): não gera ticket. */
    NENHUM("Não imprime"),
}

@Serializable
data class Produto(
    val id: Long = 0,
    val nome: String,
    val precoCentavos: Long,
    val categoria: String = "",
    val ativo: Boolean = true,
    val setor: SetorProducao = SetorProducao.COZINHA,
)

@Serializable
data class ItemComanda(
    val produtoId: Long,
    val nome: String,
    val precoUnitarioCentavos: Long,
    val quantidade: Int,
    val observacao: String = "",
    val setor: SetorProducao = SetorProducao.COZINHA,
    /** Quantas unidades já foram enviadas para a cozinha/bar. */
    val quantidadeEnviada: Int = 0,
) {
    val subtotalCentavos: Long get() = precoUnitarioCentavos * quantidade

    /** Unidades lançadas que ainda não foram para a produção. */
    val quantidadePendente: Int
        get() = if (setor == SetorProducao.NENHUM) 0 else (quantidade - quantidadeEnviada).coerceAtLeast(0)
}

enum class StatusComanda { ABERTA, PAGA, CANCELADA }

enum class FormaPagamento(val descricao: String, val naMaquininha: Boolean) {
    CREDITO("Crédito", true),
    DEBITO("Débito", true),
    PIX("PIX", true),
    DINHEIRO("Dinheiro", false),
}

@Serializable
data class Pagamento(
    val forma: FormaPagamento,
    /** Quanto deste pagamento abate da comanda. */
    val valorCentavos: Long,
    val parcelas: Int = 1,
    val codigoTransacao: String? = null,
    val idTransacao: String? = null,
    val bandeira: String? = null,
    val pagoEm: Long,
    val recebidoPor: String = "",
    /** Dinheiro: quanto o cliente entregou e quanto voltou de troco. */
    val valorRecebidoCentavos: Long? = null,
    val trocoCentavos: Long = 0,
    /** Caixa em que o pagamento entrou. */
    val caixaId: Long? = null,
    /** Preenchidos quando o pagamento é estornado. Pagamento estornado não conta como pago. */
    val estornadoEm: Long? = null,
    val estornadoPor: String? = null,
) {
    val estornado: Boolean get() = estornadoEm != null
}

@Serializable
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
    /** Pagamentos recebidos (a conta pode ser dividida e paga em várias formas). */
    val pagamentos: List<Pagamento> = emptyList(),
    /** Garçom responsável (quem abriu a comanda). */
    val garcomId: Long = 0,
    val garcomNome: String = "",
    /** Quem cobrou ou cancelou a comanda. */
    val fechadaPor: String? = null,
    /** Quantos pedidos de produção já foram enviados (numera os tickets). */
    val pedidosEnviados: Int = 0,
    /**
     * Aumenta a cada gravação. Quem grava precisa informar a versão que leu; se outro
     * aparelho gravou antes, o repositório recusa com ConflitoDeVersaoException.
     */
    val versao: Long = 0,
) {
    /** Unidades lançadas que ainda não foram para a cozinha/bar. */
    val quantidadePendente: Int get() = itens.sumOf { it.quantidadePendente }

    val subtotalCentavos: Long get() = itens.sumOf { it.subtotalCentavos }

    /** 10% do subtotal, arredondado para o centavo mais próximo. */
    val taxaServicoCentavos: Long
        get() = if (taxaServico) (subtotalCentavos * PERCENTUAL_SERVICO + 50) / 100 else 0

    val totalCentavos: Long get() = subtotalCentavos + taxaServicoCentavos

    /** Soma dos pagamentos válidos (estornados não contam). */
    val totalPagoCentavos: Long get() = pagamentos.filter { !it.estornado }.sumOf { it.valorCentavos }

    /** Quanto ainda falta pagar. */
    val restanteCentavos: Long get() = (totalCentavos - totalPagoCentavos).coerceAtLeast(0)

    val quantidadeItens: Int get() = itens.sumOf { it.quantidade }

    val estaAberta: Boolean get() = status == StatusComanda.ABERTA

    companion object {
        const val PERCENTUAL_SERVICO = 10L
    }
}

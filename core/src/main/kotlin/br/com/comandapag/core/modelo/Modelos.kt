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
    /** Dados para a nota fiscal (definidos pelo contador). */
    val fiscal: DadosFiscais = DadosFiscais(),
)

/**
 * Tributação do produto na NFC-e. Os padrões servem para o Simples Nacional
 * (CSOSN 102, CFOP 5102); o contador confirma produto a produto.
 */
@Serializable
data class DadosFiscais(
    /** NCM com 8 dígitos (ex.: 21069090 para refeições). */
    val ncm: String = "",
    val cfop: String = "5102",
    /** CSOSN no Simples Nacional (102, 500...) ou CST no regime normal. */
    val icmsSituacao: String = "102",
    /** 0 = nacional. */
    val origem: String = "0",
    val unidade: String = "UN",
    /** CST de PIS e COFINS (49 = outras operações de saída, comum no Simples). */
    val pisCofinsSituacao: String = "49",
) {
    val completos: Boolean get() = ncm.length == 8 && ncm.all(Char::isDigit) && cfop.length == 4 && icmsSituacao.isNotBlank()
}

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

enum class StatusNfce(val descricao: String) {
    AUTORIZADA("Autorizada"),
    REJEITADA("Rejeitada"),
    CANCELADA("Cancelada"),
}

/** Resultado da NFC-e guardado na comanda. */
@Serializable
data class NotaFiscalConsumidor(
    /** Referência usada no emissor (Focus NFe). */
    val ref: String,
    val status: StatusNfce,
    val ambiente: String,
    val emitidaEm: Long,
    val numero: String? = null,
    val serie: String? = null,
    val chave: String? = null,
    val qrCodeUrl: String? = null,
    val urlConsulta: String? = null,
    val protocolo: String? = null,
    val mensagem: String? = null,
    val cpf: String? = null,
    /** Emitida em contingência offline (Sefaz fora do ar); o emissor transmite depois. */
    val contingencia: Boolean = false,
    val canceladaEm: Long? = null,
) {
    val autorizada: Boolean get() = status == StatusNfce.AUTORIZADA
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
    /** NFC-e emitida para esta comanda (a mais recente). */
    val nfce: NotaFiscalConsumidor? = null,
    /** Quantas NFC-e já foram rejeitadas ou canceladas (muda a referência da próxima). */
    val tentativasNfce: Int = 0,
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
